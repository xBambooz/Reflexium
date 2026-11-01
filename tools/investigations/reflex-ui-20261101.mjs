import fs from 'node:fs/promises';
import path from 'node:path';
import {dispatch} from 'file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs';
const root=path.resolve(import.meta.dirname,'../..'),dir=path.join(root,'docs/validation/reflex-ui-20261101');
const mode=process.argv[2],port=Number(process.argv[3]);
await fs.mkdir(dir,{recursive:true});
async function tool(name,args){const r=await dispatch({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}});const c=(r.result?.content??r.content).find(x=>x.type==='text').text;if(r.result?.isError)throw Error(c);return JSON.parse(c);}
const call=(endpoint,params={})=>tool('mc_call',{port,endpoint,params});
if(mode==='click'){const r=await call('click',{x:Number(process.argv[4]),y:Number(process.argv[5]),button:Number(process.argv[6]??1)});console.log(JSON.stringify({screen:r.screen,accepted:r.accepted}));}
else if(mode==='key'){await call('key',{scancode:Number(process.argv[4])});console.log('key sent');}
else if(mode==='text'){await call('text',{text:process.argv[4]});console.log('text sent');}
else if(mode==='set'){await call('reflexium',{key:process.argv[4],value:process.argv[5]});console.log('setting applied');}
else if(mode==='shot'){
 const name=process.argv[4];await call('wait',{ms:400});
 const s=await tool('mc_status',{port});await fs.writeFile(path.join(dir,name+'.json'),JSON.stringify(s,null,2));
 await call('screenshot',{path:path.join(dir,name+'.png')});console.log(name+'.png');
}else if(mode==='status'){
 const s=await tool('mc_status',{port});await fs.writeFile(path.join(dir,'latest-status.json'),JSON.stringify(s,null,2));
 console.log(JSON.stringify({screen:s.screen,widgets:s.widgets,focus:s.focus,reflex:s.plugins?.reflexium?.reflex_status,mode:s.plugins?.reflexium?.reflex_mode}));
}else throw Error('Unknown command');
