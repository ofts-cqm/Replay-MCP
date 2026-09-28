import {describe,it,expect} from "vitest";
import {generatePreset,presetSchema} from "../src/camera/presets.js";
const base={preset:"slide",duration_us:7_000_000,replay_time_mode:"freeze",replay_start_us:1_000_000,start:{x:0,y:1.6,z:0}};
describe("camera presets",()=>{
 it("preserves authoritative duration and explicit replay policy",()=>{
  const result=generatePreset(presetSchema.parse({...base,distance:7}));expect(result.poses.at(-1)).toMatchObject({time_us:7_000_000,x:7,y:1.6});expect(result.operations.at(-1)).toMatchObject({keyframes:[{time_us:0,replay_time_us:1_000_000},{time_us:7_000_000,replay_time_us:1_000_000}]});
  expect(()=>generatePreset(presetSchema.parse({...base,distance:70,max_speed:1}))).toThrow(/duration remains authoritative/);
  expect(()=>presetSchema.parse({...base,replay_time_mode:undefined})).toThrow();
 });
 it("separates pan position from orbital movement and includes roll-free linear baking",()=>{
  const pan=generatePreset(presetSchema.parse({...base,preset:"pan",sweep_degrees:60}));expect(pan.poses.at(-1)).toMatchObject({x:0,y:1.6,z:0,yaw:60});
  const orbit=generatePreset(presetSchema.parse({...base,preset:"orbit",center:{x:0,y:2,z:0},radius:10,sweep_degrees:90}));expect(orbit.poses[0]).toMatchObject({x:10,y:2,z:0});expect(orbit.poses.at(-1)!.z).toBeCloseTo(10);expect(orbit.poses.every(x=>x.roll===0)).toBe(true);
 });
 it("repeats seeded follow, stays 3–5 blocks horizontally and eye-relative, tolerates stops, rejects teleport",()=>{
  const samples=Array.from({length:21},(_,i)=>({time_us:2_000_000+i*100000,x:i<10?i*.1:1,y:1.62,z:0,yaw:90}));
  const p=presetSchema.parse({...base,preset:"follow",replay_start_us:2_000_000,duration_us:2_000_000,replay_time_mode:"advance_1x",seed:13});
  const first=generatePreset(p,samples);expect(generatePreset(p,samples)).toEqual(first);
  for(const [i,pose] of first.poses.entries()){const target=samples[i]!;const horizontal=Math.hypot(pose.x-target.x,pose.z-target.z);expect(horizontal).toBeGreaterThanOrEqual(3);expect(horizontal).toBeLessThanOrEqual(5);expect(pose.y-target.y).toBeCloseTo(1.5);}
  expect(first.elevation_reference).toBe("player_eye");samples[10]!.x=100;expect(()=>generatePreset(p,samples)).toThrow(/teleporting/);
 });
});

it("preserves all six preset durations, negative travel, and explicit orientation",()=>{
 for(const preset of ["static","slide","rise","push","pan","orbit"] as const) {
  const result=generatePreset(presetSchema.parse({...base,preset,center:{x:0,y:2,z:0},distance:-2}));
  expect(result.poses[0]!.time_us).toBe(0);expect(result.poses.at(-1)!.time_us).toBe(base.duration_us);
 }
 const pull=generatePreset(presetSchema.parse({...base,preset:"push",yaw:90,distance:-2}));
 expect(pull.poses.at(-1)!.x).toBeCloseTo(2);expect(pull.poses.at(-1)!.z).toBeCloseTo(0);
 const fall=generatePreset(presetSchema.parse({...base,preset:"rise",distance:-1}));
 expect(fall.poses.at(-1)!.y).toBeCloseTo(.6);
});
it("rejects silent creative conflicts and impossible angular/timing constraints",()=>{
 expect(()=>generatePreset(presetSchema.parse({...base,preset:"pan",aim:{x:10,y:1,z:0}}))).toThrow(/conflict/);
 expect(()=>generatePreset(presetSchema.parse({...base,preset:"orbit",center:{x:0,y:0,z:0},yaw:20}))).toThrow(/remove yaw/);
 expect(()=>generatePreset(presetSchema.parse({...base,preset:"slide",direction:{x:1,y:1,z:0}}))).toThrow(/horizontal/);
 expect(()=>generatePreset(presetSchema.parse({...base,preset:"pan",sweep_degrees:90,max_angular_speed:1}))).toThrow(/angular speed/);
 expect(()=>generatePreset(presetSchema.parse({...base,preset:"orbit",center:{x:0,y:0,z:0},duration_us:1000}))).toThrow(/duplicate sample/);
});

it("rejects finite inputs whose generated native pose overflows",()=>{
 expect(()=>generatePreset(presetSchema.parse({...base,start:{x:3e7,y:1,z:0},distance:100}))).toThrow(/outside native/);
 expect(()=>generatePreset(presetSchema.parse({...base,direction:{x:1e308,y:0,z:1e308},distance:1e308}))).toThrow();
});
