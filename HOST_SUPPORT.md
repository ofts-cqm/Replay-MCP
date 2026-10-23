# Replay Director host support — plugin 0.2.0

Acceptance date: 2026-10-22. Implementation is available for local testing.
Full host-session and live-production certification remains open. Do not treat
an SDK connection, validator, or installed plugin as end-to-end support.

| Layer | Codex 0.163.0 | Claude Code 2.1.309 | Generic SDK client 2.0.0 |
| --- | --- | --- | --- |
| Generated package/source parity | Passed; existing portable/Codex entry points retained | Passed; separate Claude adapter, shared bundle/skills/protocol | Same pinned bundle |
| Host manifest validation | Manifest equivalence/schema assertions passed; no fresh host installation | `claude plugin validate --strict` passed for plugin and marketplace | Not applicable |
| Installation | Existing installation unchanged; 0.2.0 host reinstall unverified | Local marketplace add/install/list passed in a temporary profile; version 0.2.0 | Absolute-path configuration documented |
| Host MCP connection | New 0.2.0 host session unverified | `claude mcp list` reports Connected using the installed plugin | Passed, 48 tools and isolated offline status |
| Skills and linked references | Shared files verified; fresh host discovery unverified | Five skills packaged/verified; model-session discovery and manual local `--plugin-dir` session unverified | Manual instruction-loading path documented |
| Tool calls, schema, JSON, images, resources | SDK regression coverage passed; fresh host image inspection unverified | SDK regression coverage passed; authenticated host tool/image inspection unverified | Passed against isolated fake bridge; no claim about a model's interpretation |
| Cancellation/shutdown/reconnect/review | Shared SDK coverage passed; host-session acceptance unverified | Shared SDK coverage passed; host-session acceptance unverified | Passed for default, legacy and auto negotiation |
| Live capture/import, camera, render and completion | Unverified for this release | Unverified | Fake-bridge only |

Claude authentication reports `loggedIn: false`; no authenticated Claude model
session was run. Its validator reports a non-blocking catalog icon advisory;
both strict manifest validations pass. The sandbox initially closed stdio pipes;
the permitted rerun passed using the same isolated discovery settings. No checks
contacted a live Minecraft client or changed the user's installed Codex plugin.
The Claude marketplace was installed only into a temporary profile under /tmp.

## Version and protocol record

| Component | Tested value |
| --- | --- |
| Plugin manifests (portable, Codex, Claude) | 0.2.0 |
| Sidecar | 0.1.0 |
| MCP server/client SDK | 2.0.0 / 2.0.0 (lockfile dependencies) |
| Node full test runner | 26.12.0 |
| Minimum Node runtime | 20.0.0, official Linux x64 archive checked against published SHA-256 |
| Default SDK negotiation | 2025-11-25 |
| Explicit legacy negotiation | 2025-11-25 |
| Auto negotiation | 2025-11-25 after legacy fallback |
| Bridge | replay-mcp.bridge/1 |
| Live mod | Absent; offline checks and fake bridge only |
| Repository target (not live acceptance) | Fabric mod 1.0; Minecraft 26.2; Replay Mod 26.2-2.6.27 |

The installed SDK exposes the legacy `StdioServerTransport` used by the CLI and
modern `serveStdio`. Audit and tests showed no gap for the supported negotiation
paths, so transport setup is unchanged. A modern pinned revision is not claimed.

## Automated evidence

- `npm run typecheck` and `npm run build` pass.
- `npm test`: 21 files, 71 tests passed on Node 26.12.0.
- Minimum-runtime packaging/lifecycle matrix: 2 files, 13 tests passed with
  Node 20.0.0 sidecars (test runner on Node 26.12.0).
- Both outputs match template-owned files, host adapters, protocol source and
  identical bundled sidecar bytes. Installed Claude content/version is compared
  with the generated distribution after installation.
- All 48 tools have object input schemas without root combinators; each schema
  compiles under JSON Schema 2020-12. Valid ordinary/surface/volume queries work;
  incomplete or mixed variants fail before bridge execution.
- JSON text equals the structured payload for representative success/error,
  review cursor and export binding responses. A 67,999-character project title
  survives response serialization and a process restart without truncation.
- Verified inline images match resource-read bytes. Existing checksum and path
  confinement tests remain in the suite.
- Default/legacy/auto stdio tests prove cancellation reaches the bridge, cancelled
  mutations relinquish the lease, EOF releases ownership, a second session is
  fenced, and a restarted process reads persisted projects and authoritative
  contract/export decisions using saved cursors without reopening review.
- Offline checks use absent game directories and temporary state. Fake bridges
  bind only temporary localhost ports. Install paths and unrelated working
  directories include spaces.

## Reproducing and completing acceptance

```sh
npm run package:plugin
npm run typecheck
npm test
node packages/mcp-server/scripts/smoke-plugin.mjs
node packages/mcp-server/scripts/smoke-plugin.mjs ./plugins/replay-director-claude
claude plugin validate --strict ./plugins/replay-director-claude
claude plugin validate --strict ./.claude-plugin/marketplace.json
```

Run the same smoke with Node 20.0.0 first on PATH to exercise the advertised
minimum sidecar runtime. A modern Node can run the test runner while spawning
the minimum runtime with `REPLAY_MCP_TEST_NODE=/absolute/path/to/node20` for
`test/packaging.test.ts` and `test/compatibility-lifecycle.test.ts`.

Before a full host support claim: install 0.2.0 in a fresh Codex session; complete
Claude local-load and marketplace sessions with authentication; verify skill
loading, tool calls, inline image inspection and durable review continuation in
both. Then run capture/import, camera authoring, render, verified artifact
inspection and completion checks with each host against the intended mod.
Record the host/runtime/mod/protocol versions and evidence here. SDK fake-bridge
success must never fill these remaining host/production cells.

Claude Code is the only Claude product targeted. Other local MCP clients are
configurable, with their host limits and skill loading unverified. Remote-only
clients, hosted HTTP transport, editors and music services are outside this
support claim. See [InstallationGuide.md](InstallationGuide.md) and the packaged
`LOCAL_TESTING.md` for installation and capability fallbacks.

External behavior was checked against the official
[Claude manifest reference](https://code.claude.com/docs/en/plugins-reference),
[marketplace guide](https://code.claude.com/docs/en/plugin-marketplaces),
[Claude MCP reference](https://code.claude.com/docs/en/mcp), and
[MCP tool result contract](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content).
