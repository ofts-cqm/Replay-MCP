import {it,expect} from "vitest";
import {mkdtemp,writeFile,rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {execFileSync} from "node:child_process";
import {ArtifactStore} from "../src/artifacts/store.js";
import {JobStore} from "../src/jobs/store.js";
import {AuditLog} from "../src/persistence.js";
import {ProductionStore} from "../src/production/store.js";
import {contractSchema,digest} from "../src/production/contract.js";
it("assembles actual frame trims, checks output, recovers receipts, rejects preview and tampered media",async()=>{
 const root=await mkdtemp(join(tmpdir(),"replay-production-"));
 try {
  const artifacts=new ArtifactStore(root);await artifacts.load();const jobs=new JobStore(root,artifacts,new AuditLog(root),"test");await jobs.load();const store=new ProductionStore(root,artifacts,jobs);await store.load();
  const file=join(artifacts.localRoot,"plate.mp4");execFileSync("ffmpeg",["-v","error","-f","lavfi","-i","testsrc2=size=64x64:rate=10:duration=3","-c:v","libx264","-pix_fmt","yuv420p",file]);
  const artifact=await artifacts.registerLocal(file,"video/mp4");
  const job=await jobs.create("render",{project_id:"project",status:"completed",bridge_backed:true,result_artifact_ids:[artifact.id],result:{complete:true,render_receipt:{version:1,certifiable_projection:true,quality:"high_quality",replay_sha256:"source",native_path_hash:"camera",fps:10,width:64,height:64,start_us:0,collision:"verified",clearance_evidence:{policy:"native-linear-frozen-sweep/3",collision_check:"verified"},frame_identities:Array.from({length:30},(_,i)=>digest(i))}}});
  await store.setEdit("project",0,{clips:[{render_job_id:job.id,in_frame:5,out_frame:25}]});
  const c=contractSchema.parse({original_request:"two seconds",fps:10,target_frames:20,width:64,height:64}),hash=digest(c);
  expect((await store.check("project",c,hash,true)).status).toBe("INCOMPLETE");
  const assembled=await store.assemble("project",c,hash);expect(assembled.assembly?.frames).toBe(20);expect((await store.check("project",c,hash,true)).status).toBe("PASS");
  const recovered=new ProductionStore(root,artifacts,jobs);await recovered.load();expect((await recovered.check("project",c,hash,true)).status).toBe("PASS");
  await writeFile(assembled.assembly!.path,"tampered");expect((await recovered.check("project",c,hash,true)).status).toBe("INCOMPLETE");
  await jobs.update(job.id,{result:{...job.result,render_receipt:{...(job.result!.render_receipt as object),quality:"preview"}}});expect((await store.plates("project",store.get("project").edit!)).plates.size).toBe(0);
 } finally {await rm(root,{recursive:true,force:true});}
},30000);
