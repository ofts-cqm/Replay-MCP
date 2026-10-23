# Replay Director agent compatibility specification

Status: implementation authorized by the user on 2026-10-22; code and automated
compatibility coverage implemented. See HOST_SUPPORT.md for acceptance evidence
and unverified host-session/production layers.

Investigation date: 2026-10-22.

## Objective

Support Codex and Claude Code as explicitly tested Replay Director plugin hosts,
and provide a documented connection path for other local MCP clients. Keep one
shared MCP server, bridge contract, and filmmaking skill set. Isolate host-specific
installation and presentation metadata in packaging adapters.

Connecting an MCP client, loading filmmaking skills, and completing a production
are separate support claims. Each requires its own evidence.

## Scope

The initial update covers:

- Codex plugin regression compatibility.
- Claude Code plugin packaging, installation, skill discovery, and workflow checks.
- A generic local stdio MCP configuration using an absolute executable path.
- Portable tool schemas, result content, image delivery, and workflow guidance.
- Automated compatibility checks and a versioned host support matrix.

Remote-only clients, hosted HTTP transport, Claude web connectors, new editor
integrations, and changes to Minecraft gameplay or camera behavior are outside
this update. Claude Code support does not establish support for other Claude
products. Additional agents require separate validation.

## Investigated baseline

| Area | Observed implementation | Compatibility consequence |
| --- | --- | --- |
| MCP server | Node.js stdio server using the MCP SDK; no Codex/OpenAI runtime invocation found in current server source | Shared server can remain independent of the host |
| Manifests | Root `plugin.json` and `mcp.json`, plus `.codex-plugin/plugin.json` and `.mcp.json` | Existing portable/Codex packaging must remain supported |
| Marketplace | `.agents/plugins/marketplace.json` | No explicit Claude marketplace integration exists |
| Launch | Relative bundle argument and `cwd: "."` | Existing packaging assumes the host resolves the working directory from the plugin root |
| Responses | Result helpers return a short text summary and a separate `structuredContent` payload | Text-only consumers can miss operational data |
| Query schema | `game_query` emits root-level `anyOf` | Some hosts require schema rewriting to expose this tool |
| Skills | Shared Markdown with some Codex wording and three `agents/openai.yaml` files | Wording should be neutral; presentation metadata can stay host-specific |
| Orchestration | Conditional subagents, durable handoffs, bounded review waits | Host capability differences need an explicit sequential fallback |
| Versions | Plugin versions contain `+codex.<timestamp>`; a test requires that pattern | Version policy unnecessarily encodes one host |

Relevant source locations:

- `packages/mcp-server/src/cli.ts` and `src/server.ts`: transport and server setup.
- `packages/mcp-server/src/tools/register.ts`: schemas and result helpers.
- `packages/mcp-server/src/config.ts`: workspace, game-directory, and state discovery.
- `packages/mcp-server/src/artifacts/store.ts`: verified images and resources.
- `packages/mcp-server/src/production/review-wait.ts`: review continuation.
- `packages/mcp-server/scripts/package-plugin.mjs`: distribution generation.
- `packages/mcp-server/scripts/smoke-plugin.mjs` and `test/packaging.test.ts`:
  existing startup verification.
- `plugins/replay-director-template/`: authoritative packaged static content.

### Evidence and limits

The investigation found no differences between template-owned files and their
existing generated counterparts. The isolated offline plugin smoke passed with
48 tools and offline status. SDK probes using legacy and default client settings
also succeeded from an unrelated working directory with an absolute bundle path.
The first sandboxed smoke failed with a closed connection; the permitted rerun
passed.

These checks used temporary state and an empty game directory. They do not prove
Minecraft capture, camera editing, rendering, visual acceptance, or host-specific
plugin discovery. Success with default SDK settings does not establish which
protocol revision was negotiated. Claude Code was not installed in the inspected
environment, so no Claude loading or production acceptance was performed.

## Required design

### 1. Shared implementation and host adapters

Keep `plugins/replay-director-template/` authoritative for shared skills,
references, and authored manifests. Keep sidecar implementation in
`packages/mcp-server/src/` and the bridge contract in `protocol/bridge-v1/`.

Preserve the existing `plugins/replay-director/` Codex/portable output and default
`npm run package:plugin` behavior. Add a separate generated Claude distribution,
proposed as `plugins/replay-director-claude/`, from the same sources and bundled
server. Ignore the new generated directory in Git.

The Claude adapter must provide:

- `.claude-plugin/plugin.json` with explicit name, version, description, author,
  and shared skill discovery.
- A Claude MCP configuration that resolves the bundled server using
  `${CLAUDE_PLUGIN_ROOT}` and does not require the current directory to be the
  installed plugin directory.
- Local plugin loading instructions and a Claude marketplace entry for the
  supported marketplace installation route.

Keep Claude adapter sources under the authoritative template and have packaging
select the appropriate host files. Each generated host distribution must have
one effective launch definition for `replay-mcp`; avoid competing auto-discovered
and manifest-declared definitions.

Use one shared plugin version for both variants. Adopt a host-neutral valid
semantic version policy and replace the test that requires a Codex suffix.
Preserve the distinction between plugin, sidecar, bridge, and Fabric mod versions.

Before regenerating, compare existing generated content with its authoritative
sources and reconcile authored differences. After packaging, verify shared
template files, host adapter content, protocol copies, and intended versions.
Never repair generated or installed files as the sole authored change.

### 2. Portable tool responses

Update the shared success and error helpers so that JSON operational data is
available in a text content block as well as `structuredContent`. Preserve the
short human-readable summary and existing image/resource blocks.

