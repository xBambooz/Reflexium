import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const out=root+'/docs/validation/fg-evidence-2026-10-19/round4';await fs.mkdir(out,{recursive:true});
const captures=[];
for(const arm of ['before','after'])for(const scene of ['smoke','rain'])for(const multiplier of ['2','auto']){
 const label=arm+'-'+scene+'-'+multiplier;
 const data=JSON.parse(await fs.readFile(root+'/build/fg-round4/'+label+'.json','utf8'));
 const status=JSON.parse(await fs.readFile(root+'/build/fg-round4/'+label+'-status.json','utf8'));
 if(data.last_error||data.fg_failed)throw Error(label+' failed');
 if(data.observed.particle_generated_copies!==(arm==='before'?0:data.captured_generated_count))throw Error(label+' wrong replay count');
 if(data.fg_effective_multiplier<2)throw Error(label+' bypassed');
 if(status.focus.os_input_focus||status.focus.os_foreground||status.focus.os_minimized)throw Error(label+' focus');
 captures.push({label,captured_generated:data.captured_generated_count,effective_multiplier:data.fg_effective_multiplier,requested_multiplier:data.fg_multiplier,particle_copies:data.observed.particle_generated_copies,post_particles:data.observed.post_upscale_particles,render:data.render_size,display:data.display_size,camera:data.camera,harness_camera:status.camera,focus:status.focus,fg_evaluated:data.fg_evaluated_frames,fg_presented:data.fg_generated_presents,last_error:data.last_error});
}
await fs.writeFile(out+'/captures.json',JSON.stringify(captures,null,2));console.log(JSON.stringify(captures.map(({label,effective_multiplier,particle_copies})=>({label,effective_multiplier,particle_copies})),null,2));
