import fs from 'node:fs';
import {acquire,release,exclusive} from './lock.mjs';
import {root,ps,save,delay} from './lib.mjs';
const dir=root+'/docs/validation/dlss-1440p-perf-2026-10-30/p-overnight/'+(process.argv.includes('--focused')?'acceptance-focused':'acceptance');
const profile=process.env.APPDATA+'/ModrinthApp/profiles/26.3 Testing Instance';
const port=process.argv.includes('--focused')?25875:25873;
const debugPort=port+1;
let pid=0,foreground=0;
const call=async(endpoint,body)=>{
 const token=fs.readFileSync(profile+'/.agentharness-token','utf8').trim();
 const r=await fetch(`http://127.0.0.1:${port}/${endpoint}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(150000)});
 const data=await r.json();if(!r.ok||data.ok===false)throw Error(JSON.stringify(data));return data;
};
await acquire('capture');
try{
 exclusive();
 const hash=ps(`(Get-FileHash -LiteralPath '${profile}/mods/Reflexium-1.2.0-alpha-fabric-26.3.jar').Hash`);
 save(dir+'/jar.json',{hash});
 if(hash!=='6B40061CBA29FC5E874621FE13F5FDE1E6D8BC41B9D3A62D3C82F0D1B10748DA')throw Error('Testing jar mismatch');
 foreground=Number(ps(`Add-Type 'using System;using System.Runtime.InteropServices;public class PFocus{[DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}';[PFocus]::GetForegroundWindow().ToInt64()`));
 const template=fs.readFileSync(process.env.TEMP+'/opencode/modrinth-java.args','utf8').replace(/-Dreflexium.debugPort=\d+/,'-Dreflexium.debugPort='+debugPort).replace(/(--gameDir\s*\n)[^\n]+/, '$1'+JSON.stringify(profile.replaceAll('\\','/')));
 const args=process.env.TEMP+'/opencode/p-acceptance.args';
 fs.writeFileSync(args,`-Dagentharness.port=${port}\n-Dagentharness.monitor=primary\n-Dagentharness.width=2560\n-Dagentharness.height=1440\n-Dagentharness.fullscreen=true\n-Dagentharness.focusable=true\n`+template);
 const harness='C:/Users/Ryan/source/repos/mc-agent-harness/build/libs/agentharness-1.0.0.jar';
 pid=JSON.parse(ps(`& '${root}/tools/launch-modrinth-profile.ps1' -JavaArgsFile '${args}' -HarnessJar '${harness}'`)).pid;
 save(dir+'/launch.json',{pid,port,foreground,started:new Date().toISOString()});
 let status;
 for(let i=0;i<120;i++){try{status=await call('status');if(status.screen.includes('Title'))break;}catch{}await delay(1000);}
 if(status?.pid!==pid || status.game_directory.replaceAll('\\','/').toLowerCase()!==profile.replaceAll('\\','/').toLowerCase())throw Error('PID mismatch');
 await call('fullscreen',{fullscreen:1,activate:1});
 if(process.argv.includes('--focused')){
  const focusStatus=await call('status');
  save(dir+'/focus.json',JSON.parse(ps(`& '${root}/tools/dlss-1440p-b2/focus.ps1' -Hwnd ${focusStatus.focus.hwnd}`)));
 }
 await call('join',{world:'Reflexium Acceptance',timeout_s:120});
 await delay(12000);
 status=await call('status');save(dir+'/status.json',status);
 save(dir+'/debug-status.json',await (await fetch(`http://127.0.0.1:${debugPort}/status`)).json());
 save(dir+'/screenshot.json',await (await fetch(`http://127.0.0.1:${debugPort}/screenshot?`+new URLSearchParams({path:dir+'/world.png',gui:'1'}))).json());
 fs.copyFileSync(profile+'/logs/latest.log',dir+'/latest.log');
 console.log(JSON.stringify({pid,window:status.window,focus:status.focus,rx:status.plugins?.reflexium}));
}finally{
 if(pid){const s=await call('status');const info=ps(`(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine`);if(s.pid!==pid||!info.includes('26.3 Testing Instance')||!info.includes('agentharness'))throw Error('Shutdown ownership');await call('quit',{exit:true});for(let i=0;i<60&&ps(`if(Get-Process -Id ${pid} -ErrorAction SilentlyContinue){'alive'}`);i++)await delay(1000);if(ps(`if(Get-Process -Id ${pid} -ErrorAction SilentlyContinue){'alive'}`))throw Error('Client still alive');}
 if(foreground)save(dir+'/restored.json',{foreground,restored:ps(`Add-Type 'using System;using System.Runtime.InteropServices;public class PRestore{[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);}';[PRestore]::SetForegroundWindow([IntPtr]::new(${foreground}))`)});
 release();
}
