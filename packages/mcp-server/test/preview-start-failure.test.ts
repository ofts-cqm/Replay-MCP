import {mkdtemp,rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {Client} from "@modelcontextprotocol/client";
import {InMemoryTransport} from "@modelcontextprotocol/server";
import {expect,it,vi} from "vitest";
import {buildServer} from "../src/server.js";
import type {BridgeClient} from "../src/bridge/client.js";
import {SidecarError} from "../src/bridge/discovery.js";
import {JobStore} from "../src/jobs/store.js";
import {AuditLog} from "../src/persistence.js";

it("persists native preview-start rejection as failed instead of orphaning a queued job",async()=>{
 const root=await mkdtemp(join(tmpdir(),"preview-rejected-"));
 const built=await buildServer({dataDir:root,gameDirs:[],guessedGameDirs:[],discoveryIntervalMs:60000,command:"serve"});
 const client=new Client({name:"preview-rejection",version:"1"});
 try {
  const pair=InMemoryTransport.createLinkedPair();await built.server.connect(pair[1]);await client.connect(pair[0]);
  vi.spyOn(built.runtime,"client").mockReturnValue({descriptor:{instanceId:"fixture"}} as BridgeClient);
  const message="follow timing is bound to complete plate range and FPS; regenerate or trim in assembly";
  const mutate=vi.spyOn(built.runtime,"mutate").mockRejectedValue(new SidecarError("conflict",message));
  for(const range of [{fps:20,end_us:1000000},{fps:60,end_us:950000}]){
   const result=await client.callTool({name:"replay_preview",arguments:{output_mode:"video",start_us:0,...range}});
   expect(result.isError).toBe(true);expect(JSON.stringify(result)).toContain(message);
  }
  expect(mutate.mock.calls.map(call=>call[0])).toEqual(["render.start","render.start"]);
  const jobs=built.runtime.jobs.list();expect(jobs).toHaveLength(2);
  for(const job of jobs){expect(job.status).toBe("failed");expect(job.failure).toEqual({code:"conflict",message});expect(job.result_artifact_ids).toEqual([]);expect(job.bridge_backed).toBe(false);}
  const observer=new JobStore(root,built.runtime.artifacts,new AuditLog(root),"observer");await observer.load();
  expect(observer.list().map(job=>job.status)).toEqual(["failed","failed"]);
 }finally{await client.close();await built.close();await rm(root,{recursive:true,force:true});}
});
