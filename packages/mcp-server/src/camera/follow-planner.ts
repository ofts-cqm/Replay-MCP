import {SidecarError} from "../bridge/discovery.js";
import {digest} from "../production/contract.js";
import {generatePreset,type Preset,type TrajectorySample,type Pose} from "./presets.js";
export type Box=[number,number,number,number,number,number];
export interface FollowGeometry {policy:string;verified:boolean;bounds:Box;boxes:Box[];reason?:string}
const xyz=(p:TrajectorySample|Pose)=>[p.x,p.y,p.z];
export function segmentHits(a:number[],b:number[],box:Box,margin=0):boolean {
 let lo=0,hi=1;
 for(let i=0;i<3;i++) {const d=b[i]!-a[i]!,min=box[i]!-margin,max=box[i+3]!+margin;
  if(Math.abs(d)<1e-12){if(a[i]!<min || a[i]!>max)return false;}
  else {let x=(min-a[i]!)/d,y=(max-a[i]!)/d;if(x>y)[x,y]=[y,x];lo=Math.max(lo,x);hi=Math.min(hi,y);if(lo>hi)return false;}
 }return true;
}
const blocked=(reason:string,start:number,end:number,unknown=false):never=>{throw new SidecarError(unknown?"camera_unverified":"camera_blocked",reason,{blocked_start_us:start,blocked_end_us:end,alternatives:["split source interval","use fixed-vantage coverage","explicitly request another horizontal distance range"]});};
/** Conservative continuous connection check: clearance sweep plus the full AABB
 * containing both moving eye rays. False blocks are preferable to sampled passes. */
