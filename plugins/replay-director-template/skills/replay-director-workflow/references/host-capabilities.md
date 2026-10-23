# Host capabilities and recovery

Replay Director uses one local MCP server and shared instructions. A connected
server alone does not establish skill loading, visual inspection, or production
acceptance. Check these capabilities independently:

| Capability | Required behavior or fallback |
| --- | --- |
| Local stdio | Node 20+ can execute the pinned bundle and access the configured Minecraft directory. Remote-only clients are outside this release. |
| Tool schemas and results | Read the summary and the complete JSON text block or equivalent structuredContent. All operational fields are preserved in both representations. |
| Images | Inspect the verified inline image blocks from observation and previews. If the host cannot display them, use a local image viewer on the verified artifact path. Never infer visual acceptance from metadata. |
| Artifacts | Read verified media through resources/read or the returned local paths. A resource browser UI is optional. Preserve checksums and path confinement. |
| Skills | Load the selected SKILL.md and its linked references manually if the host has no skill loader. agents/openai.yaml is optional Codex presentation metadata. |
| Delegation | Use phase-specific workers only when allowed and when the host can stop/release them. Otherwise execute sequentially with the same request record and durable handoffs. |
| Waits and cancellation | Keep review waits at or below 50 seconds and below the host's call deadline. Cancelling a directing mutation cancels bridge work and releases its lease; explicitly reacquire only when authorized to resume. |

There is at most one controller per Minecraft instance. Terminal human takeover
stays terminal until explicit recovery; host reconnects never confer control.
Release unused control while waiting for a physical player decision.

During an active turn, continue timed-out review waits with production_status
and the exact draft_hash/after_submission_sequence or
export_binding/after_decision_sequence cursor. A timeout, disconnect, Later, or
Escape is not approval. Never reopen a draft just to poll. In a later session,
read the request record and durable project/progress state, recover the saved
cursor/binding and query authoritative status before taking action. Changed
hashes supersede old decisions. An ended turn has no promised automatic wakeup;
a new user message or separately configured host event integration is needed.

## Large responses

The server sends complete compact JSON without silent truncation. Lists are
currently unpaginated: narrow artifact_list/job_list filters and game_query
bounds/limits when possible. The compatibility test round-trips a project with
67,999 characters of notes and its complete JSON through stdio, including after
restart. This establishes transport integrity, not a host context-size promise.

Claude Code can save large successful text results to a local tool-results file;
read that file completely or in bounded portions before acting on it. Current
Claude documentation describes a 50,000-character persistence threshold and a
25,000-token default, and separate truncation of long error text. If an error
is clipped, do not use its fragment as complete operational data: use a client
that exposes structuredContent or capture the complete MCP response. Do not
blindly retry a mutation to recover output; first reconcile its request/job ID
and persisted state. Clients without full result access cannot claim recovery
support. Raising a host token budget alone does not remove every host limit.

Sources: [Claude output and image handling](https://code.claude.com/docs/en/mcp#MCP-output-limits-and-warnings),
[MCP structured content](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content).
