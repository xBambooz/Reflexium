import fs from "node:fs/promises";
import path from "node:path";
const root=path.resolve(import.meta.dirname,".."),out=process.argv[2]?path.resolve(process.argv[2]):path.join(root,"build/validation/round6");
const results=[];
for(const name of await fs.readdir(out)){
 if(!name.endsWith(".json")||name.includes("status"))continue;
 const s=JSON.parse((await fs.readFile(path.join(out,name),"utf8")).replace(/^\uFEFF/,""));
 if(!s.frames)continue;
 const f=s.frames,r=f[3].plugins.reflexium;
 for(const v of f){const rx=v.plugins.reflexium;if(rx.last_error||rx.frame_generation!=="off"||rx.display_size.w!==2560||rx.display_size.h!==1440||v.focus.os_foreground||v.focus.os_input_focus)throw Error("Invalid capture "+name);const native=rx.upscaler_effective==="off";if(rx.render_size.w!==(native?2560:1707)||rx.render_size.h!==(native?1440:960))throw Error("Wrong render size "+name);}
 const yaws=f.map(x=>x.camera.yaw);if(new Set(yaws).size<6)throw Error("Insufficient pan poses "+name);
 results.push({name,yaws,render:r.render_size,display:r.display_size,pack:r.pack,model:r.dlss_model,preset:r.dlss_preset_hint,sharpness:r.fsr_sharpness,mip_bias:r.mip_bias,phases:r.observed.jitter_phases,resets:r.observed.resets_by_reason});
}
await fs.writeFile(path.join(out,"sequence-checks.json"),JSON.stringify(results,null,2));
console.log(results.map(x=>`${x.name}: phases=${x.phases}, yaw=${x.yaws.join(",")}`).join("\n"));
