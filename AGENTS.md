# Repository instructions

## Replay Director plugin source and generated output

- `plugins/replay-director-template/` is the authoritative source for Replay
  Director skills, references, plugin manifests, and other packaged static files.
- `plugins/replay-director/` is a disposable build directory, not a source
  directory. Never make an authored change only there or in an installed plugin
  cache. Make changes in the template, then run `npm run package:plugin`.
- The packaged sidecar at `plugins/replay-director/bin/replay-mcp-server.mjs`
  is compiled from `packages/mcp-server/src/`. Fix sidecar code there; do not
  copy the compiled bundle into the template.
- The packaged `plugins/replay-director/protocol/bridge-v1/` is copied from
  the repository's `protocol/bridge-v1/`. Edit the repository source, not its
  generated copy.
- Before regenerating an existing build, compare it with these sources. If it
  contains authored changes absent from source, preserve and reconcile them
  into the appropriate source first. Do not discard changes by rebuilding or
  blindly overwrite newer template content with an older generated copy.
- Keep both template manifests (`.codex-plugin/plugin.json` and `plugin.json`)
  consistent, including their version. Apply cachebuster/version updates to
  the template before packaging; a build-only version update will be lost.
- After packaging, verify that template-owned files match the generated files
  and the packaged protocol matches its source. When reinstalling, also verify
  the installed plugin matches the generated version and intended content.
- These rules apply to all worker agents. Include the source/build distinction
  in delegated plugin-editing tasks.
