import { createHash } from "node:crypto";
import { z } from "zod";

const frames = z.number().int().positive().max(100_000_000);
export const contractSchema = z.object({
  version: z.literal(1).default(1),
  original_request: z.string().min(1).max(32000),
  fps: z.number().int().min(1).max(240),
  target_frames: frames,
  runtime_tolerance: z.number().min(0).max(1).default(.2),
  min_shot_frames: frames.optional(), max_shot_frames: frames.optional(),
  preferred_min_frames: frames.optional(), preferred_max_frames: frames.optional(),
  min_shots: z.number().int().positive().optional(), max_shots: z.number().int().positive().optional(),
  reuse_budget_frames: z.number().int().nonnegative().default(0),
  require_collision: z.boolean().default(false),
  width: z.number().int().positive().max(16384), height: z.number().int().positive().max(16384),
  // Unimplemented requirements are retained, never silently interpreted as passing.
  mechanical_requirements: z.array(z.string().min(1)).max(128).default([]),
  subjective_criteria: z.array(z.string().min(1)).max(128).default([]),
}).strict().transform(c => ({ ...c,
  min_shot_frames: c.min_shot_frames ?? c.fps, max_shot_frames: c.max_shot_frames ?? 15 * c.fps,
  preferred_min_frames: c.preferred_min_frames ?? 3 * c.fps, preferred_max_frames: c.preferred_max_frames ?? 10 * c.fps,
})).superRefine((c, ctx) => {
  if (c.min_shot_frames > c.max_shot_frames || c.preferred_min_frames > c.preferred_max_frames || (c.min_shots !== undefined && c.max_shots !== undefined && c.min_shots > c.max_shots))
    ctx.addIssue({ code: "custom", message: "minimum must not exceed maximum" });
});
export type Contract = z.output<typeof contractSchema>;
export const editSchema = z.object({
  clips: z.array(z.object({ render_job_id: z.uuid(), in_frame: z.number().int().nonnegative(), out_frame: frames }).strict()
    .refine(c => c.out_frame > c.in_frame, "out_frame must exceed in_frame")).min(1).max(1024),
}).strict();
export type Edit = z.infer<typeof editSchema>;
export interface Plate {
  job_id: string; artifact_id: string; sha256: string; path: string;
  frames: number; fps: number; width: number; height: number;
  // Identity excludes names, IDs and encoding. Intervals use the immutable full camera timeline's output frames.
  lineage: string; start_frame: number; frame_identities?: string[];
  collision: "verified" | "skipped" | "unverified";
}
export interface Finding { rule: string; kind: "failure" | "missing" | "advisory"; clip?: number; range?: [number, number]; message: string }
export interface Completion { status: "PASS" | "FAIL" | "INCOMPLETE" | "PLAYER_OVERRIDDEN"; findings: Finding[]; total_frames: number; editorial_shots: number; reused_frames: number; binding: string; subjective_criteria: string[] }
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") return `{${Object.entries(value).filter(([,v]) => v !== undefined).sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0).map(([k,v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(",")}}`;
  return JSON.stringify(value);
}
export const digest = (value: unknown): string => createHash("sha256").update(canonical(value)).digest("hex");

