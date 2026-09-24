import { mkdtemp, readFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/client";
import { InMemoryTransport } from "@modelcontextprotocol/server";
import { afterEach, describe, expect, it } from "vitest";
import { buildServer, type BuiltReplayMcpServer } from "../src/server.js";
import { createFakeBridge, type FakeBridge } from "./fake-bridge.js";

describe("spatial-map public contract", () => {
  let built: BuiltReplayMcpServer | undefined;
  let fake: FakeBridge | undefined;
  let client: Client | undefined;

  afterEach(async () => {
    await client?.close().catch(() => undefined);
    await built?.close().catch(() => undefined);
    await fake?.close().catch(() => undefined);
  });

  it("routes ordinary maps lease-free and gates level-2 refinement to a containing size-4 map", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-spatial-"));
    fake = await createFakeBridge(join(root, "game"));
    ({ built, client } = await connect(root, fake));

    const coarse = structured(await call(client, {
      kind: "spatial_map", representation: "surface",
      bounds: { min_x: -5, max_x: 11, min_y: 48, max_y: 96, min_z: -5, max_z: 11 },
      cell_size: 4, material_mix_limit: 2,
    }));
    const coarseMap = coarse.result as Record<string, unknown>;
    expect(coarseMap).toMatchObject({ representation: "surface", cell_size: 4, effective_bounds: { min_x: -8, max_x: 12, min_z: -8, max_z: 12 } });
    expect(fake.calls.some((entry) => entry.method === "lease.acquire")).toBe(false);

    const fallback = structured(await call(client, {
      kind: "spatial_map", representation: "surface",
      bounds: { min_x: -3, max_x: 5, min_y: 48, max_y: 96, min_z: -3, max_z: 5 },
      cell_size: 2, refines_map_id: coarseMap.map_id, fallback_reason: "unresolved_surface_boundary",
    }));
    expect(fallback.result).toMatchObject({ representation: "surface", cell_size: 2, refines_map_id: coarseMap.map_id });
    expect(fake.calls.filter((entry) => entry.method === "observation.spatial_map")).toHaveLength(2);
    expect(fake.calls.some((entry) => entry.method === "system.status")).toBe(true);

    const audit = await readFile(join(root, "data", "audit", "sidecar.jsonl"), "utf8");
    expect(audit).toContain("spatial_map.fallback_requested");
    expect(audit).toContain("unresolved_surface_boundary");
  });

  it("rejects invalid fallbacks, oversized requests, and older mods before bridge sampling", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-spatial-reject-"));
    fake = await createFakeBridge(join(root, "game"));
    ({ built, client } = await connect(root, fake));

    const missingParent = await client.callTool({ name: "game_query", arguments: {
      kind: "spatial_map", representation: "volume",
      bounds: { min_x: 0, max_x: 8, min_y: 64, max_y: 72, min_z: 0, max_z: 8 },
      cell_size: 2, refines_map_id: "unknown", fallback_reason: "tight_clearance",
    } });
    expect(errorCode(missingParent)).toBe("conflict");

    const oversized = await client.callTool({ name: "game_query", arguments: {
      kind: "spatial_map", representation: "surface",
      bounds: { min_x: 0, max_x: 4096, min_y: -64, max_y: 320, min_z: 0, max_z: 4096 }, cell_size: 4,
    } });
    expect(errorCode(oversized)).toBe("query_too_large");
    expect(fake.calls.filter((entry) => entry.method === "observation.spatial_map")).toHaveLength(0);

    await client.close(); await built.close(); await fake.close();
    client = undefined; built = undefined; fake = undefined;
    const oldRoot = await mkdtemp(join(tmpdir(), "replay-mcp-spatial-old-"));
    fake = await createFakeBridge(join(oldRoot, "game"), { spatialMap: false });
    ({ built, client } = await connect(oldRoot, fake));
    const unavailable = await client.callTool({ name: "game_query", arguments: {
      kind: "spatial_map", representation: "surface",
      bounds: { min_x: 0, max_x: 16, min_y: 48, max_y: 96, min_z: 0, max_z: 16 }, cell_size: 8,
    } });
    expect(errorCode(unavailable)).toBe("capability_unavailable");
    expect(fake.calls.some((entry) => entry.method === "observation.spatial_map")).toBe(false);
  });

  it("enforces representation-specific level-2 reasons in the public schema", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-spatial-schema-"));
    fake = await createFakeBridge(join(root, "game"));
    ({ built, client } = await connect(root, fake));
    const wrongReason = await client.callTool({ name: "game_query", arguments: {
      kind: "spatial_map", representation: "surface",
      bounds: { min_x: 0, max_x: 8, min_y: 48, max_y: 96, min_z: 0, max_z: 8 },
      cell_size: 2, refines_map_id: "parent", fallback_reason: "tight_clearance",
    } });
    expect(wrongReason.isError).toBe(true);
    expect(fake.calls.some((entry) => entry.method === "observation.spatial_map")).toBe(false);
  });
});

async function connect(root: string, fake: FakeBridge): Promise<{ built: BuiltReplayMcpServer; client: Client }> {
  const built = await buildServer({ dataDir: join(root, "data"), gameDirs: [fake.gameDir], guessedGameDirs: [], discoveryIntervalMs: 60_000, command: "serve" });
  const pair = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "spatial-test", version: "1.0.0" });
  await built.server.connect(pair[1]); await client.connect(pair[0]);
  return { built, client };
}

async function call(client: Client, args: Record<string, unknown>) {
  const result = await client.callTool({ name: "game_query", arguments: args });
  expect(result.isError, JSON.stringify(result.structuredContent)).not.toBe(true); return result;
}
function structured(result: { structuredContent?: unknown }): Record<string, unknown> {
  if (!result.structuredContent || typeof result.structuredContent !== "object" || Array.isArray(result.structuredContent)) throw new Error("missing structured content");
  return result.structuredContent as Record<string, unknown>;
}
function errorCode(result: { structuredContent?: unknown }): unknown {
  const value = structured(result); return ((value.error as Record<string, unknown>) ?? {}).code;
}
