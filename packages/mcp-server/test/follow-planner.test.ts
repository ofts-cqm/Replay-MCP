import {it,expect} from "vitest";
import {presetSchema,type Pose} from "../src/camera/presets.js";
import {planFollow,connectionClear,type FollowGeometry} from "../src/camera/follow-planner.js";
const input=presetSchema.parse({preset:"follow",duration_us:2_000_000,replay_start_us:2_000_000,replay_time_mode:"advance_1x",seed:123});
const samples=Array.from({length:21},(_,i)=>({time_us:2_000_000+i*100000,x:i*.05,y:1.62,z:0,yaw:0}));
const world:FollowGeometry={policy:"native-follow-context/1",verified:true,bounds:[-20,-5,-20,20,10,20],boxes:[]};
it("plans deterministic bounded follow on continuous synthetic subject model and lowers under ceilings",()=>{
 const a=planFollow(input,samples,world);expect(planFollow(input,samples,world)).toEqual(a);
 expect(a.poses[0]!.time_us).toBe(0);expect(a.poses.at(-1)!.time_us).toBe(2_000_000);
 for(let i=1;i<a.poses.length;i++)expect(connectionClear(a.poses[i-1]!,a.poses[i]!,samples[i-1]!,samples[i]!,world,input)).toBe(true);
 const ceiling={...world,boxes:[[-20,2.8,-20,20,3,20]] as FollowGeometry["boxes"]};
 const low=planFollow(input,samples,ceiling);expect(low.poses.every(p=>p.y<=2.3+1e-8)).toBe(true);expect(low.planner.chosen_elevations.some(y=>y<1)).toBe(true);
});
it("blocks an impossible corridor, unknown geometry and intermediate wall crossings",()=>{
 const walls={...world,boxes:[[-20,-5,-20,20,10,20]] as FollowGeometry["boxes"]};
 expect(()=>planFollow(input,samples,walls)).toThrow(/no continuous/);
 expect(()=>planFollow(input,samples,{...world,verified:false})).toThrow(/proof/);
 const a:Pose={time_us:0,x:-4,y:3,z:0,yaw:0,pitch:0,roll:0},b={...a,time_us:1e6,x:4};
 expect(connectionClear(a,b,{...samples[0]!,x:-4,z:4},{...samples[0]!,x:4,z:4},{...world,boxes:[[-.1,2,-1,.1,4,1]]},input)).toBe(false);
});
it("preserves heading at stops despite head turns and reports teleport intervals",()=>{
 const stopped=samples.map(s=>({...s,x:0,yaw:s.time_us%360}));expect(planFollow(input,stopped,world).poses).toHaveLength(21);
 const jump=samples.map(s=>({...s}));jump[5]!.x=99;expect(()=>planFollow(input,jump,world)).toThrow(/teleporting/);
});
