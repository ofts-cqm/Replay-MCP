import { cp, mkdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(packageRoot, "../..");
const destination = resolve(packageRoot, "protocol", "bridge-v1");
await mkdir(resolve(packageRoot, "protocol"), { recursive: true });
await rm(destination, { recursive: true, force: true });
await cp(resolve(repositoryRoot, "protocol", "bridge-v1"), destination, { recursive: true });
