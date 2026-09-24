import { z } from "zod";
import type { BridgeClient } from "../bridge/client.js";
import { SidecarError } from "../bridge/discovery.js";
import { ReplayMcpRuntime, objectResult } from "../runtime.js";

const blockCoordinate = z.number().int().min(-2_147_483_648).max(2_147_483_647);
const boundsSchema = z.object({
  min_x: blockCoordinate, max_x: blockCoordinate,
  min_y: blockCoordinate, max_y: blockCoordinate,
  min_z: blockCoordinate, max_z: blockCoordinate,
}).strict().superRefine((bounds, context) => {
  for (const axis of ["x", "y", "z"] as const) {
    if (bounds[`min_${axis}`] >= bounds[`max_${axis}`]) {
      context.addIssue({ code: "custom", path: [`max_${axis}`], message: `max_${axis} must be greater than min_${axis}` });
    }
  }
});

const queryCommon = {
  kind: z.literal("spatial_map"),
  instance_id: z.uuid().optional(),
  bounds: boundsSchema,
  cell_size: z.union([z.literal(2), z.literal(4), z.literal(8), z.literal(16), z.literal(32)]),
  material_mix_limit: z.union([z.literal(1), z.literal(2), z.literal(3)]).default(1),
  require_complete: z.boolean().default(false),
  refines_map_id: z.string().min(1).max(128).optional(),
  timeout_ms: z.number().int().min(250).max(300_000).optional(),
};

export const spatialMapQuerySchema = z.discriminatedUnion("representation", [
  z.object({
    ...queryCommon,
    representation: z.literal("surface"),
    surface_mode: z.enum(["world_surface", "motion_blocking_no_leaves"]).default("motion_blocking_no_leaves"),
    fallback_reason: z.enum(["unresolved_surface_boundary", "adjacent_surfaces"]).optional(),
  }).strict().superRefine(validateFallback),
  z.object({
    ...queryCommon,
    representation: z.literal("volume"),
    fallback_reason: z.enum(["tight_clearance", "thin_geometry", "adjacent_volumes"]).optional(),
  }).strict().superRefine(validateFallback),
]);

export type SpatialMapArgs = z.infer<typeof spatialMapQuerySchema>;
type Bounds = z.infer<typeof boundsSchema>;

interface MapRecord {
  map_id: string;
  instance_id: string;
  representation: "surface" | "volume";
  cell_size: number;
  effective_bounds: Bounds;
  dimension: string;
  replay_time_us?: number;
  created_at_ms: number;
}

export class SpatialMapRegistry {
  readonly #maps = new Map<string, MapRecord>();
  readonly #maximumEntries: number;
  constructor(maximumEntries = 256) { this.#maximumEntries = maximumEntries; }

  async query(runtime: ReplayMcpRuntime, args: SpatialMapArgs, signal?: AbortSignal): Promise<{ result: Record<string, unknown>; client: BridgeClient }> {
    const client = runtime.client(args.instance_id);
    const capability = spatialCapability(client);
    const effective = validateAgainstCapability(args, capability);
    let refinement: MapRecord | undefined;
    if (args.cell_size === 2) {
      refinement = this.#validateRefinement(args, client, effective, capability);
      const status = objectResult(await client.call("system.status", {}, { deadlineMs: timeout(args), ...(signal ? { signal } : {}) }));
      validateCurrentWorld(refinement, status);
      await runtime.audit.append("spatial_map.fallback_requested", {
        instance_id: client.descriptor.instanceId,
        refines_map_id: args.refines_map_id,
        representation: args.representation,
        fallback_reason: args.fallback_reason,
        requested_bounds: args.bounds,
        effective_bounds: effective,
      });
    }

    const { instance_id: _instanceId, kind: _kind, timeout_ms: _timeoutMs, ...params } = args;
    const result = objectResult(await client.call("observation.spatial_map", params, { deadlineMs: timeout(args), ...(signal ? { signal } : {}) }));
    validateBridgeIdentity(result, client, args, refinement);
    this.#remember(result, client);
    const work = isObject(result.work) ? result.work : {};
    await runtime.audit.append("spatial_map.completed", {
      instance_id: client.descriptor.instanceId,
      map_id: result.map_id,
      representation: args.representation,
      cell_size: args.cell_size,
      fallback_reason: args.fallback_reason,
      response_bytes: result.response_bytes,
      aggregate_cells: work.aggregate_cells,
      source_columns: work.source_columns,
      logical_voxels: work.logical_voxels,
      scan_slices: work.scan_slices,
      client_thread_us: work.client_thread_us,
      max_slice_us: work.max_slice_us,
    });
    return { result, client };
  }

  #validateRefinement(args: SpatialMapArgs, client: BridgeClient, effective: Bounds,
                      capability: Record<string, unknown>): MapRecord {
    const record = args.refines_map_id ? this.#maps.get(args.refines_map_id) : undefined;
    if (!record) throw new SidecarError("conflict", "refines_map_id is not a recent map from this sidecar session", { reason: "unknown_refinement_map" });
    const maxAge = finitePositive(capability.max_map_age_ms) ?? 600_000;
    if (Date.now() - record.created_at_ms > maxAge) {
      this.#maps.delete(record.map_id);
      throw new SidecarError("conflict", "refines_map_id is too old to refine safely", { reason: "stale_refinement_map", max_age_ms: maxAge });
    }
    if (record.instance_id !== client.descriptor.instanceId || record.representation !== args.representation || record.cell_size !== 4) {
      throw new SidecarError("conflict", "refines_map_id must identify a size-4 map for the same instance and representation", { reason: "incompatible_refinement_map" });
    }
    if (!contains(record.effective_bounds, effective)) {
      throw new SidecarError("conflict", "the size-4 map does not contain the requested level-2 refinement", { reason: "refinement_outside_parent" });
    }
    return record;
  }

