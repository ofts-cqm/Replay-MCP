import { access, readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { McpServer, ResourceTemplate } from "@modelcontextprotocol/server";
import { ReplayMcpRuntime } from "../runtime.js";

export function registerResources(server: McpServer, runtime: ReplayMcpRuntime): void {
  server.registerResource("bridge-schema", new ResourceTemplate("replay-mcp://schema/{name}", { list: undefined }), {
    title: "Replay MCP versioned schema", description: "JSON Schema from the bridge-v1 language-neutral contract", mimeType: "application/schema+json",
  }, async (uri, variables) => {
    const name = safeName(String(variables.name));
    const path = join(await schemaRoot(), name.endsWith(".json") ? name : `${name}.schema.json`);
    return { contents: [{ uri: uri.href, mimeType: "application/schema+json", text: await readFile(path, "utf8") }] };
  });

  server.registerResource("artifact", new ResourceTemplate("replay-mcp://artifact/{id}", { list: undefined }), {
    title: "Verified Replay MCP artifact", description: "Checksum-verified image, video, replay, handoff, or log", mimeType: "application/octet-stream",
  }, async (_uri, variables) => {
    const artifact = runtime.artifacts.get(String(variables.id));
    if (!artifact) throw new Error(`artifact ${String(variables.id)} was not found`);
    return { contents: [await runtime.artifacts.resource(artifact)] };
  });

  server.registerResource("project-manifest", new ResourceTemplate("replay-mcp://project/{id}/manifest", { list: undefined }), {
    title: "Replay MCP project manifest", description: "Current revision of an editor-neutral production project", mimeType: "application/json",
  }, async (uri, variables) => {
    const project = runtime.projects.get(String(variables.id));
    return { contents: [{ uri: uri.href, mimeType: "application/json", text: `${JSON.stringify(project, null, 2)}\n` }] };
  });

  server.registerResource("replay-metadata", new ResourceTemplate("replay-mcp://replay/{id}/metadata", { list: undefined }), {
    title: "Replay metadata", description: "Live Replay Mod metadata and compatibility report", mimeType: "application/json",
  }, async (uri, variables) => {
    const client = runtime.client();
    const metadata = await client.call("replay.metadata", { path: String(variables.id) });
    return { contents: [{ uri: uri.href, mimeType: "application/json", text: `${JSON.stringify(metadata, null, 2)}\n` }] };
  });

  server.registerResource("job-log", new ResourceTemplate("replay-mcp://job/{id}/log", { list: undefined }), {
    title: "Replay MCP job log", description: "Persistent full log for a render, preview, analysis, still, or export job", mimeType: "text/plain",
  }, async (uri, variables) => {
    const job = runtime.jobs.get(String(variables.id));
    if (!job) throw new Error(`job ${String(variables.id)} was not found`);
    return { contents: [{ uri: uri.href, mimeType: "text/plain", text: `${job.logs.join("\n")}\n` }] };
  });

  server.registerResource("session-audit", new ResourceTemplate("replay-mcp://audit/{session_id}", { list: undefined }), {
    title: "Replay MCP session audit", description: "Read-only sidecar action and command audit trace", mimeType: "application/x-ndjson",
  }, async (uri, variables) => {
    if (String(variables.session_id) !== runtime.sessionId) throw new Error("audit session was not found");
    const text = await readFile(runtime.audit.path(), "utf8").catch((error: NodeJS.ErrnoException) => error.code === "ENOENT" ? "" : Promise.reject(error));
    return { contents: [{ uri: uri.href, mimeType: "application/x-ndjson", text }] };
  });
}

function safeName(value: string): string {
  if (!/^[a-z0-9_.-]+$/i.test(value) || value.includes("..")) throw new Error("invalid schema name");
  return value;
}

async function schemaRoot(): Promise<string> {
  const here = dirname(fileURLToPath(import.meta.url));
  const candidates = [
    resolve(here, "../protocol/bridge-v1/schemas"),
    resolve(here, "../../protocol/bridge-v1/schemas"),
    resolve(here, "../../../../protocol/bridge-v1/schemas"),
  ];
  for (const candidate of candidates) {
    try { await access(candidate); return candidate; } catch { /* try source checkout layout */ }
  }
  throw new Error("bundled bridge schemas are unavailable");
}
