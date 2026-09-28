import { z } from "zod";
import { SidecarError } from "../bridge/discovery.js";
import { digest } from "../production/contract.js";
const vec = z.object({x:z.number().finite(),y:z.number().finite(),z:z.number().finite()}).strict();
export const presetSchema = z.object({
  preset:z.enum(["static","slide","rise","push","pan","orbit","follow"]), profile:z.enum(["interior","exterior"]).default("exterior"),
  duration_us:z.number().int().min(1000).max(60_000_000).refine(n=>n%1000===0,"duration must be millisecond aligned"),
  replay_end_us:z.number().int().nonnegative().optional().describe("Optional explicit source end; follow requires end = start + duration"),
  replay_time_mode:z.enum(["freeze","advance_1x"]), replay_start_us:z.number().int().nonnegative().refine(n=>n%1000===0,"source time must be millisecond aligned"),
  interior_height_mode:z.enum(["support_plus_1_6","explicit"]).default("support_plus_1_6"),
  start:vec.optional(), aim:vec.optional(), yaw:z.number().finite().optional(), pitch:z.number().min(-90).max(90).default(0),
  direction:vec.optional(), distance:z.number().finite().optional(), sweep_degrees:z.number().finite().optional(), tilt_degrees:z.number().finite().default(0),
  center:vec.optional(), radius:z.number().positive().max(256).optional(), height:z.number().finite().optional(), start_angle_degrees:z.number().finite().default(0),
  max_speed:z.number().positive().optional(), max_angular_speed:z.number().positive().optional(),
  player_uuid:z.uuid().optional(), seed:z.number().int().min(0).max(0xffffffff).default(0),
  follow_min_distance:z.number().min(.5).max(32).default(3), follow_max_distance:z.number().min(.5).max(32).default(5),
  follow_fps:z.number().int().min(20).max(120).refine(n=>n%20===0,"follow FPS must be a multiple of 20").default(60),
  follow_elevation:z.number().min(0).max(2).default(1.5),
  skip_collision_check:z.boolean().default(false),
}).strict().superRefine((p,ctx)=>{
  if(p.preset==="follow" && (p.duration_us>50_000_000 || p.duration_us%50_000!==0 || p.replay_start_us<=1_000_000))ctx.addIssue({code:"custom",message:"follow requires source start >1s and a 50ms-aligned duration <=50s (1000 native tick segments)"});
});
export type Preset = z.infer<typeof presetSchema>;
export interface TrajectorySample {time_us:number;x:number;y:number;z:number;yaw:number}
export interface Pose {time_us:number;x:number;y:number;z:number;yaw:number;pitch:number;roll:number}
const rad = Math.PI/180;
const fail = (message:string):never => { throw new SidecarError("camera_conflict",message); };
function look(p:{x:number;y:number;z:number},t:{x:number;y:number;z:number}):{yaw:number;pitch:number} {
  const dx=t.x-p.x,dy=t.y-p.y,dz=t.z-p.z;
  if (Math.hypot(dx,dy,dz)<1e-6) return fail("aim coincides with camera");
  return {yaw:Math.atan2(-dx,dz)/rad,pitch:-Math.atan2(dy,Math.hypot(dx,dz))/rad};
}
/** Piecewise-linear baked path; orbit is a polygon with <= 2 degree angular steps. */
export function generatePreset(input:Preset, trajectory?:TrajectorySample[]) {
  const p=input, poses:Pose[]=[];
  if(p.replay_end_us!==undefined && p.replay_end_us!==p.replay_start_us+(p.replay_time_mode==="freeze"?0:p.duration_us)) fail("source end conflicts with start, duration and replay-time mode");
  if(p.preset==="pan" && p.aim) fail("pan/tilt and fixed aim conflict; use yaw/pitch as the starting orientation or choose static tracking");
  if(p.preset==="orbit" && (p.yaw!==undefined || p.direction)) fail("orbit aims at center/aim; remove yaw/direction or use manual timeline editing");
  if (p.follow_min_distance>p.follow_max_distance) fail("follow minimum exceeds maximum");
  if (p.preset==="follow" && p.replay_time_mode!=="advance_1x") fail("follow requires explicit advance_1x; replay speed is never silently changed");
  const start=p.start ?? (p.preset==="orbit" || p.preset==="follow" ? {x:0,y:0,z:0} : fail("start position required; interior support resolution happens before generation"));
  const count=p.preset==="pan" ? Math.max(1,Math.ceil(Math.abs(p.sweep_degrees ?? 60)/45),Math.ceil(Math.abs(p.tilt_degrees)/45)) : p.preset==="orbit" ? Math.max(2,Math.ceil(Math.abs(p.sweep_degrees ?? 90)/2)) : p.preset==="follow" ? (trajectory?.length ?? 0)-1 : p.aim ? Math.max(2,Math.ceil(p.duration_us/100000)) : 1;
  if (count<1 || count>1000) fail("trajectory missing or subdivision budget exceeded");
  let heading=(trajectory?.[0]?.yaw ?? p.yaw ?? 0)*rad;
  if(p.preset==="follow" && trajectory) {
    const first=trajectory[0]!;const moving=trajectory.find(v=>Math.hypot(v.x-first.x,v.z-first.z)>.025);
    if(moving)heading=Math.atan2(-(moving.x-first.x),moving.z-first.z);
  }
  const phase=((p.seed*1664525+1013904223)>>>0)/4294967296*Math.PI*2;
  for(let i=0;i<=count;i++) {
    const t=i/count, time=p.preset==="follow" ? trajectory![i]!.time_us-p.replay_start_us : Math.round(p.duration_us*t/1000)*1000;
    let position={...start}, orientation={yaw:p.yaw ?? 0,pitch:p.pitch};
    if(p.preset==="slide" || p.preset==="push") {
      const d=p.direction ?? (p.preset==="push" ? {x:-Math.sin((p.yaw ?? 0)*rad)*Math.cos(p.pitch*rad),y:-Math.sin(p.pitch*rad),z:Math.cos((p.yaw ?? 0)*rad)*Math.cos(p.pitch*rad)} : {x:1,y:0,z:0}); if(p.preset==="slide" && d.y!==0) fail("horizontal slide requires direction.y=0");
      const length=Math.hypot(d.x,d.y,d.z); if(length===0) fail("movement direction must be nonzero");
      const distance=p.distance ?? (p.profile==="interior" ? 2 : 8);
      position={x:start.x+d.x/length*distance*t,y:start.y+d.y/length*distance*t,z:start.z+d.z/length*distance*t};
    } else if(p.preset==="rise") position.y+=(p.distance ?? (p.profile==="interior" ? 1 : 5))*t;
    else if(p.preset==="pan") orientation={yaw:(p.yaw ?? 0)+(p.sweep_degrees ?? (p.profile==="interior"?30:60))*t,pitch:p.pitch+p.tilt_degrees*t};
    else if(p.preset==="orbit") {
      if(!p.center) fail("orbit requires center");
      const angle=(p.start_angle_degrees+(p.sweep_degrees ?? 90)*t)*rad,r=p.radius ?? (p.profile==="interior"?3:12);
      position={x:p.center!.x+Math.cos(angle)*r,y:p.center!.y+(p.height ?? 0),z:p.center!.z+Math.sin(angle)*r};
      orientation=look(position,p.aim ?? p.center!);
    } else if(p.preset==="follow") {
      const subject=trajectory![i]!;
      if(i>0) {
        const prev=trajectory![i-1]!,distance=Math.hypot(subject.x-prev.x,subject.y-prev.y,subject.z-prev.z),dt=(subject.time_us-prev.time_us)/1e6;
        if(dt<=0 || distance>Math.max(8,dt*25)) fail(`missing/teleporting player interval at ${subject.time_us}; split coverage`);
        const dx=subject.x-prev.x,dz=subject.z-prev.z;
        if(Math.hypot(dx,dz)>.025) { const target=Math.atan2(-dx,dz),difference=Math.atan2(Math.sin(target-heading),Math.cos(target-heading)); heading+=Math.max(-dt*rad*45,Math.min(dt*rad*45,difference)); }
      }
      const seconds=time/1e6, distance=p.follow_min_distance+(p.follow_max_distance-p.follow_min_distance)*(.5+.5*Math.sin(phase+seconds*.15));
      const angle=heading+Math.PI+Math.sin(phase+seconds*.12)*35*rad;
      position={x:subject.x-Math.sin(angle)*distance,y:subject.y+p.follow_elevation,z:subject.z+Math.cos(angle)*distance}; orientation=look(position,subject);
    }
    if(p.aim && p.preset!=="orbit" && p.preset!=="follow") orientation=look(position,p.aim);
    const previous=poses.at(-1);
    if(previous) { while(orientation.yaw-previous.yaw>180) orientation.yaw-=360; while(orientation.yaw-previous.yaw< -180) orientation.yaw+=360; }
    if(orientation.pitch< -90 || orientation.pitch>90) fail("tilt exceeds pitch bounds");
    const pose={time_us:time,...position,...orientation,roll:0};
    if(!Object.values(pose).every(Number.isFinite) || [pose.x,pose.y,pose.z].some(v=>Math.abs(v)>30_000_000) || Math.abs(pose.yaw)>3.4e38)
      fail("generated pose is nonfinite or outside native world/rotation bounds; reduce coordinates, direction or angles");
    if(previous) {
      const dt=(time-previous.time_us)/1e6;if(dt<=0) fail("duplicate sample times; shorten subdivision or increase duration");
      const speed=Math.hypot(pose.x-previous.x,pose.y-previous.y,pose.z-previous.z)/dt;
      const angular=Math.hypot(pose.yaw-previous.yaw,pose.pitch-previous.pitch)/dt;
      if(p.max_speed!==undefined && speed>p.max_speed+1e-6) fail(`speed ${speed.toFixed(3)} exceeds ${p.max_speed}; duration remains authoritative`);
      if(p.max_angular_speed!==undefined && angular>p.max_angular_speed+1e-6) fail(`angular speed ${angular.toFixed(3)} exceeds ${p.max_angular_speed}`);
    }
    poses.push(pose);
  }
  if(poses.at(-1)!.time_us!==p.duration_us) fail("trajectory source range does not match duration");
  const operations:Record<string,unknown>[]=["camera_position","yaw","pitch","roll","spectated_entity"].map(track=>({op:"replace_track",track,keyframes:poses.map(pose=>({time_us:pose.time_us,...(track==="camera_position"?{x:pose.x,y:pose.y,z:pose.z,interpolation:"linear"}:{value:track==="spectated_entity"?-1:pose[track as "yaw"|"pitch"|"roll"]})}))}));
  operations.push({op:"replace_track",track:"replay_time",keyframes:[{time_us:0,replay_time_us:p.replay_start_us},{time_us:p.duration_us,replay_time_us:p.replay_start_us+(p.replay_time_mode==="freeze"?0:p.duration_us)}]});
  return {generator:"camera-presets/3",parameters:p,elevation_reference:"player_eye",poses,operations,path_hash:digest(poses),collision_check:p.skip_collision_check?"skipped":"unverified"};
}