  #remember(result: Record<string, unknown>, client: BridgeClient): void {
    if (typeof result.map_id !== "string" || typeof result.representation !== "string" || typeof result.cell_size !== "number"
        || typeof result.dimension !== "string" || !isBounds(result.effective_bounds)) {
      throw new SidecarError("invalid_response", "bridge returned an invalid spatial-map identity");
    }
    const representation = result.representation;
    if (representation !== "surface" && representation !== "volume") throw new SidecarError("invalid_response", "bridge returned an invalid spatial-map representation");
    this.#maps.set(result.map_id, {
      map_id: result.map_id,
      instance_id: client.descriptor.instanceId,
      representation,
      cell_size: result.cell_size,
      effective_bounds: result.effective_bounds,
      dimension: result.dimension,
      ...(typeof result.replay_time_us === "number" ? { replay_time_us: result.replay_time_us } : {}),
      created_at_ms: Date.now(),
    });
    while (this.#maps.size > this.#maximumEntries) this.#maps.delete(this.#maps.keys().next().value!);
  }
}

function validateFallback(value: { cell_size: number; refines_map_id?: string | undefined; fallback_reason?: string | undefined }, context: z.RefinementCtx): void {
  if (value.cell_size === 2) {
    if (!value.refines_map_id) context.addIssue({ code: "custom", path: ["refines_map_id"], message: "refines_map_id is required for cell_size 2" });
    if (!value.fallback_reason) context.addIssue({ code: "custom", path: ["fallback_reason"], message: "fallback_reason is required for cell_size 2" });
  } else {
    if (value.refines_map_id !== undefined) context.addIssue({ code: "custom", path: ["refines_map_id"], message: "refines_map_id is valid only for cell_size 2" });
    if (value.fallback_reason !== undefined) context.addIssue({ code: "custom", path: ["fallback_reason"], message: "fallback_reason is valid only for cell_size 2" });
  }
}

function spatialCapability(client: BridgeClient): Record<string, unknown> {
  const capabilities = client.hello?.capabilities;
  const capability = isObject(capabilities) && isObject(capabilities.spatial_map) ? capabilities.spatial_map : undefined;
  if (!capability || capability.surface !== true || capability.volume !== true || capability.loaded_chunks_only !== true) {
    throw new SidecarError("capability_unavailable", "the connected Replay MCP mod does not advertise spatial-map support");
  }
  return capability;
}

