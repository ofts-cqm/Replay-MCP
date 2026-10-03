import {it,expect} from "vitest";
import {mkdtemp,rm,writeFile} from "node:fs/promises";
import {tmpdir,hostname} from "node:os";
import {join} from "node:path";
import {ArtifactStore} from "../src/artifacts/store.js";
import {JobStore} from "../src/jobs/store.js";
import {AuditLog} from "../src/persistence.js";
import {ProductionStore} from "../src/production/store.js";
import {ProgressStore,shotBinding} from "../src/production/progress.js";
import {ProjectStore} from "../src/projects/store.js";
it("a new observer preserves live-owner jobs; proven dead owners interrupt without completing partial media",async()=>{
 const root=await mkdtemp(join(tmpdir(),"jobs-recovery-"));try{
 const artifacts=new ArtifactStore(root);await artifacts.load();const audit=new AuditLog(root),one=new JobStore(root,artifacts,audit,"one");await one.load();
 const live=await one.create("render",{status:"running"});const dead=await one.create("render",{status:"running"});
 await one.update(dead.id,{owner_process:{pid:2147483647,host:hostname(),start:"dead"},result_artifact_ids:["partial"]});
 const two=new JobStore(root,artifacts,audit,"two");await two.load();expect(two.get(live.id)?.status).toBe("running");expect(two.get(dead.id)?.status).toBe("failed");expect(two.get(dead.id)?.failure?.code).toBe("sidecar_restarted");
 await one.update(live.id,{status:"completed"});expect(two.get(live.id)?.status).toBe("completed");
 }finally{await rm(root,{recursive:true,force:true});}
});
it("recovery derives separate evidence from persisted state; changing one plan keeps the other checkpoint",async()=>{
 const root=await mkdtemp(join(tmpdir(),"progress-recovery-"));try{
 const artifacts=new ArtifactStore(root);await artifacts.load();const jobs=new JobStore(root,artifacts,new AuditLog(root),"one");await jobs.load();const prod=new ProductionStore(root,artifacts,jobs);await prod.load();const projects=new ProjectStore(root,artifacts);await projects.load();
 let project=await projects.create({title:"fixture"});project.scenes=[{id:"scene",shots:[{id:"a",duration_us:3000000},{id:"b",duration_us:3000000}]}];
 const progress=new ProgressStore(root,artifacts,jobs,prod);await progress.load();const path=join(artifacts.localRoot,"path.json");await writeFile(path,"{}");const artifact=await artifacts.registerLocal(path,"application/json");
 for(const shot of ["a","b"])await progress.generated(project,shot,artifact.id,"revision",{native_path_hash:"native",collision_check:"verified",policy:"native-linear-frozen-sweep/3"});
 const preview=await jobs.create("preview",{...shotBinding(project,"a"),status:"completed",result_artifact_ids:[artifact.id],result:{timeline_revision:"revision"}});await progress.visual(project,"a",preview.id,"accepted","fixture visual finding");
 let summary=await progress.summary(project);expect(summary.planned_shots).toBe(2);expect(summary.rendered_shots).toBe(0);expect(summary.shots[0]?.visual_review?.outcome).toBe("accepted");expect(summary.completion.status).toBe("NOT_RECHECKED");
 (project.scenes[0]!.shots as Record<string,unknown>[])[0]!.duration_us=4000000;
 const restarted=new ProgressStore(root,artifacts,jobs,prod);await restarted.load();summary=await restarted.summary(project);expect(summary.shots[0]?.generation).toBeNull();expect(summary.shots[0]?.visual_review).toBeNull();expect(summary.shots[1]?.generation).not.toBeNull();
 await writeFile(path,"tampered");expect((await restarted.summary(project)).shots[1]?.generation).toBeNull();
 }finally{await rm(root,{recursive:true,force:true});}
});

it("invalidates inherited source and output context but preserves unrelated siblings and notes",async()=>{
 const root=await mkdtemp(join(tmpdir(),"progress-context-"));try{
 const artifacts=new ArtifactStore(root);await artifacts.load();const jobs=new JobStore(root,artifacts,new AuditLog(root),"test");await jobs.load();const prod=new ProductionStore(root,artifacts,jobs);await prod.load();const projects=new ProjectStore(root,artifacts);await projects.load();
 const project=await projects.create({title:"context"});
 const take={id:"t",replay_sha256:"source-a",accepted_clips:[{replay_in_us:0,replay_out_us:3000000}],shots:[{id:"a",duration_us:3000000}]};
 project.scenes=[{id:"s",takes:[take,{id:"other",replay_sha256:"other-source",shots:[{id:"b",duration_us:3000000}]}]}];
 const progress=new ProgressStore(root,artifacts,jobs,prod);await progress.load();const path=join(artifacts.localRoot,"path.json");await writeFile(path,"{}");const artifact=await artifacts.registerLocal(path,"application/json");
 for(const shot of ["a","b"]){await progress.generated(project,shot,artifact.id,"revision",{native_path_hash:"native"});const preview=await jobs.create("preview",{...shotBinding(project,shot),status:"completed",result_artifact_ids:[artifact.id],result:{timeline_revision:"revision"}});await progress.visual(project,shot,preview.id,"accepted","fixture");}
 const before=shotBinding(project,"a").plan_hash;
 project.notes={comment:"editorial only"};project.scenes[0]!.notes={comment:"unrelated notes"};expect(shotBinding(project,"a").plan_hash).toBe(before);
 take.replay_sha256="source-b";
 const restarted=new ProgressStore(root,artifacts,jobs,prod);await restarted.load();let summary=await restarted.summary(project);
 expect(summary.shots[0]?.generation).toBeNull();expect(summary.shots[0]?.visual_review).toBeNull();expect(summary.shots[1]?.generation).not.toBeNull();expect(summary.shots[1]?.visual_review).not.toBeNull();
 take.replay_sha256="source-a";take.accepted_clips[0]!.replay_in_us=1000;expect(shotBinding(project,"a").plan_hash).not.toBe(before);
 const b=shotBinding(project,"b").plan_hash;const originalFps=project.frame_rate;project.frame_rate=originalFps===60?30:60;expect(shotBinding(project,"b").plan_hash).not.toBe(b);
 project.frame_rate=originalFps;project.resolution.width+=16;expect(shotBinding(project,"b").plan_hash).not.toBe(b);
 summary=await restarted.summary(project);expect(summary.shots.every(s=>s.generation===null&&s.visual_review===null)).toBe(true);
 }finally{await rm(root,{recursive:true,force:true});}
});
