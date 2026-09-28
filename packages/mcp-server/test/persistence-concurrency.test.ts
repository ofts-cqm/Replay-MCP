import {mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {expect,it} from "vitest";
import {JsonCollection} from "../src/persistence.js";

it("independent stale writers preserve each other's records and refresh readers",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"collection-writers-"));
 const a=new JsonCollection<{id:string;value:number}>(dir,"index.json"),b=new JsonCollection<{id:string;value:number}>(dir,"index.json");
 await a.load();await b.load();
 await Promise.all([a.set({id:"render",value:1}),b.set({id:"screenshot",value:2})]);
 expect(a.values().map(x=>x.id).sort()).toEqual(["render","screenshot"]);
 expect(b.get("render")).toEqual({id:"render",value:1});
});
it("rejects stale same-record writes without poisoning later saves",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"collection-conflict-"));
 const a=new JsonCollection<{id:string;value:number}>(dir,"index.json"),b=new JsonCollection<{id:string;value:number}>(dir,"index.json");
 await a.load();await a.set({id:"production",value:1});await b.load();
 await a.set({id:"production",value:2});
 await expect(b.set({id:"production",value:0})).rejects.toThrow("persistent record changed");
 expect(b.get("production")?.value).toBe(2);
 await b.set({id:"production",value:3});expect(a.get("production")?.value).toBe(3);
});
