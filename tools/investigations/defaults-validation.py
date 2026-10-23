"""Owned 1440p defaults A/B. Analysis dependencies are not shipped with the mod."""
import json
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/validation/defaults'
SESSION = Path('C:/Users/Ryan/source/repos/mc-agent-harness/instances/25671/session.json')

def call(endpoint, **params):
    session = json.loads(SESSION.read_text(encoding='utf-8-sig'))
    assert Path(session['game_directory']).resolve() == ROOT / 'run'
    request = urllib.request.Request('http://127.0.0.1:25671/' + endpoint,
        data=json.dumps(params).encode(), headers={'Authorization':'Bearer '+session['token'], 'Content-Type':'application/json'})
    with urllib.request.urlopen(request, timeout=180) as response:
        result = json.load(response)
    assert result.get('ok'), result
    return result

def setting(key, value):
    return call('reflexium', key=key, value=str(value))

def capture():
    status = call('status')
    if not status['world_loaded']:
        call('join', world='Reflexium Vitrail Smoke', timeout_s=120)
    for command in ('gamemode spectator @a', 'tick freeze', 'time set 6000', 'weather clear'):
        call('command', command=command)
    setting('frame_generation', 'off')
    setting('reflex_mode', 'on')
    setting('smart_reflex', 'true')
    setting('reflex_interval_us', -1)
    setting('fsr_sharpness',20)
    setting('pack_shadow_cap','0,0')
    for pack in ('vanilla', 'bsl'):
        setting('shaders', int(pack == 'bsl'))
        time.sleep(5)
        for mode, model, preset in (('native','transformer',11),('quality','cnn',5),('quality','transformer',11),
                                    ('balanced','transformer',11),('balanced','transformer',13),
                                    ('performance','transformer',11),('performance','transformer',13)):
            label = f'{pack}-{mode}-{model}-{preset}'
            setting('upscaler','off' if mode == 'native' else 'dlss')
            if mode != 'native': setting('quality',mode)
            setting('dlss_model',model)
            setting('dlss_preset',preset)
            for motion in ('static','pan'):
                call('camera',x=-24.5,y=131,z=11.5,yaw=0,pitch=0)
                time.sleep(2.5)
                status = call('status')
                assert status['window']['width']==2560 and status['window']['height']==1440
                prefix = label+'-'+motion
                (OUT/(prefix+'-status.json')).write_text(json.dumps(status,indent=2))
                result = call('capture_sequence',dir=str(OUT/prefix),count=12,interval_ms=100,
                              **({'dyaw':12,'ticks':24} if motion=='pan' else {}))
                (OUT/(prefix+'.json')).write_text(json.dumps(result,indent=2))
                print(prefix,flush=True)
            call('reflexium',profile='reset')
            time.sleep(4)
            profile=call('reflexium',profile='off')
            (OUT/(label+'-profile.json')).write_text(json.dumps(profile,indent=2))
            print(label, profile.get('spans',{}).get('evaluate'),flush=True)
    setting('dlss_preset',-1)

