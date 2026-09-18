package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void helloMustBeFirstAndAuthenticationIsConstantContract() {
        byte[] token = "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);
        RpcSession session = new RpcSession("client", token);
        assertFalse(session.authenticate(new byte[32]));
        assertTrue(session.authenticate(token));
        BridgeException failure = assertThrows(BridgeException.class, () -> session.requireMethod("system.status"));
        assertEquals(BridgeError.PROTOCOL_MISMATCH, failure.error());
        session.requireMethod("system.hello");
        session.completeHello();
        session.requireMethod("system.status");
    }

    @Test void parsesJsonRpcAndCapsDeadline() {
        JsonObject json = new JsonObject(); json.addProperty("jsonrpc", "2.0"); json.addProperty("id", "1"); json.addProperty("method", "system.status");
        JsonObject params = new JsonObject(); params.addProperty("deadline_ms", 49); json.add("params", params);
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class, () -> RpcRequest.parse(json, ProtocolLimits.defaults())).error());
        params.addProperty("deadline_ms", 50);
        assertEquals(50, RpcRequest.parse(json, ProtocolLimits.defaults()).deadline().toMillis());
    }

    @Test void idempotencyRejectsRequestIdReuseWithDifferentBody() {
        IdempotencyStore store = new IdempotencyStore(4); JsonObject one = new JsonObject(); one.addProperty("x", 1); JsonObject result = new JsonObject(); result.addProperty("ok", true);
        store.remember("r", one, result);
        assertTrue(store.lookup("r", one).isPresent());
        JsonObject two = new JsonObject(); two.addProperty("x", 2);
        assertEquals(BridgeError.CONFLICT, assertThrows(BridgeException.class, () -> store.lookup("r", two)).error());
    }
}
