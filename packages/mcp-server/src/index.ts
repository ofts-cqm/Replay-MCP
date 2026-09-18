export { buildServer, type BuiltReplayMcpServer } from "./server.js";
export { resolveOptions, persistGameDirs, type CliOptions } from "./config.js";
export { BridgeClient, BridgeRpcError } from "./bridge/client.js";
export { DiscoveryManager, SidecarError } from "./bridge/discovery.js";
export { BRIDGE_PROTOCOL, SIDECAR_VERSION } from "./types.js";
