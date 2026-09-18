package net.ofts.replay_mcp.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.AttributeKey;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.ofts.replay_mcp.bridge.BridgeRouter;
import net.ofts.replay_mcp.protocol.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BridgeWebSocketServer implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Replay MCP Bridge");
    private static final AttributeKey<RpcSession> SESSION = AttributeKey.valueOf("replay-mcp-session");
    private final Gson gson = new Gson();
    private final byte[] token;
    private final BridgeRouter router;
    private final ProtocolLimits limits;
    private final EventLoopGroup boss = new NioEventLoopGroup(1, daemonFactory("replay-mcp-accept"));
    private final EventLoopGroup workers = new NioEventLoopGroup(2, daemonFactory("replay-mcp-io"));
    private final ExecutorService requests = Executors.newFixedThreadPool(2, daemonFactory("replay-mcp-request"));
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private Channel server;

    public BridgeWebSocketServer(byte[] token, BridgeRouter router, ProtocolLimits limits) {
        this.token = token.clone(); this.router = router; this.limits = limits;
    }

    public int start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true).childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        clients.add(channel);
                        channel.pipeline().addLast(new HttpServerCodec());
                        channel.pipeline().addLast(new HttpObjectAggregator(limits.maxTextBytes()));
                        channel.pipeline().addLast(new UpgradeAuthenticator());
                        channel.pipeline().addLast(new WebSocketServerProtocolHandler("/bridge", null, true, limits.maxTextBytes()));
                        channel.pipeline().addLast(new FrameHandler());
                    }
                });
        server = bootstrap.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync().channel();
        router.eventSink(this::publish);
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    private void publish(String method, JsonObject params) {
        JsonObject notification = new JsonObject(); notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method); notification.add("params", params.deepCopy());
        String payload = gson.toJson(notification);
        clients.stream().filter(Channel::isActive).filter(c -> { RpcSession s = c.attr(SESSION).get(); return s != null && s.helloComplete(); })
                .forEach(c -> c.eventLoop().execute(() -> c.writeAndFlush(new TextWebSocketFrame(payload))));
    }

    private final class UpgradeAuthenticator extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            if (!request.uri().equals("/bridge") || !validToken(request.headers())) {
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                        Unpooled.copiedBuffer("unauthenticated", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            RpcSession session = new RpcSession(UUID.randomUUID().toString(), token);
            session.authenticate(token);
            context.channel().attr(SESSION).set(session);
            context.fireChannelRead(request.retain());
        }

        private boolean validToken(HttpHeaders headers) {
            String supplied = headers.get("X-Replay-MCP-Token");
            if (supplied == null) {
                String authorization = headers.get(HttpHeaderNames.AUTHORIZATION);
                if (authorization != null && authorization.startsWith("Bearer ")) supplied = authorization.substring(7);
            }
            if (supplied == null) return false;
            try { return MessageDigest.isEqual(token, HexFormat.of().parseHex(supplied)); }
            catch (IllegalArgumentException ignored) { return false; }
        }
    }

    private final class FrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) { context.close(); return; }
            if (frame instanceof PingWebSocketFrame ping) { context.writeAndFlush(new PongWebSocketFrame(ping.content().retain())); return; }
            if (!(frame instanceof TextWebSocketFrame text)) { context.writeAndFlush(new CloseWebSocketFrame(1003, "text frames only")); return; }
            if (text.content().readableBytes() > limits.maxTextBytes()) { context.writeAndFlush(new CloseWebSocketFrame(1009, "message too large")); return; }
            String payload = text.text();
            requests.execute(() -> handle(context.channel(), payload));
        }

        @Override public void channelInactive(ChannelHandlerContext context) {
            RpcSession session = context.channel().attr(SESSION).get();
            if (session != null) router.disconnected(session.connectionId());
        }
    }

    private void handle(Channel channel, String payload) {
        String id = "null";
        JsonObject response;
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            if (json.has("id")) id = json.get("id").getAsString();
            RpcRequest request = RpcRequest.parse(json, limits);
            response = RpcResponse.success(request.id(), router.dispatch(channel.attr(SESSION).get(), request));
        } catch (BridgeException failure) {
            response = RpcResponse.failure(id, failure);
        } catch (RuntimeException failure) {
            LOGGER.error("Unhandled bridge request failure", failure);
            response = RpcResponse.internalFailure(id);
        }
        String serialized = gson.toJson(response);
        channel.eventLoop().execute(() -> channel.writeAndFlush(new TextWebSocketFrame(serialized)));
    }

    @Override public void close() {
        if (server != null) server.close().syncUninterruptibly();
        requests.shutdownNow(); workers.shutdownGracefully().syncUninterruptibly(); boss.shutdownGracefully().syncUninterruptibly();
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        return runnable -> { Thread thread = new Thread(runnable, prefix + "-" + UUID.randomUUID()); thread.setDaemon(true); return thread; };
    }
}
