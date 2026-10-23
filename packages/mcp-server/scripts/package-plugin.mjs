import { build } from "esbuild";
import { cp, mkdir, rm, readFile, writeFile, readdir } from "node:fs/promises";
import { dirname, resolve, relative, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(packageRoot, "../..");
const templateRoot = resolve(repositoryRoot, "plugins/replay-director-template");
const receiptName = ".package-files.json";
const hosts = ["codex", "claude"];
const destinations = hosts.map(host => resolve(repositoryRoot, `plugins/replay-director${host === "claude" ? "-claude" : ""}`));

async function files(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true }).catch(error => {
    if (error.code === "ENOENT") return [];
    throw error;
  })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...await files(path));
    else result.push(path);
  }
  return result;
}
const hash = bytes => createHash("sha256").update(bytes).digest("hex");

// Refuse to discard generated-only edits. Receipts allow source changes while
// still identifying manual edits to the previous build. Legacy builds must
// match today's sources before the first rebuild with this packager.
for (const destination of destinations) {
  const receipt = JSON.parse(await readFile(join(destination, receiptName), "utf8").catch(error => {
    if (error.code === "ENOENT") return "null";
    throw error;
  }));
  const existingFiles = await files(destination);
  if (receipt) {
    const present = new Set(existingFiles.map(path => relative(destination, path)));
    for (const name of Object.keys(receipt)) {
      if (!present.has(name)) throw new Error(`Generated file was removed: ${join(destination, name)}. Reconcile the removal into source before rebuilding, or remove the disposable build directory after review.`);
    }
  }
  for (const path of existingFiles) {
    const name = relative(destination, path);
    if (name === receiptName) continue;
    const bytes = await readFile(path);
    if (receipt ? receipt[name] === hash(bytes) : await legacyMatches(name, bytes)) continue;
    throw new Error(`Generated file differs from its source/previous build: ${path}. Reconcile authored changes into the template, server or protocol source before rebuilding.`);
  }
}
async function legacyMatches(name, bytes) {
  // Bundles are compiled output, not authored static files.
  if (name === "bin/replay-mcp-server.mjs") return true;
  const source = name.startsWith("protocol/") ? join(repositoryRoot, name) : join(templateRoot, name);
  try { return bytes.equals(await readFile(source)); } catch { return false; }
}

const portable = JSON.parse(await readFile(join(templateRoot, "plugin.json"), "utf8"));
const claude = JSON.parse(await readFile(join(templateRoot, "adapters/claude/.claude-plugin/plugin.json"), "utf8"));
const codex = JSON.parse(await readFile(join(templateRoot, ".codex-plugin/plugin.json"), "utf8"));
if (portable.version !== codex.version || portable.version !== claude.version) throw new Error("Template plugin versions disagree");
if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(portable.version)) throw new Error("Plugin version must be a host-neutral stable semantic version (major.minor.patch)");

for (const [index, host] of hosts.entries()) {
  const destination = destinations[index];
  await rm(destination, { recursive: true, force: true });
  await mkdir(destination, { recursive: true });
  for (const name of await readdir(templateRoot)) {
    if (name === "adapters") continue;
    if (host === "claude" && ["plugin.json", "mcp.json", ".mcp.json", ".codex-plugin"].includes(name)) continue;
    await cp(join(templateRoot, name), join(destination, name), { recursive: true });
  }
  if (host === "claude") {
    await cp(join(templateRoot, "adapters/claude"), destination, { recursive: true });
    const path = join(destination, ".claude-plugin/plugin.json");
    const manifest = JSON.parse(await readFile(path, "utf8"));
    if (manifest.version !== portable.version) throw new Error("Claude template plugin version disagrees");
  }
  await mkdir(join(destination, "bin"), { recursive: true });
  if (host === "codex") {
    await build({
      entryPoints: [resolve(packageRoot, "src/cli.ts")],
      outfile: join(destination, "bin/replay-mcp-server.mjs"),
      bundle: true, platform: "node", format: "esm", target: "node20", sourcemap: false,
      banner: { js: "import { createRequire as __replayMcpCreateRequire } from 'node:module'; const require = __replayMcpCreateRequire(import.meta.url);" },
    });
  } else {
    await cp(join(destinations[0], "bin/replay-mcp-server.mjs"), join(destination, "bin/replay-mcp-server.mjs"));
  }
  await mkdir(join(destination, "protocol"), { recursive: true });
  await cp(resolve(repositoryRoot, "protocol/bridge-v1"), join(destination, "protocol/bridge-v1"), { recursive: true });
  const receipt = Object.fromEntries(await Promise.all((await files(destination)).map(async path => [relative(destination, path), hash(await readFile(path))])));
  await writeFile(join(destination, receiptName), `${JSON.stringify(receipt, null, 2)}\n`);
}
