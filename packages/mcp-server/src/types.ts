import { z } from "zod";

export const BRIDGE_PROTOCOL = "replay-mcp.bridge/1" as const;
export const SIDECAR_VERSION = "0.1.0" as const;

export const JsonValueSchema: z.ZodType<unknown> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(JsonValueSchema),
    z.record(z.string(), JsonValueSchema),
  ]),
);

export const JsonObjectSchema = z.record(z.string(), JsonValueSchema);
export type JsonObject = Record<string, unknown>;

export const InstanceDescriptorSchema = z.object({
  instanceId: z.uuid(),
  processId: z.number().int().positive(),
  port: z.number().int().min(1).max(65_535),
  createdAt: z.iso.datetime(),
  displayName: z.string().min(1).max(256),
  gameDirectory: z.string().min(1),
  protocolVersion: z.literal(BRIDGE_PROTOCOL),
  modVersion: z.string().min(1),
  minecraftVersion: z.string().min(1),
  replayModVersion: z.string().min(1),
}).strict();
export type InstanceDescriptor = z.infer<typeof InstanceDescriptorSchema>;

export const BridgeErrorCodeSchema = z.enum([
  "protocol_mismatch", "unauthenticated", "invalid_request",
  "capability_unavailable", "invalid_mode", "query_too_large",
  "outside_loaded_area", "no_loaded_coverage", "control_required",
  "stale_fence", "control_busy", "conflict", "policy_denied", "timeout",
  "cancelled", "internal_error", "recording_not_armed",
]);
export type BridgeErrorCode = z.infer<typeof BridgeErrorCodeSchema>;

export const BridgeFailureSchema = z.object({
  jsonrpc: z.literal("2.0"),
  id: z.string(),
  error: z.object({
    code: BridgeErrorCodeSchema,
    message: z.string(),
    data: JsonObjectSchema.default({}),
  }).strict(),
}).strict();

export const BridgeSuccessSchema = z.object({
  jsonrpc: z.literal("2.0"),
  id: z.string(),
  result: JsonValueSchema,
}).strict();

export const BridgeEventSchema = z.object({
  jsonrpc: z.literal("2.0"),
  method: z.string().regex(/^[a-z]+(?:\.[a-z_]+)+$/),
  params: JsonObjectSchema.default({}),
}).strict();

export const ArtifactDescriptorSchema = z.object({
  path: z.string().min(1),
  mime_type: z.string().regex(/^[a-z0-9.+-]+\/[a-z0-9.+-]+$/i),
  size: z.number().int().nonnegative(),
  sha256: z.string().regex(/^[a-f0-9]{64}$/i),
  complete: z.boolean(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
}).passthrough();
export type ArtifactDescriptor = z.infer<typeof ArtifactDescriptorSchema>;

export const LeasePolicySchema = z.object({
  ttl_ms: z.number().int().min(5_000),
  heartbeat_interval_ms: z.number().int().min(1_000),
  idle_ceiling_ms: z.number().int().min(5_000),
}).strict().refine((value) => value.heartbeat_interval_ms < value.ttl_ms,
  "heartbeat interval must be shorter than lease TTL");

export const HelloSchema = z.object({
  protocol: z.literal(BRIDGE_PROTOCOL),
  instance_id: z.uuid(),
  process_id: z.number().int().positive(),
  connection_id: z.string().min(1),
  runtime_mode: z.string(),
  capabilities: JsonObjectSchema,
  lease: JsonObjectSchema,
  lease_policy: LeasePolicySchema,
  command_policy: JsonObjectSchema,
  flight_policy: JsonObjectSchema,
  allowed_artifact_roots: z.array(z.string().min(1)).min(1),
}).passthrough();
export type Hello = z.infer<typeof HelloSchema>;

export const LeaseSchema = z.object({
  lease_id: z.string().min(32),
  fence: z.number().int().positive(),
  owner_label: z.string().min(1).max(64),
  expires_at: z.iso.datetime(),
}).passthrough();
export type Lease = z.infer<typeof LeaseSchema>;

export function asObject(value: unknown, label = "bridge result"): JsonObject {
  const result = JsonObjectSchema.safeParse(value);
  if (!result.success) throw new TypeError(`${label} is not an object`);
  return result.data as JsonObject;
}
