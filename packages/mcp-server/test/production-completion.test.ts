import {describe,it,expect} from "vitest";
import {checkCompletion,contractSchema,digest,editSchema,type Edit,type Plate,type Contract} from "../src/production/contract.js";
const contract=contractSchema.parse({original_request:"Three minute unique film",fps:30,target_frames:5400,width:1920,height:1080});
const plate=(id:string,lineage=id,frames=300):Plate=>({job_id:id,artifact_id:id,sha256:id,path:id,frames,fps:30,width:1920,height:1080,lineage,start_frame:0,collision:"verified"});
function check(c:Contract,edit:Edit,plates:Map<string,Plate>,assembly=true) {
 return checkCompletion(c,edit,plates,{locked:true,contract_hash:digest(c),...(assembly?{assembly:{frames:edit.clips.reduce((n,x)=>n+x.out_frame-x.in_frame,0),fps:30,width:1920,height:1080,sha256:"film",contract_hash:digest(c),edit_hash:digest(edit)}}:{})});
}
function film(seconds:number,shotSeconds=6) { const count=seconds/shotSeconds,plates=new Map<string,Plate>(),clips:Edit["clips"]=[];for(let i=0;i<count;i++){const id=String(i);plates.set(id,plate(id,id,shotSeconds*30));clips.push({render_job_id:id,in_frame:0,out_frame:shotSeconds*30});}return {plates,edit:{clips}}; }
describe("production mechanical completion",()=>{
 it("defaults to 144–216 seconds, hard 1–15 second shots, pacing only advisory, no count limit",()=>{
  expect(contract).toMatchObject({runtime_tolerance:.2,min_shot_frames:30,max_shot_frames:450,preferred_min_frames:90,preferred_max_frames:300,reuse_budget_frames:0});
  expect(contract.min_shots).toBeUndefined();
  for(const seconds of [144,180,216]) {const f=film(seconds);expect(check(contract,f.edit,f.plates).status).toBe("PASS");}
  for(const seconds of [138,222]) {const f=film(seconds);expect(check(contract,f.edit,f.plates).findings.some(x=>x.rule==="runtime.bounds")).toBe(true);}
  const f=film(180,12);const result=check(contract,f.edit,f.plates);expect(result.status).toBe("PASS");expect(result.findings.every(x=>x.kind==="advisory")).toBe(true);
 });
 it("fails correct-runtime film padded by renamed, re-encoded, split repeated clips",()=>{
  const f=film(180);for(let i=8;i<30;i++) f.plates.set(String(i),{...plate(String(i),String(6+i%2),180),sha256:`reencoded-${i}`});
  const result=check(contract,f.edit,f.plates);expect(result.total_frames).toBe(5400);expect(result.status).toBe("FAIL");expect(result.reused_frames).toBe(22*180);
  f.edit.clips=f.edit.clips.flatMap(c=>[{...c,out_frame:90},{...c,in_frame:90}]);expect(check(contract,f.edit,f.plates).reused_frames).toBe(result.reused_frames);
 });
 it("does not inflate distinct editorial shots by adjacent trims and applies the combined hard bound",()=>{
  const p=plate("one","camera",600),edit={clips:[{render_job_id:"one",in_frame:0,out_frame:300},{render_job_id:"one",in_frame:300,out_frame:600}]};
  const result=check({...contract,target_frames:600},edit,new Map([["one",p]]));expect(result.editorial_shots).toBe(1);expect(result.findings.some(x=>x.rule==="shot.bounds")).toBe(true);
 });
 it("detects independently baked exact frame reuse while allowing different frozen camera views",()=>{
  const a={...plate("a","path-a",180),frame_identities:Array(180).fill("view-a")},b={...plate("b","path-b",180),frame_identities:Array(180).fill("view-a")};
  const edit={clips:[{render_job_id:"a",in_frame:0,out_frame:180},{render_job_id:"b",in_frame:0,out_frame:180}]};
  expect(check({...contract,target_frames:360},edit,new Map([["a",a],["b",b]])).reused_frames).toBe(180);
  b.frame_identities.fill("view-b");expect(check({...contract,target_frames:360},edit,new Map([["a",a],["b",b]])).status).toBe("PASS");
 });
 it("missing, stale, skipped required collision and unsupported semantic evidence never pass",()=>{
  const f=film(180);expect(check(contract,f.edit,f.plates,false).status).toBe("INCOMPLETE");
  f.plates.get("0")!.collision="skipped";expect(check({...contract,require_collision:true},f.edit,f.plates).status).toBe("INCOMPLETE");
  expect(check({...contract,mechanical_requirements:["show every room"]},f.edit,f.plates).status).toBe("INCOMPLETE");
  const result=checkCompletion(contract,f.edit,f.plates,{locked:false,contract_hash:"new",assembly:{frames:5400,fps:30,width:1920,height:1080,sha256:"film",edit_hash:"old",contract_hash:"old"}});expect(result.status).toBe("INCOMPLETE");expect(result.findings.map(x=>x.rule)).toContain("assembly.stale");
 });
 it("rejects hidden override fields and unsupported edit commands",()=>{
  expect(()=>contractSchema.parse({...contract,override:true})).toThrow();expect(()=>editSchema.parse({clips:[],command:"ffmpeg"})).toThrow();
 });
});
