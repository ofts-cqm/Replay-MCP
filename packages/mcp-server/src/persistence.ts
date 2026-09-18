import { appendFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
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

  values(): T[] { return [...this.#items.values()]; }
  get(id: string): T | undefined { return this.#items.get(id); }

  async set(item: T): Promise<void> {
    this.#items.set(item.id, item);
    const document = { storage_version: 1 as const, items: this.values() };
    this.#writeQueue = this.#writeQueue.then(async () => await writeJsonAtomic(this.#path, document));
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
