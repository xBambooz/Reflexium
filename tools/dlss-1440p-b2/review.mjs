import fs from 'node:fs';
import {evidence,root,ps,save} from './lib.mjs';
const spread=(a,b)=>100*(Math.max(a,b)/Math.min(a,b)-1);
function matchingPair(rows,metric){
 for(let i=0;i<rows.length;i++)for(let j=i+1;j<rows.length;j++)if(spread(rows[i][metric],rows[j][metric])<=3)return [rows[i],rows[j]];
 return null;
}
if(process.argv.includes('--check')){
 const {default:assert}=await import('node:assert/strict');
 assert.equal(matchingPair([{fps:100},{fps:104}],'fps'),null);
 assert.deepEqual(matchingPair([{fps:100},{fps:104},{fps:102}],'fps'),[{fps:100},{fps:102}]);
 assert.equal(matchingPair([{fps:100}],'fps'),null);
 console.log('Repeat pairing checks passed');process.exit(0);
}
const file=evidence+'/passes-b3.json';
if(!fs.existsSync(file))throw Error('No raw passes');
const passes=JSON.parse(fs.readFileSync(file));
const records=[];
for(const row of passes){
 const reference=passes.find(r=>r.config==='native' && r.build===row.build && r.key.split('-')[0]===row.key.split('-')[0] && r.valid);
 let correlation=null;
 if(reference){correlation=JSON.parse(ps(`& '${root}/tools/dlss-1440p-b2/correlate.ps1' -Reference '${evidence}/crops/${reference.key}.png' -Candidate '${evidence}/crops/${row.key}.png'`));}
 records.push({...row,correlation,accepted_sample:row.valid && !!correlation?.valid});
}
const results=[];
for(const group of new Set(records.map(r=>r.key.split('-')[0]))){
 for(const config of new Set(records.filter(r=>(r.key.split('-')[0])===group).map(r=>r.config))){
  const rows=records.filter(r=>r.config===config && (r.key.split('-')[0])===group);
  const valid=rows.filter(r=>r.accepted_sample);
  const metrics={};
  for(const metric of ['avg_fps','low1_fps','low01_fps']){
   const pair=matchingPair(valid,metric);
   metrics[metric]=pair?{mean:(pair[0][metric]+pair[1][metric])/2,range:pair.map(r=>r[metric]),keys:pair.map(r=>r.key),spread_pct:spread(pair[0][metric],pair[1][metric])}:{state:'unverified or noisy'};
  }
  results.push({group,config,all:rows.map(r=>({key:r.key,valid:r.accepted_sample,avg:r.avg_fps,correlation:r.correlation?.normalized_correlation})),metrics});
 }
}
save(evidence+'/acceptance.json',{records,results});
console.log(JSON.stringify(results,null,2));