function validateAgainstCapability(args: SpatialMapArgs, capability: Record<string, unknown>): Bounds {
  const fallback = args.cell_size === 2;
  const sizesName = `${args.representation}_${fallback ? "fallback_" : ""}cell_sizes`;
  if (!numberArray(capability[sizesName]).includes(args.cell_size)) {
    throw new SidecarError("capability_unavailable", `cell_size ${args.cell_size} is not advertised for ${args.representation}`);
  }
  if (!numberArray(capability.material_mix_limits).includes(args.material_mix_limit)) {
    throw new SidecarError("capability_unavailable", `material_mix_limit ${args.material_mix_limit} is not advertised`);
  }
  if (args.representation === "surface" && !stringArray(capability.surface_modes).includes(args.surface_mode)) {
    throw new SidecarError("capability_unavailable", `surface_mode ${args.surface_mode} is not advertised`);
  }
  const effective = align(args.bounds, args.cell_size, true);
  if (Object.values(effective).some((value) => value < -2_147_483_648 || value > 2_147_483_647)) {
    throw new SidecarError("invalid_request", "aligned bounds exceed 32-bit world coordinates", { effective_bounds: effective });
  }
  const limitsRoot = isObject(capability.limits) ? capability.limits : undefined;
  const limitName = fallback ? `${args.representation}_fallback` : `ordinary_${args.representation}`;
  const limits = limitsRoot && isObject(limitsRoot[limitName]) ? limitsRoot[limitName] : undefined;
  if (!limits) throw new SidecarError("capability_unavailable", "the connected mod did not advertise spatial-map work limits");
  const spanX = effective.max_x - effective.min_x, spanY = effective.max_y - effective.min_y, spanZ = effective.max_z - effective.min_z;
  const aggregateCells = (spanX / args.cell_size) * (spanZ / args.cell_size) * (args.representation === "volume" ? spanY / args.cell_size : 1);
  const sourceWork = spanX * spanZ * (args.representation === "volume" ? spanY : 1);
  const maxCells = finitePositive(limits.max_result_cells), maxWork = finitePositive(limits[args.representation === "surface" ? "max_source_columns" : "max_logical_voxels"]);
  const maxSpan = finitePositive(limits.max_axis_span);
  const actualMaxSpan = args.representation === "surface" ? Math.max(spanX, spanZ) : Math.max(spanX, spanY, spanZ);
  if (!maxCells || !maxWork || !maxSpan) throw new SidecarError("capability_unavailable", "the connected mod advertised invalid spatial-map limits");
  if (aggregateCells > maxCells || sourceWork > maxWork || actualMaxSpan > maxSpan) {
    throw new SidecarError("query_too_large", "spatial map exceeds the negotiated work limits", {
      aggregate_cells: aggregateCells, max_aggregate_cells: maxCells,
      [args.representation === "surface" ? "source_columns" : "logical_voxels"]: sourceWork,
      max_source_work: maxWork, max_axis_span: maxSpan, effective_bounds: effective,
      suggested_cell_size: args.cell_size < 4 ? 4 : args.cell_size < 8 ? 8 : args.cell_size < 16 ? 16 : 32,
    });
  }
  return effective;
}

function validateCurrentWorld(record: MapRecord, status: Record<string, unknown>): void {
  if (status.dimension !== record.dimension) throw new SidecarError("conflict", "the active dimension differs from the refinement map", { reason: "refinement_world_changed" });
  if (record.replay_time_us !== undefined && status.replay_time_us !== record.replay_time_us) {
    throw new SidecarError("conflict", "the paused replay time differs from the refinement map", { reason: "refinement_replay_time_changed" });
  }
}

function validateBridgeIdentity(result: Record<string, unknown>, client: BridgeClient, args: SpatialMapArgs, refinement?: MapRecord): void {
  if (result.instance_id !== client.descriptor.instanceId || result.representation !== args.representation || result.cell_size !== args.cell_size) {
    throw new SidecarError("invalid_response", "bridge returned a spatial map for the wrong request identity");
  }
  if (refinement && (result.dimension !== refinement.dimension || result.replay_time_us !== refinement.replay_time_us)) {
    throw new SidecarError("conflict", "world identity changed while the level-2 refinement was running", { reason: "refinement_world_changed" });
  }
}

function align(bounds: Bounds, cellSize: number, alignY: boolean): Bounds {
  const down = (value: number) => Math.floor(value / cellSize) * cellSize;
  const up = (value: number) => Math.ceil(value / cellSize) * cellSize;
  return {
    min_x: down(bounds.min_x), max_x: up(bounds.max_x),
    min_y: alignY ? down(bounds.min_y) : bounds.min_y, max_y: alignY ? up(bounds.max_y) : bounds.max_y,
    min_z: down(bounds.min_z), max_z: up(bounds.max_z),
  };
}

function contains(outer: Bounds, inner: Bounds): boolean {
  return outer.min_x <= inner.min_x && outer.max_x >= inner.max_x
    && outer.min_y <= inner.min_y && outer.max_y >= inner.max_y
    && outer.min_z <= inner.min_z && outer.max_z >= inner.max_z;
}
function timeout(args: SpatialMapArgs): number { return args.timeout_ms ?? 30_000; }
function finitePositive(value: unknown): number | undefined { return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : undefined; }
function numberArray(value: unknown): number[] { return Array.isArray(value) ? value.filter((item): item is number => typeof item === "number") : []; }
function stringArray(value: unknown): string[] { return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []; }
function isObject(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value); }
function isBounds(value: unknown): value is Bounds {
  return isObject(value) && ["min_x", "max_x", "min_y", "max_y", "min_z", "max_z"].every((key) => typeof value[key] === "number");
}
