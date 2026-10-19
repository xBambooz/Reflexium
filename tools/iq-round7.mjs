import fs from "node:fs/promises";
import path from "node:path";
import {dispatch} from "file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs";
const root=path.resolve(import.meta.dirname,".."), out=path.join(root,"build/validation/round7");
async function tool(name,args){const r=await dispatch({jsonrpc:"2.0",id:1,method:"tools/call",params:{name,arguments:args}});const c=r.result?.content??r.content;const v=JSON.parse(c.find(x=>x.type==="text").text);if(r.result?.isError||!v.ok&&name!=="mc_launch")throw Error(JSON.stringify(v));return v;}
async function call(endpoint,params={}){const s=JSON.parse((await fs.readFile("C:/Users/Ryan/source/repos/mc-agent-harness/instances/25626/session.json","utf8")).replace(/^\uFEFF/,""));if(path.resolve(s.game_directory)!==path.join(root,"run"))throw Error("Wrong owned directory");return tool("mc_call",{port:25626,endpoint,params});}
const set=(key,value)=>call("reflexium",{key,value:String(value)});
const mode=process.argv[2];
if(mode==="launch")console.log(JSON.stringify(await tool("mc_launch",{project:root,port:25626,monitor:"primary",width:2560,height:1440,run_directory:path.join(root,"run"),gradle_properties:{rxDebug:"25627",reflexiumInstallDir:path.join(root,"build/install")}})));
if(mode==="status")console.log(JSON.stringify(await tool("mc_status",{port:25626})));
if(mode==="call")console.log(JSON.stringify(await call(process.argv[3],JSON.parse(process.argv[4]??"{}"))));
if(mode==="set")console.log(JSON.stringify(await set(process.argv[3],process.argv[4])));
if(mode==="setup"){await call("join",{world:"Reflexium Vitrail Smoke",timeout_s:120});for(const command of ["gamemode spectator @a","tick freeze","time set 6000","weather clear"])await call("command",{command});for(const [k,v] of Object.entries({frame_generation:"off",smart_reflex:0,reflex_interval_us:16667,pack_shadow_cap:"0,0",fsr_sharpness:20}))await set(k,v);}
if(mode==="exit")console.log(JSON.stringify(await call("quit",{exit:1})));


if(mode==="matrix") {
 await fs.mkdir(out,{recursive:true});
 for(const shader of [0,1]) {
  await set("shaders",shader); await call("wait",{ms:4000});
  for(const binary of [0,1]) {
   await set("cutout_mips",binary); await call("wait",{ms:8000});
   for(const quality of ["native","quality","performance"]) {
    await set("upscaler",quality==="native"?"off":"dlss");
    if(quality!=="native") {await set("quality",quality);await set("dlss_model","transformer");await set("dlss_preset",11);}
    for(const motion of ["static","pan"]) {
     const label=`${shader?"bsl":"off"}-${binary?"binary":"vanilla"}-${quality}-${motion}`;
     await call("camera",{x:-24.5,y:131,z:-12,yaw:0,pitch:0});
     await call("wait",{ms:2500});
     const r=await call("capture_sequence",{dir:path.join(out,label),count:6,interval_ms:100,...(motion==="pan"?{dyaw:30,ticks:240}:{})});
     await fs.writeFile(path.join(out,label+".json"),JSON.stringify(r,null,2));
     if(motion==="pan")await call("wait",{ms:12500});
     console.log(label);
    }
   }
  }
 }
 await set("cutout_mips",0); await call("wait",{ms:5000});
}if(mode==="grass") {
 for(const shader of [0,1]) {
  await set("shaders",shader);await call("wait",{ms:4000});
  for(const binary of [0,1]) {
   await set("cutout_mips",binary);await call("wait",{ms:8000});
   for(const quality of ["native","quality","performance"]) {
    await set("upscaler",quality==="native"?"off":"dlss");if(quality!=="native")await set("quality",quality);
    for(const motion of ["static","pan"]) { const label=`grass-${shader?"bsl":"off"}-${binary?"binary":"vanilla"}-${quality}-${motion}`;
    await call("camera",{x:-24.5,y:131,z:11.5,yaw:0,pitch:0});await call("wait",{ms:2000});
    const r=await call("capture_sequence",{dir:path.join(out,label),count:6,interval_ms:100,...(motion==="pan"?{dyaw:30,ticks:240}:{})});
    await fs.writeFile(path.join(out,label+".json"),JSON.stringify(r,null,2));if(motion==="pan")await call("wait",{ms:12500});console.log(label); }
   }
  }
 }
 await set("cutout_mips",0);await call("wait",{ms:5000});
}
if(mode==="check") {
 const result=[];
 for(const name of (await fs.readdir(out)).filter(n=>n.endsWith(".json"))) {
  const seq=JSON.parse(await fs.readFile(path.join(out,name),"utf8"));if(!seq.frames)continue;
  const yaws=seq.frames.map(f=>f.camera.yaw);
  for(const f of seq.frames) {
   const r=f.plugins.reflexium,native=name.includes("-native");
   if(f.game_directory!==path.join(root,"run")||f.focus.os_input_focus||f.focus.os_foreground||r.last_error||r.frame_generation!=="off"||f.width!==2560||f.height!==1440)throw Error("Capture invalid: "+name);
   if(native?r.upscaler_effective!=="off"||r.render_size.w!==2560:r.upscaler_effective!=="dlss"||r.render_size.w!==(name.includes("performance")?1280:1707))throw Error("Mode invalid: "+name);
  }
  if(name.includes("-pan")&&new Set(yaws).size<5)throw Error("Insufficient pan: "+name);
  if(name.includes("static")&&new Set(yaws).size!==1)throw Error("Static moved: "+name);
  result.push({name,yaws,frames:seq.frames.map(f=>f.frames),render:seq.frames[0].plugins.reflexium.render_size});
 }
 await fs.writeFile(path.join(root,"docs/validation/iq-evidence-2026-10-19/round7/sequence-checks.json"),JSON.stringify(result,null,2));console.log(result.length+" sequences checked");
}
