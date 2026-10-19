import fs from "node:fs/promises";
import path from "node:path";
import {dispatch} from "file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs";
const root=path.resolve(import.meta.dirname,".."), out=path.join(root,"build/validation/round6");
async function tool(name,args){const r=await dispatch({jsonrpc:"2.0",id:1,method:"tools/call",params:{name,arguments:args}});const c=r.result?.content??r.content;const v=JSON.parse(c.find(x=>x.type==="text").text);if(r.result?.isError||!v.ok&&name!=="mc_launch")throw Error(JSON.stringify(v));return v;}
async function call(endpoint,params={}){const s=JSON.parse((await fs.readFile("C:/Users/Ryan/source/repos/mc-agent-harness/instances/25618/session.json","utf8")).replace(/^\uFEFF/,""));if(path.resolve(s.game_directory)!==path.join(root,"run"))throw Error("Wrong owned directory");return tool("mc_call",{port:25618,endpoint,params});}
const set=(key,value)=>call("reflexium",{key,value:String(value)});
const mode=process.argv[2];
if(mode==="launch")console.log(JSON.stringify(await tool("mc_launch",{project:root,port:25618,monitor:"primary",width:2560,height:1440,run_directory:path.join(root,"run"),gradle_properties:{rxDebug:"25619",reflexiumInstallDir:path.join(root,"build/install")}})));
if(mode==="status")console.log(JSON.stringify(await tool("mc_status",{port:25618})));
if(mode==="call")console.log(JSON.stringify(await call(process.argv[3],JSON.parse(process.argv[4]??"{}"))));
if(mode==="set")console.log(JSON.stringify(await set(process.argv[3],process.argv[4])));
if(mode==="setup"){await call("join",{world:"Reflexium Vitrail Smoke",timeout_s:120});for(const command of ["gamemode spectator @a","tick freeze","time set 6000","weather clear"])await call("command",{command});for(const [k,v] of Object.entries({frame_generation:"off",smart_reflex:0,reflex_interval_us:16667,pack_shadow_cap:"0,0",fsr_sharpness:20}))await set(k,v);}
if(mode==="capture"||mode==="water"){const label=process.argv[3],model=process.argv[4]??"cnn",bias=process.argv[5]??0;await set("upscaler",model==="native"?"off":"dlss");await set("quality","quality");if(model!=="native"){await set("dlss_model",model);await set("dlss_preset",model==="cnn"?5:11);}await set("mip_bias_offset",bias);await call("camera",mode==="water"?{x:0,y:153,z:91,yaw:0,pitch:25}:{x:-24.5,y:131,z:11.5,yaw:0,pitch:0});await call("wait",{ms:2000});const r=await call("capture_sequence",{dir:path.join(out,label),count:8,interval_ms:100,dyaw:mode==="water"?12:60,ticks:240});await fs.writeFile(path.join(out,label+".json"),JSON.stringify(r,null,2));await call("wait",{ms:12500});console.log(label);}
if(mode==="exit")console.log(JSON.stringify(await call("quit",{exit:1})));


if(mode==="water-setup"){for(const command of ["fill -16 145 96 16 145 128 minecraft:bricks","fill -16 146 96 16 149 128 minecraft:water","fill -17 145 95 17 149 95 minecraft:stone_bricks","fill -17 145 129 17 149 129 minecraft:stone_bricks","fill -17 145 95 -17 149 129 minecraft:stone_bricks","fill 17 145 95 17 149 129 minecraft:stone_bricks","fill -10 150 119 -8 160 121 minecraft:bricks","fill 8 150 119 10 160 121 minecraft:oak_leaves"])await call("command",{command});console.log("Water fixture ready");}