/** Pure mechanical checks. Callers must resolve plates from trusted completed jobs, never tool-supplied receipts. */
export function checkCompletion(c: Contract, edit: Edit, plates: Map<string, Plate>, evidence: {
  locked: boolean; assembly?: { frames: number; fps: number; width: number; height: number; sha256: string; edit_hash: string; contract_hash: string };
  contract_hash: string; additional?: Finding[];
}): Completion {
  const findings: Finding[] = [...(evidence.additional ?? [])];
  const add = (rule: string, kind: Finding["kind"], message: string, clip?: number, range?: [number, number]) => findings.push({rule, kind, message, ...(clip === undefined ? {} : {clip}), ...(range ? {range} : {})});
  if (!evidence.locked) add("contract.locked", "missing", "Player has not locked this contract revision.");
  for (const requirement of c.mechanical_requirements) add("requirement.unsupported", "missing", `No mechanical evidence adapter for: ${requirement}`);
  let total = 0, reused = 0;
  const seen = new Map<string, [number, number][]>();
  const frameSpans: string[][] = [];
  const spans: { length: number; first: number; start: number }[] = [];
  let previous: { lineage: string; end: number } | undefined;
  edit.clips.forEach((clip, i) => {
    const length = clip.out_frame - clip.in_frame, outputStart = total; total += length;
    const p = plates.get(clip.render_job_id);
    if (!p) { add("render.receipt", "missing", "Current completed render receipt and verified artifact required.", i, [outputStart,total]); previous = undefined; spans.push({length, first:i, start:outputStart}); frameSpans.push([]); return; }
    if (p.fps !== c.fps || p.width !== c.width || p.height !== c.height) add("render.format", "failure", "Plate format differs from contract.", i);
    if (clip.out_frame > p.frames) add("edit.range", "failure", "Trim extends beyond decoded source frames.", i);
    if (c.require_collision && p.collision !== "verified") add("collision.required", "missing", `Collision evidence is ${p.collision}.`, i);
    const start = p.start_frame + clip.in_frame, end = p.start_frame + clip.out_frame;
    if (previous?.lineage === p.lineage && previous.end === start) { spans[spans.length-1]!.length += length; frameSpans.at(-1)!.push(...(p.frame_identities?.slice(clip.in_frame,clip.out_frame) ?? [])); }
    else { spans.push({length, first:i, start:outputStart}); frameSpans.push(p.frame_identities?.slice(clip.in_frame,clip.out_frame) ?? []); }
    previous = {lineage:p.lineage,end};
    const intervals = seen.get(p.lineage) ?? [];
    reused += intervals.reduce((sum, [a,b]) => sum + Math.max(0, Math.min(end,b)-Math.max(start,a)), 0);
    intervals.push([start,end]); intervals.sort((a,b) => a[0]-b[0]);
    const merged: [number,number][] = [];
    for (const interval of intervals) { const last = merged.at(-1); if (last && interval[0] <= last[1]) last[1] = Math.max(last[1],interval[1]); else merged.push([...interval]); }
    seen.set(p.lineage,merged);
  });
  // A histogram per editorial span preserves intentional static holds while detecting exact footage
  // repeated across separately authored paths, renamed jobs, trims, splits or re-encodings.
  const seenFrames=new Map<string,number>(); let repeatedIdentities=0;
  for (const span of frameSpans) {
    const counts=new Map<string,number>(); for(const token of span) counts.set(token,(counts.get(token) ?? 0)+1);
    for(const [token,count] of counts) { repeatedIdentities+=Math.min(count,seenFrames.get(token) ?? 0); seenFrames.set(token,Math.max(count,seenFrames.get(token) ?? 0)); }
  }
  reused=Math.max(reused,repeatedIdentities);
  if (reused > c.reuse_budget_frames) add("footage.reuse", "failure", `${reused} repeated source frames exceeds budget ${c.reuse_budget_frames}.`);
  const lower = Math.ceil(c.target_frames * (1-c.runtime_tolerance)), upper = Math.floor(c.target_frames * (1+c.runtime_tolerance));
  if (total < lower || total > upper) add("runtime.bounds", "failure", `${total} frames outside ${lower}–${upper}.`);
  for (const span of spans) {
    if (span.length < c.min_shot_frames || span.length > c.max_shot_frames) add("shot.bounds", "failure", `${span.length} frames outside hard shot bounds.`, span.first, [span.start,span.start+span.length]);
    else if (span.length < c.preferred_min_frames || span.length > c.preferred_max_frames) add("shot.pacing", "advisory", `${span.length} frames outside preferred pacing band.`, span.first);
  }
  if ((c.min_shots !== undefined && spans.length < c.min_shots) || (c.max_shots !== undefined && spans.length > c.max_shots)) add("shot.count", "failure", `${spans.length} editorial shots outside explicit count bounds.`);
  const a = evidence.assembly;
  if (!a) add("assembly.receipt", "missing", "Controlled final assembly receipt and artifact are required.");
  else {
    if (a.contract_hash !== evidence.contract_hash || a.edit_hash !== digest(edit)) add("assembly.stale", "missing", "Assembly receipt does not match this contract and edit.");
    if (a.frames !== total || a.fps !== c.fps || a.width !== c.width || a.height !== c.height) add("assembly.metadata", "failure", "Decoded final artifact differs from edit or contract.");
  }
  return {status: findings.some(f => f.kind === "failure") ? "FAIL" : findings.some(f => f.kind === "missing") ? "INCOMPLETE" : "PASS", findings, total_frames:total, editorial_shots:spans.length, reused_frames:reused,
    binding: digest({contract:evidence.contract_hash, edit, artifact:a?.sha256, findings}), subjective_criteria:c.subjective_criteria};
}
