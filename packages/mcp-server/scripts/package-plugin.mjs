import { build } from "esbuild";
import { cp, mkdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(packageRoot, "../..");
const templateRoot = resolve(repositoryRoot, "plugins", "replay-director-template");
const pluginRoot = resolve(repositoryRoot, "plugins", "replay-director");
await rm(pluginRoot, { recursive: true, force: true });
await cp(templateRoot, pluginRoot, { recursive: true });
const binDir = resolve(pluginRoot, "bin");
await mkdir(binDir, { recursive: true });
await build({
  entryPoints: [resolve(packageRoot, "src", "cli.ts")],
  outfile: resolve(binDir, "replay-mcp-server.mjs"),
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node20",
  sourcemap: false,
  banner: { js: "import { createRequire as __replayMcpCreateRequire } from 'node:module'; const require = __replayMcpCreateRequire(import.meta.url);" },
});
const protocolDestination = resolve(pluginRoot, "protocol", "bridge-v1");
await mkdir(resolve(pluginRoot, "protocol"), { recursive: true });
await cp(resolve(repositoryRoot, "protocol", "bridge-v1"), protocolDestination, { recursive: true });
