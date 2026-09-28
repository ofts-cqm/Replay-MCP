import {mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {Client} from "@modelcontextprotocol/client";
import {InMemoryTransport} from "@modelcontextprotocol/server";
import {expect,it,vi} from "vitest";
import {buildServer} from "../src/server.js";
import type {BridgeClient} from "../src/bridge/client.js";

it("resolves interior orbit support at actual start, and rolls back unknown geometry",async()=>{
 const root=await mkdtemp(join(tmpdir(),"preset-tool-"));
 const built=await buildServer({dataDir:root,gameDirs:[],guessedGameDirs:[],discoveryIntervalMs:60000,command:"serve"});
 const client=new Client({name:"camera-test",version:"1"});
 try {
  const pair=InMemoryTransport.createLinkedPair();await built.server.connect(pair[1]);await client.connect(pair[0]);
  const bridge={} as BridgeClient;
  const mutate=vi.spyOn(built.runtime,"mutate").mockImplementation(async(method)=>({client:bridge,result:method==="replay.camera_context"?{x:3,y:2.1,z:0,yaw:0}:method==="timeline.apply"?{revision:"new",undo_token:"undo"}:method==="replay.path_clearance"?{collision_check:"unverified",reason:"unloaded chunk"}:{revision:"old"}}));
  const result=await client.callTool({name:"replay_camera_preset",arguments:{base_revision:"old",parameters:{preset:"orbit",profile:"interior",duration_us:7000000,replay_time_mode:"freeze",replay_start_us:1000000,center:{x:0,y:0,z:0},start:{x:99,y:5,z:99},radius:3}}});
  expect(result.isError).toBe(true);
  expect(mutate.mock.calls[0]![1]).toMatchObject({start:{x:3,y:5,z:0},resolve_support:true,orient_open:false});
  expect(mutate.mock.calls[1]![1]).toMatchObject({base_revision:"old"});
  expect(mutate.mock.calls[3]).toEqual(["timeline.undo",{instance_id:undefined,base_revision:"new",undo_token:"undo"}]);
 } finally {await client.close();await built.close();}
});

it("never applies a sampled follow trajectory lacking native continuous subject evidence",async()=>{
 const root=await mkdtemp(join(tmpdir(),"follow-proof-tool-"));
 const built=await buildServer({dataDir:root,gameDirs:[],guessedGameDirs:[],discoveryIntervalMs:60000,command:"serve"});const client=new Client({name:"follow-test",version:"1"});
 try {
  const pair=InMemoryTransport.createLinkedPair();await built.server.connect(pair[1]);await client.connect(pair[0]);
  const mutate=vi.spyOn(built.runtime,"mutate").mockResolvedValue({client:{} as BridgeClient,result:{samples:[{time_us:2000000,x:0,y:1.6,z:0,yaw:0}],subject_motion_verified:false,subject_motion_reason:"unknown packet history",temporal_history:{blocked_start_us:2100000,blocked_end_us:2200000}}});
  const result=await client.callTool({name:"replay_camera_preset",arguments:{base_revision:"old",parameters:{preset:"follow",player_uuid:"11111111-1111-4111-8111-111111111111",duration_us:3000000,replay_time_mode:"advance_1x",replay_start_us:2000000}}});
  expect(result.isError).toBe(true);expect(mutate.mock.calls.map(c=>c[0])).toEqual(["replay.trajectory"]);
 }finally{await client.close();await built.close();}
});
