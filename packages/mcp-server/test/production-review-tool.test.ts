import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/client";
import { InMemoryTransport } from "@modelcontextprotocol/server";
import { expect, it, vi } from "vitest";
import { buildServer } from "../src/server.js";
import type { BridgeClient } from "../src/bridge/client.js";

it("draft waits by default; status continues the same cursor without drafting or acquiring control", async () => {
  const root=await mkdtemp(join(tmpdir(),"review-tool-"));
  const built=await buildServer({dataDir:root,gameDirs:[],guessedGameDirs:[],discoveryIntervalMs:60_000,command:"serve"});
  const client=new Client({name:"review-test",version:"1"});
  try {
    const pair=InMemoryTransport.createLinkedPair();await built.server.connect(pair[1]);await client.connect(pair[0]);
    const created=await client.callTool({name:"project_create",arguments:{title:"Review test",frame_rate:30,resolution:{width:1280,height:720},output_root:"renders"}});
    const project_id=(created.structuredContent as {project:{id:string}}).project.id;
    const initial={review_submission_version:1,draft_hash:"a",presentation:{state:"visible"}};
    let authority: Record<string,unknown>={...initial,review_submission_sequence:1,review_submission:{draft_hash:"a",sequence:1,approved:false,comment:"Eight shots"},presentation:{state:"closed"}};
    const bridge={call:vi.fn(async()=>authority)} as unknown as BridgeClient;
    const mutate=vi.spyOn(built.runtime,"mutate").mockResolvedValue({result:initial,client:bridge});
    const read=vi.spyOn(built.runtime,"read").mockImplementation(async()=>({result:authority,client:bridge}));
    const acquire=vi.spyOn(built.runtime.leases,"acquire");
    const response=await client.callTool({name:"production_contract_draft",arguments:{project_id,contract:{original_request:"One minute",fps:30,target_frames:1800,width:1280,height:720}}});
    expect(response.isError).not.toBe(true);
    expect(response.structuredContent).toMatchObject({review_wait:{status:"commented",draft_hash:"a",after_submission_sequence:0}});
    expect(mutate).toHaveBeenCalledTimes(1);
    expect(mutate.mock.calls[0]![1]).not.toHaveProperty("wait_timeout_ms");

    authority={...initial,locked_hash:"a",review_submission_sequence:2,review_submission:{draft_hash:"a",sequence:2,approved:true},presentation:{state:"closed"}};
    const resumed=await client.callTool({name:"production_status",arguments:{project_id,wait_timeout_ms:1000,draft_hash:"a",after_submission_sequence:1}});
    expect(resumed.isError).not.toBe(true);
    expect(resumed.structuredContent).toMatchObject({authority:{review_wait:{status:"approved"},locked_hash:"a"}});
    expect(mutate).toHaveBeenCalledTimes(1);expect(acquire).not.toHaveBeenCalled();expect(read).toHaveBeenCalledTimes(1);
    const invalid=await client.callTool({name:"production_status",arguments:{project_id,wait_timeout_ms:1000}});
    expect(invalid.isError).toBe(true);
  } finally { await client.close();await built.close(); }
});

it("completion publishes a real report request and exposes export presentation separately from contract review", async () => {
  const root=await mkdtemp(join(tmpdir(),"export-review-tool-"));
  const built=await buildServer({dataDir:root,gameDirs:[],guessedGameDirs:[],discoveryIntervalMs:60_000,command:"serve"});
  const client=new Client({name:"export-review-test",version:"1"});
  try {
    const pair=InMemoryTransport.createLinkedPair();await built.server.connect(pair[1]);await client.connect(pair[0]);
    const project_id=crypto.randomUUID();
    const bridge={} as BridgeClient;
    const authority={locked_hash:"contract",locked_contract:{original_request:"One minute",fps:30,target_frames:1800,width:1280,height:720}};
    vi.spyOn(built.runtime,"read").mockResolvedValue({result:authority,client:bridge});
    const completion={status:"FAIL" as const,findings:[{rule:"footage.reuse",kind:"failure" as const,message:"225 repeated frames"}],total_frames:1800,editorial_shots:8,reused_frames:225,binding:"export-binding",subjective_criteria:[]};
    vi.spyOn(built.runtime.production,"check").mockResolvedValue(completion);
    vi.spyOn(built.runtime.production,"get").mockReturnValue({id:project_id,storage_version:1,edit_revision:2,attempts:2,no_progress:0,
      assembly:{frames:1800,fps:30,width:1280,height:720,sha256:"artifact-sha",path:"/test/master.mp4",edit_hash:"edit",contract_hash:"contract",assembler:"replay-mcp-cuts/1"}});
    const mutate=vi.spyOn(built.runtime,"mutate").mockResolvedValue({client:bridge,result:{...authority,presentation:{state:"closed"},export_presentation:{project_id,binding:"export-binding",state:"visible"}}});
    const result=await client.callTool({name:"production_check",arguments:{project_id}});
    expect(result.isError).not.toBe(true);
    expect(result.structuredContent).toMatchObject({completion:{status:"FAIL"},export_presentation:{project_id,binding:"export-binding",state:"visible"}});
    expect(mutate).toHaveBeenCalledExactlyOnceWith("production.report",{project_id,report:{...completion,contract_hash:"contract",artifact_sha256:"artifact-sha",artifact_path:"/test/master.mp4",edit_revision:2}});
    mutate.mockResolvedValue({client:bridge,result:authority});
    const legacy=await client.callTool({name:"production_check",arguments:{project_id}});
    expect(legacy.structuredContent).toMatchObject({export_presentation:{state:"unsupported"}});
  } finally { await client.close();await built.close(); }
});