def analyze():
    import numpy as np
    from PIL import Image, ImageDraw
    evidence=ROOT/'docs/validation/defaults-evidence-2026-10-19'
    evidence.mkdir(parents=True,exist_ok=True)
    metrics=[]
    for pack in ('vanilla','bsl'):
        labels=[f'{pack}-{mode}-{model}-{preset}' for mode,model,preset in
                (('native','transformer',11),('quality','cnn',5),('quality','transformer',11),
                 ('balanced','transformer',11),('balanced','transformer',13),
                 ('performance','transformer',11),('performance','transformer',13))]
        for motion in ('static','pan'):
            sheet=Image.new('RGB',(1600,len(labels)*330),'#202020')
            draw=ImageDraw.Draw(sheet)
            for row,label in enumerate(labels):
                prefix=label+'-'+motion
                if not (OUT/(prefix+'.json')).exists(): continue
                meta=json.loads((OUT/(prefix+'.json')).read_text())
                for col,index in enumerate((0,11)):
                    im=Image.open(OUT/prefix/f'frame_{index:03}.png').convert('RGB')
                    sheet.paste(im.crop((1550,650,2350,950)),(800*col,row*330+30))
                    draw.text((800*col+5,row*330+8),f'{label} {motion} frame {index}',fill='white')
                    native=Image.open(OUT/(labels[0]+'-'+motion)/f'frame_{index:03}.png').convert('RGB')
                    native_meta=json.loads((OUT/(labels[0]+'-'+motion+'.json')).read_text())['frames'][index]['camera']
                    assert meta['frames'][index]['camera'] == native_meta, (prefix,index,'camera mismatch')
                    for region,box in {'ground':(400,1100,1400,1280),'plants':(1550,650,2350,950),'leaves':(950,500,1450,850)}.items():
                        a=np.asarray(im.crop(box),dtype=float); b=np.asarray(native.crop(box),dtype=float)
                        mse=float(np.mean((a-b)**2))
                        # High-pass correlation distinguishes retained texture from smoothness.
                        def hp(rgb):
                            g=rgb.mean(axis=2)
                            return (4*g[1:-1,1:-1]-g[:-2,1:-1]-g[2:,1:-1]-g[1:-1,:-2]-g[1:-1,2:]).ravel()
                        metrics.append(dict(label=label,motion=motion,frame=index,region=region,mse=mse,
                                            psnr=float(10*np.log10(255**2/max(mse,1e-12))),
                                            high_pass_correlation=float(np.corrcoef(hp(a),hp(b))[0,1]),
                                            camera=meta['frames'][index]['camera']))
            sheet.save(evidence/f'{pack}-{motion}.png')
    (evidence/'metrics.json').write_text(json.dumps(metrics,indent=2))
    profiles={p.stem.replace('-profile',''):json.loads(p.read_text()) for p in OUT.glob('*-profile.json')}
    (evidence/'profiles.json').write_text(json.dumps(profiles,indent=2))
    rows=['| Mode | Static ground PSNR / HP correlation | Pan ground PSNR / HP correlation | Evaluate ms |',
          '| --- | --- | --- | --- |']
    for label,profile in sorted(profiles.items()):
        scores=[]
        for motion in ('static','pan'):
            m=next(x for x in metrics if x['label']==label and x['motion']==motion and x['frame']==11 and x['region']=='ground')
            scores.append(f"{m['psnr']:.2f} / {m['high_pass_correlation']:.3f}")
        rows.append(f"| {label} | {' | '.join(scores)} | {profile['spans']['evaluate']['gpu_us']/1000:.3f} |")
    (evidence/'summary.md').write_text('\n'.join(rows)+'\n')
    print('Wrote contact sheets, metrics and GPU profiles',flush=True)

def fresh():
    # Run only after removing the owned copy's config and restarting the client.
    initial=call('status')
    rx=initial['plugins']['reflexium']
    assert (rx['upscaler'],rx['quality'],rx['dlss_model'],rx['frame_generation'],rx['fg_multiplier'],rx['reflex_mode']) == ('auto','quality','cnn','dlss',0,'ON'),rx
    assert not rx['smart_reflex'],rx
    (OUT/'fresh-title.json').write_text(json.dumps(initial,indent=2))
    if not initial['world_loaded']: call('join',world='Reflexium Vitrail Smoke',timeout_s=120)
    for shaders in (1,0,1):
        setting('shaders',shaders)
        time.sleep(5)
        state=call('status'); rx=state['plugins']['reflexium']
        assert rx['upscaler_effective'] == ('dlss' if shaders else 'off'),rx
        assert rx['quality']=='quality' and rx['dlss_model']=='cnn' and not rx['last_error'],rx
        if not shaders: assert rx['render_size']==rx['display_size'],rx
        (OUT/f'fresh-shaders-{shaders}.json').write_text(json.dumps(state,indent=2))
        print('Fresh config',shaders,rx['upscaler_effective'],rx['quality'],rx['dlss_model'],rx['fg_status'],flush=True)
    setting('frame_generation','off')

if __name__ == '__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    if sys.argv[1]=='capture': capture()
    elif sys.argv[1]=='analyze': analyze()
    elif sys.argv[1]=='fresh': fresh()
    elif sys.argv[1]=='call': print(json.dumps(call(sys.argv[2],**json.loads(sys.argv[3] if len(sys.argv)>3 else '{}')),indent=2))
