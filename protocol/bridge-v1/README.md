# Replay MCP bridge v1 contract

This directory is the language-neutral contract between the Fabric bridge and
the Node sidecar. The wire protocol name is `replay-mcp.bridge/1`.

The bridge uses JSON-RPC 2.0-style text messages over an authenticated loopback
WebSocket. Requests always have string IDs. Responses contain exactly one of
`result` or `error`. Notifications have a method and params but no ID. Binary
files are represented by an `artifact` descriptor and are never put directly
on the JSON channel.

Schemas use JSON Schema draft 7 so both Java fixture tests and the TypeScript
Ajv suite can validate them. `fixtures/valid.json` and `fixtures/invalid.json`
are shared golden examples. Changes that are not backward-compatible require a
new versioned directory.
