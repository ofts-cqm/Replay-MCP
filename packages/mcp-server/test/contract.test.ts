import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Ajv } from "ajv";
import { describe, expect, it } from "vitest";

describe("bridge-v1 shared fixtures", () => {
  it("accepts every valid fixture and rejects every invalid fixture", async () => {
    const root = resolve(import.meta.dirname, "../../../protocol/bridge-v1");
    const ajv = new Ajv({ strict: true, allErrors: true, formats: { uuid: true, "date-time": true } });
    const validators = new Map<string, ReturnType<typeof ajv.compile>>();
    for (const name of ["discovery.schema.json", "rpc.schema.json", "artifact.schema.json", "status.schema.json", "lease.schema.json", "action.schema.json", "timeline.schema.json", "render.schema.json", "spatial-map.schema.json"]) {
      validators.set(name, ajv.compile(JSON.parse(await readFile(resolve(root, "schemas", name), "utf8"))));
    }
    for (const fixture of JSON.parse(await readFile(resolve(root, "fixtures", "valid.json"), "utf8")) as { schema: string; value: unknown }[]) {
      expect(validators.get(fixture.schema)?.(fixture.value), JSON.stringify(validators.get(fixture.schema)?.errors)).toBe(true);
    }
    for (const fixture of JSON.parse(await readFile(resolve(root, "fixtures", "invalid.json"), "utf8")) as { schema: string; value: unknown }[]) {
      expect(validators.get(fixture.schema)?.(fixture.value), fixture.schema).toBe(false);
    }
  });
});