export function connectionClear(a:Pose,b:Pose,sa:TrajectorySample,sb:TrajectorySample,g:FollowGeometry,p:Preset,budget?:{remaining:number}):boolean {
 const av=xyz(a),bv=xyz(b),aa=xyz(sa),bb=xyz(sb);
 for(let j=0;j<3;j++) if(Math.min(av[j]!,bv[j]!)-.5<g.bounds[j]! || Math.max(av[j]!,bv[j]!)+.5>g.bounds[j+3]!)return false;
 const r=[a.x-sa.x,a.z-sa.z],d=[b.x-sb.x-r[0]!,b.z-sb.z-r[1]!],d2=d[0]!**2+d[1]!**2;
 const t=d2===0?0:Math.max(0,Math.min(1,-(r[0]!*d[0]!+r[1]!*d[1]!)/d2));
 if(Math.hypot(r[0]!+d[0]!*t,r[1]!+d[1]!*t)<p.follow_min_distance-1e-8 || Math.max(Math.hypot(...r),Math.hypot(b.x-sb.x,b.z-sb.z))>p.follow_max_distance+1e-8)return false;
 const hull=[0,1,2].map(i=>Math.min(av[i]!,bv[i]!,aa[i]!,bb[i]!)).concat([0,1,2].map(i=>Math.max(av[i]!,bv[i]!,aa[i]!,bb[i]!))) as Box;
 for(const box of g.boxes) {
  if(budget && --budget.remaining<0)return blocked("follow geometry intersection budget exhausted",sa.time_us,sb.time_us,true);
  if(segmentHits(av,bv,box,.5))return false;
  if([0,1,2].every(i=>hull[i]!<=box[i+3]! && hull[i+3]!>=box[i]!))return false;
 }return true;
}
export function planFollow(p:Preset,samples:TrajectorySample[],g:FollowGeometry) {
 if(!g.verified || g.policy!=="native-follow-context/1")return blocked(g.reason??"native geometry proof missing",p.replay_start_us,p.replay_start_us+p.duration_us,true);
 // Discard shapes outside the complete permissible camera/eye envelope before
 // bounded candidate search (e.g. a floor below every eye and clearance volume).
 const low=[Math.min(...samples.map(s=>s.x))-p.follow_max_distance-.5,Math.min(...samples.map(s=>s.y))-.5,Math.min(...samples.map(s=>s.z))-p.follow_max_distance-.5];
 const high=[Math.max(...samples.map(s=>s.x))+p.follow_max_distance+.5,Math.max(...samples.map(s=>s.y))+p.follow_elevation+.5,Math.max(...samples.map(s=>s.z))+p.follow_max_distance+.5];
 g={...g,boxes:g.boxes.filter(box=>[0,1,2].every(i=>box[i+3]!>=low[i]! && box[i]!<=high[i]!))};
 const budget={remaining:2_000_000};
 const preferred=generatePreset({...p,max_speed:undefined,max_angular_speed:undefined},samples);
 type Node={pose:Pose;cost:number;parent?:Node};let prior:Node[]=[];let edges=0;
 const speed=p.max_speed??12,angular=p.max_angular_speed??90;
 for(let i=0;i<samples.length;i++) {
  const s=samples[i]!,ideal=preferred.poses[i]!,base=Math.atan2(ideal.z-s.z,ideal.x-s.x),dist=Math.hypot(ideal.x-s.x,ideal.z-s.z);
  const nodes:Node[]=[];
  const elevations=[...new Set([p.follow_elevation,Math.min(1,p.follow_elevation),Math.min(.5,p.follow_elevation),0])];
  for(const elevation of elevations) for(const angleDelta of [0,-15,15,-30,30]) for(const distance of [...new Set([dist,(p.follow_min_distance+p.follow_max_distance)/2,p.follow_max_distance])]) {
   const angle=base+angleDelta*Math.PI/180,x=s.x+Math.cos(angle)*distance,z=s.z+Math.sin(angle)*distance,y=s.y+elevation;
   const pose:Pose={time_us:s.time_us-p.replay_start_us,x,y,z,yaw:Math.atan2(x-s.x,s.z-z)*180/Math.PI,pitch:Math.atan2(elevation,distance)*180/Math.PI,roll:0};
   const preference=(elevation-p.follow_elevation)**2*20+angleDelta**2/900+(distance-dist)**2;
   if(!connectionClear(pose,pose,s,s,g,p,budget))continue;
   if(i===0){nodes.push({pose,cost:preference});continue;}
   let best:Node|undefined;
   for(const previous of prior) {
    if(++edges>500_000)return blocked("follow connection budget exhausted",samples[i-1]!.time_us,s.time_us,true);
    const a=previous.pose,dt=(pose.time_us-a.time_us)/1e6;
    let yaw=pose.yaw;while(yaw-a.yaw>180)yaw-=360;while(yaw-a.yaw< -180)yaw+=360;
    const travel=Math.hypot(x-a.x,y-a.y,z-a.z),rotation=Math.hypot(yaw-a.yaw,pose.pitch-a.pitch);
    if(travel>speed*dt+1e-8 || rotation>angular*dt+1e-8)continue;
    if(!connectionClear(a,pose,samples[i-1]!,s,g,p,budget))continue;
    const cost=previous.cost+preference+travel*travel/dt+rotation*rotation/100;
    if(!best || cost<best.cost-1e-10)best={pose:{...pose,yaw},cost,parent:previous};
   }
   if(best)nodes.push(best);
  }
  if(!nodes.length)return blocked("no continuous clear and visible follow route within distance/motion bounds",samples[Math.max(0,i-1)]!.time_us,s.time_us);
  // Stable deterministic beam; bounded search can reject a feasible route, never certify an unchecked one.
  prior=nodes.sort((a,b)=>a.cost-b.cost).slice(0,16);
 }
 const poses:Pose[]=[];let node:Node|undefined=prior[0];while(node){poses.push(node.pose);node=node.parent;}poses.reverse();
 // Deterministic constrained relaxation reduces finite-difference acceleration.
 // Every proposed vertex must retain clearance, visibility, distance and motion limits.
 const motion=(a:Pose,b:Pose)=>{const dt=(b.time_us-a.time_us)/1e6;return Math.hypot(b.x-a.x,b.y-a.y,b.z-a.z)<=speed*dt+1e-8 && Math.hypot(b.yaw-a.yaw,b.pitch-a.pitch)<=angular*dt+1e-8;};
 for(let pass=0;pass<8;pass++)for(let i=1;i<poses.length-1;i++) {
  const old=poses[i]!,a=poses[i-1]!,b=poses[i+1]!,subject=samples[i]!;
  const v={...old,x:(a.x+2*old.x+b.x)/4,y:(a.y+2*old.y+b.y)/4,z:(a.z+2*old.z+b.z)/4};
  if(v.y<subject.y || v.y>subject.y+p.follow_elevation)continue;
  v.yaw=Math.atan2(v.x-subject.x,subject.z-v.z)*180/Math.PI;while(v.yaw-old.yaw>180)v.yaw-=360;while(v.yaw-old.yaw< -180)v.yaw+=360;
  v.pitch=Math.atan2(v.y-subject.y,Math.hypot(v.x-subject.x,v.z-subject.z))*180/Math.PI;
  if(motion(a,v)&&motion(v,b)&&connectionClear(a,v,samples[i-1]!,subject,g,p,budget)&&connectionClear(v,b,subject,samples[i+1]!,g,p,budget))poses[i]=v;
 }
 // Final baked result is rechecked after smoothing, then native full-path validation runs after apply.
 for(let i=1;i<poses.length;i++)if(!connectionClear(poses[i-1]!,poses[i]!,samples[i-1]!,samples[i]!,g,p,budget))return blocked("final follow validation failed",samples[i-1]!.time_us,samples[i]!.time_us);
 const operations=preferred.operations.map(op=>op.track==="replay_time"?op:{...op,keyframes:poses.map(pose=>({time_us:pose.time_us,...(op.track==="camera_position"?{x:pose.x,y:pose.y,z:pose.z,interpolation:"linear"}:{value:op.track==="spectated_entity"?-1:pose[op.track as "yaw"|"pitch"|"roll"]})}))});
 return {...preferred,generator:"follow-candidate-planner/1",poses,operations,path_hash:digest(poses),planner:{edges,smoothing:"8 constrained relaxation passes",visibility:"continuous conservative eye-ray hull for native joined tick envelopes",motion:{max_speed:speed,max_angular_speed:angular},chosen_elevations:poses.map((v,i)=>v.y-samples[i]!.y),geometry_hash:digest(g),subject_model:"native renderer tick envelopes; requires native trace proof and final native path revalidation"}};
}
