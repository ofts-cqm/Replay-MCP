import { createHash } from "node:crypto";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parseClipMarkers, verifyReplaySource } from "../src/capture/importer.js";

const a = "11111111-1111-4111-8111-111111111111";
const b = "22222222-2222-4222-8222-222222222222";
const c = "33333333-3333-4333-8333-333333333333";
const d = "44444444-4444-4444-8444-444444444444";

describe("player clip marker import", () => {
  it("pairs unordered markers by clip id and excludes revoked and incomplete clips", () => {
    const parsed = parseClipMarkers([
      marker(a, "end", 200), marker(b, "start", 300), marker(a, "start", 100),
      marker(b, "revoke", 350), marker(c, "start", 400), { name: "ordinary marker", time_us: 10 },
    ]);
    expect(parsed.accepted).toEqual([{ clip_id: a, replay_in_us: 100, replay_out_us: 200 }]);
    expect(parsed.diagnostics).toEqual(expect.arrayContaining([
      expect.objectContaining({ clip_id: b, status: "revoked" }),
      expect.objectContaining({ clip_id: c, status: "incomplete" }),
    ]));
  });

  it("rejects duplicate, zero/negative, and malformed marker sets", () => {
    const parsed = parseClipMarkers([
      marker(a, "start", 100), marker(a, "start", 101), marker(a, "end", 200),
      marker(b, "start", 300), marker(b, "end", 300),
      marker(c, "start", 500), marker(c, "end", 400),
      { name: `replay_mcp:clip:v1:${d}:start`, time_us: -1 },
      { name: "replay_mcp:clip:v2:nope:start", time_us: 1 },
    ]);
    expect(parsed.accepted).toEqual([]);
    expect(parsed.diagnostics.map((item) => item.code)).toEqual(expect.arrayContaining([
      "duplicate_clip_marker", "invalid_clip_range", "invalid_clip_marker",
    ]));
  });

  it("verifies finalized source identity and detects changed replay bytes", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-source-"));
    const path = join(root, "take.mcpr");
    const original = Buffer.from("finalized-replay");
    await writeFile(path, original);
    const metadata = {
      path, sha256: createHash("sha256").update(original).digest("hex"), size: original.length,
      duration_us: 1_000_000, finalized: true, source_immutable: true,
    };
    await expect(verifyReplaySource(metadata)).resolves.toMatchObject({ path, size: original.length, duration_us: 1_000_000 });
    await writeFile(path, Buffer.from("modified-replay!"));
    await expect(verifyReplaySource(metadata)).rejects.toMatchObject({ code: "replay_changed" });
    await expect(verifyReplaySource({ ...metadata, finalized: false })).rejects.toMatchObject({ code: "replay_not_finalized" });
  });
});

function marker(id: string, kind: "start" | "end" | "revoke", time_us: number) {
  return { name: `replay_mcp:clip:v1:${id}:${kind}`, time_us };
}