The JSON text must represent the same payload, including project/job identifiers,
error details, review revision hashes, and continuation cursors. Do not silently
truncate operational fields. Verify representative large responses against host
output limits; if limits require pagination or retrieval, specify that behavior
explicitly rather than returning an incomplete payload that appears complete.

Preserve all existing artifact checksums, path confinement, error codes, and
lease requirements. Prefer shared protocol behavior over host-name detection.

### 3. Portable input schemas

Expose `game_query` with an object schema that has no root-level `anyOf`, `oneOf`,
or `allOf`, while preserving existing argument names and query kinds. Keep
variant-specific validation on the server using the existing strict schemas.

The published schema and descriptions must explain required spatial-map fields
and valid parameter combinations. Reject invalid combinations before bridge
execution. Inspect every registered tool schema for similar portability issues.
Do not depend on a client rewriting schemas correctly.

### 4. Launch, discovery, and protocol behavior

Separate the installed bundle path from the user's workspace and Minecraft game
directory. Launch must work from an unrelated directory and paths containing
spaces. Explicit configuration through `--game-dir`, `REPLAY_MCP_GAME_DIR`, and
`REPLAY_MCP_WORKSPACE`, plus persisted configuration, must remain predictable.
Keep persistent state outside generated and installed plugin directories.

Document an absolute-path stdio configuration for generic clients. Keep the
bundled server pinned; do not introduce unpinned runtime downloads.

Record actual negotiated protocol revisions in compatibility tests. Audit the
installed SDK's supported stdio entry points and the current CLI lifecycle before
changing transport setup. Change transport only if testing demonstrates a gap.
Verify shutdown, cancellation, lease release, and reconnect behavior for every
supported negotiation path.

### 5. Shared skills and host capabilities

Use host-neutral wording for shared filmmaking instructions. Retain
`agents/openai.yaml` as optional Codex metadata; shared behavior must not depend
on another host reading it.

Document these capabilities separately:

- Local stdio execution and access to the configured Minecraft instance.
- Tool schemas and full operational result data.
- Inline image inspection and access to verified media artifacts.
- Skill loading or an explicit manual instruction-loading path.
- Optional subagent creation and lifecycle management.
- Bounded tool waits, cancellation, and durable continuation after reconnect.

Use phase-specific delegation when the host supports the required lifecycle.
When the host cannot provide that lifecycle, continue sequentially using the
same durable checkpoints, subject to governing instructions. Preserve one
controller per Minecraft instance and terminal human takeover semantics.

Keep review cursors and export bindings authoritative across timeouts and
reconnects. A timeout is never approval. Guidance must distinguish continuing an
active turn from recovering in a later session and must not promise automatic
wakeup after a turn ends.

## Verification and acceptance

| Layer | Required evidence |
| --- | --- |
| Packaging | Host manifests validate; shared content and protocol match source; versions agree; each host has one effective server definition |
| Offline MCP | Launch from an unrelated directory and paths with spaces; all current tools list; status is offline with isolated discovery |
| Schemas | Every tool has a usable object input schema; ordinary and spatial queries validate correctly; invalid combinations fail before bridge calls |
| Results | Text JSON and structured payload agree for success and error cases, including recovery cursors |
| Media | Verified image blocks reach the client; resource reads work; supported workflows do not require a resource UI the host lacks |
| Lifecycle | Cancellation and shutdown release control; concurrent sessions cannot both control one instance; reconnect preserves recoverable state |
| Review | Timeout and reconnect resume from the correct cursor without reopening review or inventing a decision |
| Codex host | Existing installation, skill discovery, tool calls, and image inspection continue working |
| Claude Code host | Validator, local load, marketplace install, skill discovery, tool calls, image inspection, and review continuation pass |
| Production | Per-host live capture/import, camera, render, artifact inspection, and completion checks pass before claiming end-to-end support |

Extend existing packaging and fake-bridge tests rather than duplicating their
implementation. Run offline checks with temporary state and an absent game
directory so they cannot discover a live client. Record host, SDK, Node, plugin,
sidecar, mod, and negotiated protocol versions with acceptance results.

Claude's validator and an actual Claude Code session are required for a Claude
plugin support claim. A generic SDK client smoke cannot substitute for them.
Keep Node's advertised minimum version unless validation demonstrates a reason
to change it; include that minimum in the release test matrix.

## Implementation sequence

1. Harden result content and input schemas; add focused compatibility tests.
2. Add host adapter sources and deterministic Claude distribution generation;
   retain the existing Codex packaging path.
3. Update shared skills, version policy, and capability/fallback guidance.
4. Run packaging, isolated MCP, fake-bridge, and protocol/lifecycle checks.
5. Validate actual Codex and Claude Code loading and representative workflows.
6. Update `InstallationGuide.md`, plugin-local testing instructions,
   `ARCHITECTURE.md`, and the support matrix using the resulting evidence.

## Release criteria and reporting

The first milestone is Codex regression coverage, verified Claude Code plugin
support, and a documented generic local MCP setup. Publish support status by
layer: package validation, MCP connection, skill loading, and live production.
Mark untested layers as unverified and include the tested host versions.

Other clients may be described as configurable through standard MCP without
claiming their plugin or production workflows are tested. Optional editors and
music services remain independently configured dependencies.

## External references

- [Claude plugin manifest and environment-variable reference](https://code.claude.com/docs/en/plugins-reference)
- [Claude MCP schemas, images, and client behavior](https://code.claude.com/docs/en/mcp)
- [MCP structured tool content and backward compatibility](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content)

External behavior must be rechecked against the host versions selected for
implementation and release validation.
