import { appendFile, mkdir, readFile, rename, writeFile, rmdir } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { isDeepStrictEqual } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import { dirname, join } from "node:path";

export interface VersionedDocument {
  storage_version: 1;
}

export async function readJson<T>(path: string, fallback: T): Promise<T> {
  try {
    return JSON.parse(await readFile(path, "utf8")) as T;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return fallback;
    throw error;
  }
}

export async function writeJsonAtomic(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${process.pid}.${crypto.randomUUID()}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, path);
}

export class JsonCollection<T extends { id: string }> {
  readonly #path: string;
  readonly #items = new Map<string, T>();
  #loaded = false;
  #writeQueue: Promise<void> = Promise.resolve();

  constructor(dataDir: string, filename: string) {
    this.#path = join(dataDir, filename);
  }

  async load(): Promise<void> {
    if (this.#loaded) return;
    const document = await readJson<{ storage_version: 1; items: T[] }>(this.#path, { storage_version: 1, items: [] });
    if (document.storage_version !== 1 || !Array.isArray(document.items)) {
      throw new Error(`unsupported persistent document: ${this.#path}`);
    }
    for (const item of document.items) this.#items.set(item.id, item);
    this.#loaded = true;
  }

  #refresh(): void {
    let document: { storage_version: number; items: T[] };
    try { document = JSON.parse(readFileSync(this.#path, "utf8")); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return; throw error; }
    if (document.storage_version !== 1 || !Array.isArray(document.items)) throw new Error(`unsupported persistent document: ${this.#path}`);
    this.#items.clear();
    for (const item of document.items) this.#items.set(item.id, item);
  }
  values(): T[] { this.#refresh(); return structuredClone([...this.#items.values()]); }
  get(id: string): T | undefined { this.#refresh(); return structuredClone(this.#items.get(id)); }

  async set(item: T): Promise<void> {
    const expected = structuredClone(this.#items.get(item.id)), next = structuredClone(item);
    const write = async () => {
      await mkdir(dirname(this.#path), {recursive:true});
      const lock = `${this.#path}.lock`, deadline = Date.now() + 5_000;
      while (true) {
        try { await mkdir(lock); break; }
        catch (error) {
          if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
          if (Date.now() >= deadline) throw new Error(`persistent store lock timed out: ${lock}; stop writers before removing an abandoned lock`);
          await delay(25);
        }
      }
      try {
        const document = await readJson<{storage_version:1;items:T[]}>(this.#path,{storage_version:1,items:[]});
        if (document.storage_version !== 1 || !Array.isArray(document.items)) throw new Error(`unsupported persistent document: ${this.#path}`);
        const items = new Map(document.items.map(value => [value.id,value]));
        const current = items.get(next.id);
        if (!isDeepStrictEqual(current,expected) && !isDeepStrictEqual(current,next)) throw new Error(`persistent record changed: ${next.id}; refresh before retrying`);
        items.set(next.id,next);
        await writeJsonAtomic(this.#path,{storage_version:1,items:[...items.values()]});
        this.#items.set(next.id,next);
      } finally { await rmdir(lock); }
    };
    this.#writeQueue = this.#writeQueue.then(write,write);
    await this.#writeQueue;
  }
}

export class AuditLog {
  readonly #path: string;
  constructor(dataDir: string) { this.#path = join(dataDir, "audit", "sidecar.jsonl"); }
  async append(type: string, details: Record<string, unknown> = {}): Promise<void> {
    await mkdir(dirname(this.#path), { recursive: true });
    const record = { timestamp: new Date().toISOString(), type, ...details };
    await appendFile(this.#path, `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o600 });
  }
  path(): string { return this.#path; }
}
