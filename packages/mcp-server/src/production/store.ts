import { mkdir, rename, rm } from "node:fs/promises";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { JsonCollection } from "../persistence.js";
import { hashFile, type ArtifactStore } from "../artifacts/store.js";
import type { JobStore } from "../jobs/store.js";
import { SidecarError } from "../bridge/discovery.js";
import { checkCompletion, digest, type Contract, type Edit, type Plate, type Completion, type Finding } from "./contract.js";

export interface Assembly { frames: number; fps: number; width: number; height: number; sha256: string; edit_hash: string; contract_hash: string; path: string; assembler: string }
interface Production { active_assembly_job?:string; id: string; storage_version: 1; edit_revision: number; edit?: Edit; assembly?: Assembly; completion?: Completion; attempts: number; no_progress: number; last_diagnostics?: string; authority_snapshot?: Record<string, unknown> }
export class ProductionStore {
  readonly #data: JsonCollection<Production>;
  readonly #busy = new Set<string>();
  constructor(readonly dataDir: string, readonly artifacts: ArtifactStore, readonly jobs: JobStore) { this.#data = new JsonCollection(dataDir,"production/index.json"); }
  async load(): Promise<void> { await this.#data.load(); }
  private active(id:string):boolean {
    if(this.#busy.has(id))return true;
    const owner=this.get(id).active_assembly_job;if(!owner)return false;
    const job=this.jobs.get(owner);return !!job && ["queued","running"].includes(job.status);
  }
  get(id: string): Production { return structuredClone(this.#data.get(id) ?? {id,storage_version:1,edit_revision:0,attempts:0,no_progress:0}); }
  async snapshot(id: string, state: Record<string, unknown>): Promise<void> { const p = this.get(id); p.authority_snapshot = state; await this.#data.set(p); }
  async setEdit(id: string, revision: number, edit: Edit): Promise<Production> {
    if (this.active(id)) throw new SidecarError("conflict","assembly is active");
    const p = this.get(id); if (revision !== p.edit_revision) throw new SidecarError("revision_conflict","edit revision changed");
    p.edit = edit; p.edit_revision++; delete p.active_assembly_job; delete p.assembly; delete p.completion; await this.#data.set(p); return p;
  }
  async plates(id: string, edit: Edit): Promise<{plates:Map<string,Plate>; findings:Finding[]}> {
    const plates = new Map<string,Plate>(), findings: Finding[] = [];
    for (const clip of edit.clips) {
      if (plates.has(clip.render_job_id)) continue;
      const job = this.jobs.get(clip.render_job_id);
      if (!job || job.project_id !== id || job.kind !== "render" || job.status !== "completed" || !job.bridge_backed || job.result?.complete !== true || job.result_artifact_ids.length !== 1) continue;
      const r = job.result.render_receipt as Record<string,unknown> | undefined;
      const artifact = this.artifacts.get(job.result_artifact_ids[0]!);
      if (!r || r.version !== 1 || r.quality !== "high_quality" || r.certifiable_projection !== true || !artifact || typeof r.replay_sha256 !== "string" || typeof r.native_path_hash !== "string" || typeof r.fps !== "number" || typeof r.start_us !== "number") continue;
      try {
        if (await hashFile(artifact.path) !== artifact.sha256) throw new Error("render artifact hash changed");
        const meta = await probe(artifact.path);
        if (!Array.isArray(r.frame_identities) || r.frame_identities.length !== meta.frames || !r.frame_identities.every(v=>typeof v === "string" && /^[a-f0-9]{64}$/.test(v))) throw new Error("exact native frame lineage unavailable; spectator or oversized render is uncertified");
        if (meta.fps !== r.fps || meta.width !== r.width || meta.height !== r.height) throw new Error("render metadata does not match native receipt");
        const start = r.start_us * r.fps / 1_000_000;
        if (!Number.isInteger(start)) throw new Error("render start is not frame aligned");
        plates.set(job.id,{job_id:job.id,artifact_id:artifact.id,sha256:artifact.sha256,path:artifact.path,...meta,lineage:digest({replay:r.replay_sha256,camera:r.native_path_hash,fps:r.fps}),start_frame:start,frame_identities:r.frame_identities as string[],collision:receiptCollision(r)});
      } catch (e) { findings.push({rule:"render.artifact",kind:"missing",message:e instanceof Error ? e.message : String(e)}); }
    }
    return {plates,findings};
  }
  async check(id: string, contract: Contract, contractHash: string, locked: boolean): Promise<Completion> {
    if (this.active(id)) throw new SidecarError("conflict","assembly is active; check after it completes");
    const p = this.get(id), edit = p.edit ?? {clips:[]};
    const {plates,findings} = await this.plates(id,edit);
    let assembly = p.assembly;
    if (assembly) try { if (await hashFile(assembly.path) !== assembly.sha256) throw new Error("final artifact hash changed"); const meta = await probe(assembly.path); assembly = {...assembly,...meta}; }
    catch { assembly = undefined; delete p.assembly; findings.push({rule:"assembly.artifact",kind:"missing",message:"Final artifact is missing or changed."}); }
    const result = checkCompletion(contract,edit,plates,{locked,contract_hash:contractHash,...(assembly ? {assembly} : {}),additional:findings});
    p.completion = result; await this.#data.set(p); return result;
  }
  async assemble(id: string, contract: Contract, contractHash: string, assemblyJobId?:string): Promise<Production> {
    if (this.active(id)) throw new SidecarError("conflict","assembly already active");
    const p = this.get(id); if (!p.edit) throw new SidecarError("invalid_request","edit plan required");
    if (p.attempts >= 8 || p.no_progress >= 3) throw new SidecarError("repair_budget_exhausted","repair budget exhausted; result remains incomplete; create a new project for a new production");
    this.#busy.add(id);
    const root = join(this.artifacts.localRoot,"productions",id,crypto.randomUUID());
    const parts:string[] = [];
    try {
      p.attempts++; if(assemblyJobId)p.active_assembly_job=assemblyJobId; await this.#data.set(p);
      const {plates} = await this.plates(id,p.edit);
      if (p.edit.clips.some(c => !plates.has(c.render_job_id))) throw new SidecarError("evidence_missing","every plate requires a current completed native render receipt");
      await mkdir(root,{recursive:true});
      // Only integer-frame trims and straight cuts. No agent-supplied command, filters, filenames or audio.
      for (const [index,clip] of p.edit.clips.entries()) {
        const plate = plates.get(clip.render_job_id)!;
        if (clip.out_frame > plate.frames || plate.fps !== contract.fps || plate.width !== contract.width || plate.height !== contract.height) throw new SidecarError("invalid_edit","plate range or format differs from contract");
        const path = join(root,`part-${index}.mkv`); parts.push(path);
        await run("ffmpeg",["-v","error","-nostdin","-i",plate.path,"-map","0:v:0","-vf",`trim=start_frame=${clip.in_frame}:end_frame=${clip.out_frame},setpts=PTS-STARTPTS`,"-an","-c:v","ffv1","-y",path]);
        if (await hashFile(plate.path) !== plate.sha256) throw new SidecarError("evidence_stale","source changed during assembly");
      }
      // Fixed numeric part names avoid ffconcat quoting or protocol injection.
      const {writeFile} = await import("node:fs/promises");
      const list = join(root,"parts.ffconcat"); await writeFile(list,"ffconcat version 1.0\n"+parts.map((_,i) => `file part-${i}.mkv`).join("\n")+"\n");
      const partial = join(root,"partial.mp4"), final = join(root,"master.mp4");
      await run("ffmpeg",["-v","error","-nostdin","-f","concat","-safe","1","-i",list,"-map","0:v:0","-an","-c:v","libx264","-crf","18","-pix_fmt","yuv420p","-movflags","+faststart","-y",partial]);
      const meta = await probe(partial); await rename(partial,final);
      const latest=this.get(id);
      if(latest.edit_revision!==p.edit_revision || digest(latest.edit)!==digest(p.edit) || (assemblyJobId && latest.active_assembly_job!==assemblyJobId))throw new SidecarError("evidence_stale","edit or assembly ownership changed during export; finished artifact retained but not certified");
      if(latest.authority_snapshot)p.authority_snapshot=latest.authority_snapshot;else delete p.authority_snapshot;
      delete p.active_assembly_job;
      p.assembly = {...meta,sha256:await hashFile(final),path:final,edit_hash:digest(p.edit),contract_hash:contractHash,assembler:"replay-mcp-cuts/1"};
      delete p.completion; p.no_progress = 0;
      await this.artifacts.registerLocal(final,"video/mp4",{project_id:id,provenance:{assembler:p.assembly.assembler,edit_hash:p.assembly.edit_hash,contract_hash:contractHash}});
      await this.#data.set(p); return p;
    } catch (e) {
      const diagnostic = e instanceof Error ? e.message : String(e);
      const current=this.get(id);
      current.no_progress = current.last_diagnostics === diagnostic ? current.no_progress+1 : 1; current.last_diagnostics = diagnostic;
      if(!assemblyJobId || current.active_assembly_job===assemblyJobId)delete current.active_assembly_job;
      await this.#data.set(current); throw e;
    } finally { this.#busy.delete(id); await Promise.all([...parts,join(root,"parts.ffconcat")].map(path=>rm(path,{force:true}))); }
  }
}
export async function probe(path: string): Promise<{frames:number;fps:number;width:number;height:number}> {
  const raw = JSON.parse(await run("ffprobe",["-v","error","-select_streams","v:0","-count_frames","-show_entries","stream=nb_read_frames,avg_frame_rate,width,height","-of","json",path])) as {streams: {nb_read_frames:string;avg_frame_rate:string;width:number;height:number}[]};
  const s = raw.streams[0]; if (!s) throw new Error("no video stream");
  const [a,b] = s.avg_frame_rate.split("/").map(Number), fps = a! / b!, count = Number(s.nb_read_frames);
  if (!Number.isInteger(count) || count < 1 || !Number.isFinite(fps) || fps <= 0) throw new Error("unverifiable media frame metadata");
  return {frames:count,fps,width:s.width,height:s.height};
}
function run(command: string, args: string[]): Promise<string> {
  return new Promise((resolve,reject) => {
    const child = spawn(command,args,{stdio:["ignore","pipe","pipe"]}); let out="",error="";
    const timer = setTimeout(() => { child.kill("SIGKILL"); reject(new Error(`${command} exceeded 10-minute limit`)); },600_000);
    child.stdout.on("data",(b:Buffer) => { out+=b.toString(); if (out.length>2_000_000) child.kill(); });
    child.stderr.on("data",(b:Buffer) => { error=(error+b.toString()).slice(-8000); });
    child.on("error",e => { clearTimeout(timer); reject(e); }); child.on("close",code => { clearTimeout(timer); code===0 ? resolve(out) : reject(new Error(`${command} failed: ${error}`)); });
  });
}

/** Retire receipts from the client hasChunkAt false-positive policy. */
export function receiptCollision(receipt: Record<string, unknown>): Plate["collision"] {
  if (receipt.collision === "skipped") return "skipped";
  const evidence = receipt.clearance_evidence as Record<string, unknown> | undefined;
  return receipt.collision === "verified" && (evidence?.policy === "native-linear-frozen-sweep/3" || (evidence?.policy === "native-linear-packet-static-sweep/1" && (evidence.temporal_history as Record<string,unknown>|undefined)?.verified === true && (evidence.temporal_history as Record<string,unknown>).policy === "packet-static-world/1"))
    && evidence.collision_check === "verified" ? "verified" : "unverified";
}
