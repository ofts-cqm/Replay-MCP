import {JsonCollection} from "../persistence.js";
import {hashFile,type ArtifactStore} from "../artifacts/store.js";
import type {JobStore,JobRecord} from "../jobs/store.js";
import type {ProjectManifest} from "../projects/store.js";
import type {ProductionStore} from "./store.js";
import {digest} from "./contract.js";
import {SidecarError} from "../bridge/discovery.js";
interface Checkpoint {id:string;project_id:string;shot_id:string;plan_hash:string;artifact_id:string;native_path_hash:string;timeline_revision:string;clearance:Record<string,unknown>;created_at:string}
interface VisualReview {id:string;project_id:string;shot_id:string;plan_hash:string;job_id:string;artifact_hashes:Record<string,string>;findings:string;outcome:"accepted"|"revise";created_at:string}
export function plannedShots(project:ProjectManifest):Record<string,unknown>[] {
 const result:Record<string,unknown>[]=[];
 for(const scene of project.scenes) {
  if(Array.isArray(scene.shots))result.push(...scene.shots as Record<string,unknown>[]);
  if(Array.isArray(scene.takes))for(const take of scene.takes as Record<string,unknown>[])if(Array.isArray(take.shots))result.push(...take.shots as Record<string,unknown>[]);
 }
 const ids=new Set<string>();for(const shot of result){if(typeof shot.id!=="string" || ids.has(shot.id))throw new SidecarError("project_invalid","progress requires unique shot IDs");ids.add(shot.id);}
 return result;
}
export function shotBinding(project:ProjectManifest,shotId:string):{project_id:string;shot_id:string;plan_hash:string} {
 const shot=plannedShots(project).find(s=>s.id===shotId);if(!shot)throw new SidecarError("shot_not_found",`Shot ${shotId} is not in the persisted plan`);
 // Context is retained separately from sibling shots and editorial notes. Old v1 hashes
 // intentionally become stale rather than inheriting evidence for a different source.
 let sceneContext:Record<string,unknown>={},takeContext:Record<string,unknown>|null=null;
 const context=(value:Record<string,unknown>)=>Object.fromEntries(Object.entries(value).filter(([key])=>!["shots","takes","notes","title","description"].includes(key)));
 for(const scene of project.scenes) {
  if(Array.isArray(scene.shots)&&scene.shots.includes(shot))sceneContext=context(scene);
  if(Array.isArray(scene.takes))for(const take of scene.takes as Record<string,unknown>[])if(Array.isArray(take.shots)&&take.shots.includes(shot)){sceneContext=context(scene);takeContext=context(take);}
 }
 return {project_id:project.id,shot_id:shotId,plan_hash:digest({version:2,shot,scene:sceneContext,take:takeContext,frame_rate:project.frame_rate,resolution:project.resolution})};
}
export class ProgressStore {
 readonly failures:JsonCollection<{id:string;project_id:string;shot_id:string;plan_hash:string;code:string;message:string;diagnostics:Record<string,unknown>;created_at:string}>;
 readonly summaries:JsonCollection<Record<string,unknown>&{id:string}>;
 readonly paths:JsonCollection<Checkpoint>;readonly reviews:JsonCollection<VisualReview>;
 constructor(root:string,readonly artifacts:ArtifactStore,readonly jobs:JobStore,readonly production:ProductionStore){this.failures=new JsonCollection(root,"production/blocked-shots.json");this.summaries=new JsonCollection(root,"production/progress.json");this.paths=new JsonCollection(root,"production/paths.json");this.reviews=new JsonCollection(root,"production/visual-reviews.json");}
 async load(){await Promise.all([this.paths.load(),this.reviews.load(),this.summaries.load(),this.failures.load()]);}
 async blocked(project:ProjectManifest,shot:string,code:string,message:string,diagnostics:Record<string,unknown>={}) {
  await this.failures.set({...shotBinding(project,shot),id:crypto.randomUUID(),code,message,diagnostics,created_at:new Date().toISOString()});
 }
 async generated(project:ProjectManifest,shot:string,artifactId:string,revision:string,clearance:Record<string,unknown>){
  const binding=shotBinding(project,shot);await this.paths.set({...binding,id:crypto.randomUUID(),artifact_id:artifactId,timeline_revision:revision,native_path_hash:String(clearance.native_path_hash??""),clearance,created_at:new Date().toISOString()});
 }
 async visual(project:ProjectManifest,shot:string,jobId:string,outcome:"accepted"|"revise",findings:string){
  const binding=shotBinding(project,shot),job=this.jobs.get(jobId);
  if(!job || job.status!=="completed" || job.project_id!==project.id || job.shot_id!==shot || job.plan_hash!==binding.plan_hash || !["preview","render","still"].includes(job.kind) || !job.result_artifact_ids.length)throw new SidecarError("evidence_missing","visual notes require a completed matching image/video job");
  const hashes:Record<string,string>={};for(const id of job.result_artifact_ids){const a=this.artifacts.get(id);if(!a || await hashFile(a.path)!==a.sha256)throw new SidecarError("evidence_stale","visual artifact missing or changed");hashes[id]=a.sha256;}
  const review={...binding,id:crypto.randomUUID(),job_id:jobId,outcome,findings,artifact_hashes:hashes,created_at:new Date().toISOString()};await this.reviews.set(review);return {...review,authority:"agent visual observation only; cannot override mechanical checks"};
 }
 async summary(project:ProjectManifest){
  const planned=plannedShots(project),production=this.production.get(project.id),jobs=this.jobs.list().filter(j=>j.project_id===project.id),paths=this.paths.values(),reviews=this.reviews.values(),failures=this.failures.values();
  const edits={clips:jobs.filter(j=>j.kind==="render").map(j=>({render_job_id:j.id,in_frame:0,out_frame:1}))};
  const {plates}=await this.production.plates(project.id,edits);
  const validArtifact=async(id:string)=>{const a=this.artifacts.get(id);try{return !!a && a.complete && await hashFile(a.path)===a.sha256;}catch{return false;}};
  const shots=await Promise.all(planned.map(async shot=>{
   const binding=shotBinding(project,String(shot.id)),matching=jobs.filter(j=>j.shot_id===shot.id && j.plan_hash===binding.plan_hash);
   const path=paths.filter(p=>p.project_id===project.id && p.shot_id===shot.id && p.plan_hash===binding.plan_hash).at(-1);
   const failure=failures.filter(f=>f.project_id===project.id && f.shot_id===shot.id && f.plan_hash===binding.plan_hash).at(-1);
   const blocked=failure && (!path || failure.created_at>=path.created_at)?failure:null;
   const pathCurrent=path && await validArtifact(path.artifact_id);
   const renders=matching.filter(j=>plates.has(j.id) && (!pathCurrent || (j.result?.render_receipt as Record<string,unknown>|undefined)?.native_path_hash===path.native_path_hash));
   const review=reviews.filter(r=>r.project_id===project.id && r.shot_id===shot.id && r.plan_hash===binding.plan_hash && matching.some(j=>j.id===r.job_id && j.status==="completed")).at(-1);
   const reviewJob=review?matching.find(j=>j.id===review.job_id):undefined;
   const samePath=!pathCurrent || (reviewJob?.result?.render_receipt as Record<string,unknown>|undefined)?.native_path_hash===path.native_path_hash || reviewJob?.result?.timeline_revision===path.timeline_revision;
   const visualCurrent=review && samePath && (await Promise.all(Object.keys(review.artifact_hashes).map(validArtifact))).every(Boolean);
   return {shot_id:shot.id,plan_hash:binding.plan_hash,blocked,generation:pathCurrent?{artifact_id:path.artifact_id,timeline_revision:path.timeline_revision}:null,geometry:pathCurrent?path.clearance:null,visual_review:visualCurrent?{job_id:review.job_id,outcome:review.outcome,authority:"agent_observation"}:null,render_job_ids:renders.map(j=>j.id),unfinished_jobs:matching.filter(j=>j.status!=="completed").map(j=>({id:j.id,status:j.status,owner_identity:j.owner_process?"recorded":"unverifiable_legacy",failure:j.failure}))};
  }));
  const edit=production.edit,usableFrames=edit?.clips.reduce((n,c)=>n+(c.in_frame>=0 && c.out_frame>c.in_frame && c.out_frame<=(plates.get(c.render_job_id)?.frames??0)?c.out_frame-c.in_frame:0),0)??0;
  const targetValue=(production.authority_snapshot?.locked_contract as Record<string,unknown>|undefined)?.target_frames;
  const target=typeof targetValue==="number"?targetValue:null;
  const summary={version:1,project_id:project.id,project_revision:project.revision,planned_shots:shots.length,rendered_shots:shots.filter(s=>s.render_job_ids.length).length,blocked_shots:shots.filter(s=>s.blocked).map(s=>s.shot_id),shots,edit_revision:production.edit_revision,usable_edit_frames:usableFrames,target_frames:target,remaining_target_frames:target===null?null:Math.max(0,target-usableFrames),assembly:{historical_receipt:production.assembly??null,current_file_verified:production.assembly?await hashFile(production.assembly.path).then(h=>h===production.assembly!.sha256).catch(()=>false):false},completion:{status:"NOT_RECHECKED",historical:production.completion??null},repair_budget:{attempts:production.attempts,no_progress:production.no_progress},recovery:{unfinished_jobs:jobs.filter(j=>j.status!=="completed").map(j=>({id:j.id,status:j.status,owner_identity:j.owner_process?"recorded":"unverifiable_legacy",failure:j.failure})),next_action:"Reuse current finished plates; retry only missing/stale work, then production_check. Never reacquire after human takeover without authorization."}};
  this.summaries.get(project.id);await this.summaries.set({id:project.id,...summary});return summary;
 }
}
