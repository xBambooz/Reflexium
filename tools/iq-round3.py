"""Round 3 captures, restricted to the owned worktree and harness port."""
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/validation/round3"
SESSION = Path("C:/Users/Ryan/source/repos/mc-agent-harness/instances/25613/session.json")

def call(endpoint, **body):
    session = json.loads(SESSION.read_text(encoding="utf-8-sig"))
    assert Path(session["game_directory"]).resolve() == (ROOT / "run").resolve()
    request = urllib.request.Request("http://127.0.0.1:25613/" + endpoint,
        data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + session["token"], "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=240) as response:
        result = json.load(response)
    assert result.get("ok"), result
    return result

def setting(key, value):
    return call("reflexium", key=key, value=str(value))

def capture(label):
    setting("frame_generation", "off")
    call("command", command="tick freeze")
    for shaders in ("1", "0"):
        setting("shaders", shaders)
        call("wait", ms=3000)
        for scene, pose in {"plants":(-24.5,131,11.5,0,0), "water":(-37.5,132,30,0,35)}.items():
            for mode in ("native", "quality", "balanced", "performance"):
                setting("upscaler", "off" if mode == "native" else "dlss")
                if mode != "native":
                    setting("quality", mode)
                call("camera", **dict(zip(("x","y","z","yaw","pitch"),pose)))
                call("wait", ms=2500)
                prefix = f"{label}-{'bsl' if shaders == '1' else 'vanilla'}-{scene}-{mode}"
                status = call("status")
                (OUT / (prefix + "-status.json")).write_text(json.dumps(status,indent=2))
                result = call("capture_sequence", dir=str(OUT / prefix), count=12, interval_ms=100, dyaw=12, ticks=24)
                (OUT / (prefix + ".json")).write_text(json.dumps(result,indent=2))
                print(prefix,flush=True)

def defaults():
    setting("shaders", "1")
    setting("upscaler", "dlss")
    call("wait", ms=3000)
    for mode in ("quality", "balanced", "performance"):
        setting("quality", mode)
        for name,sharp,preset,bias in (("default",20,-1,0),("sharp0",0,-1,0),("sharp40",40,-1,0),
                                       ("bias05",20,-1,.5),("other-preset",20,13 if mode!="performance" else 11,0)):
            setting("fsr_sharpness", sharp)
            setting("dlss_preset", preset)
            setting("mip_bias_offset", bias)
            call("camera", x=-24.5,y=131,z=11.5,yaw=0,pitch=0)
            call("wait",ms=2500)
            prefix=f"defaults-{mode}-{name}"
            (OUT/(prefix+"-status.json")).write_text(json.dumps(call("status"),indent=2))
            result=call("capture_sequence",dir=str(OUT/prefix),count=12,interval_ms=100,dyaw=12,ticks=24)
            (OUT/(prefix+".json")).write_text(json.dumps(result,indent=2))
            print(prefix,flush=True)
    setting("fsr_sharpness",20)
    setting("dlss_preset",-1)
    setting("mip_bias_offset",0)

def analyze():
    import numpy as np
    from PIL import Image, ImageDraw
    evidence=ROOT/"docs/validation/iq-evidence-2026-10-19/round3"
    evidence.mkdir(parents=True,exist_ok=True)
    metrics=[]
    for pack in ("bsl","vanilla"):
        for scene,box in (("plants",(1550,650,2350,1000)),("water",(300,300,1100,650))):
            for label in ("before","after"):
                prefixes=[f"{label}-{pack}-{scene}-{mode}" for mode in ("native","quality","balanced","performance")]
                if not all((OUT/(p+".json")).exists() for p in prefixes): continue
                sheet=Image.new("RGB",(2400,4*380),"#202020")
                draw=ImageDraw.Draw(sheet)
                for row,prefix in enumerate(prefixes):
                    for col,index in enumerate((0,5,11)):
                        im=Image.open(OUT/prefix/f"frame_{index:03}.png")
                        sheet.paste(im.crop(box),(col*800,row*380+30))
                        draw.text((col*800+5,row*380+8),f"{prefix} frame {index}",fill="white")
                sheet.save(evidence/f"{label}-{pack}-{scene}-pan.png")
                native=np.asarray(Image.open(OUT/prefixes[0]/"frame_011.png").convert("RGB"),dtype=float)
                native_meta=json.loads((OUT/(prefixes[0]+".json")).read_text())["frames"][-1]["camera"]
                for prefix in prefixes[1:]:
                    im=np.asarray(Image.open(OUT/prefix/"frame_011.png").convert("RGB"),dtype=float)
                    meta=json.loads((OUT/(prefix+".json")).read_text())["frames"][-1]["camera"]
                    for region,(x,y,w,h) in {"opaque_ground":(400,1100,1000,180),"plants":(1550,650,800,350),"water":(300,300,800,350)}.items():
                        mse=float(np.mean((im[y:y+h,x:x+w]-native[y:y+h,x:x+w])**2))
                        metrics.append(dict(path=prefix,region=region,mse=mse,psnr=float(10*np.log10(255**2/max(mse,1e-12))),
                                            yaw_delta=meta["yaw"]-native_meta["yaw"]))
    for mode in ("quality","balanced","performance"):
        names=("default","sharp0","sharp40","bias05","other-preset")
        if not all((OUT/f"defaults-{mode}-{n}/frame_005.png").exists() for n in names): continue
        sheet=Image.new("RGB",(2400,len(names)*380),"#202020")
        draw=ImageDraw.Draw(sheet)
        for row,name in enumerate(names):
            for col,index in enumerate((0,5,11)):
                im=Image.open(OUT/f"defaults-{mode}-{name}/frame_{index:03}.png")
                sheet.paste(im.crop((1550,650,2350,1000)),(col*800,row*380+30))
                draw.text((col*800+5,row*380+8),f"{mode} {name} frame {index}",fill="white")
        sheet.save(evidence/f"defaults-{mode}.png")
    if (OUT/"glass-performance/frame_011.png").exists():
        sheet=Image.new("RGB",(2400,1520),"#202020")
        draw=ImageDraw.Draw(sheet)
        for row,mode in enumerate(("native","quality","balanced","performance")):
            for col,index in enumerate((0,5,11)):
                im=Image.open(OUT/f"glass-{mode}/frame_{index:03}.png")
                sheet.paste(im.crop((800,400,1600,750)),(col*800,row*380+30))
                draw.text((col*800+5,row*380+8),f"glass {mode} frame {index}",fill="white")
        sheet.save(evidence/"glass-pan.png")
    for name in ("grass-control","water-control","glass-control","crop-motion"):
        source=OUT/(name+"_output.png")
        if source.exists():
            Image.open(source).save(evidence/(name+".png"))
    (evidence/"metrics.json").write_text(json.dumps(metrics,indent=2))
    print(json.dumps(metrics,indent=2))

def controls():
    options=ROOT/"run/shaderpacks/BSL_v10.1.8.zip.txt"
    original=options.read_bytes() if options.exists() else None
    try:
        options.write_text((original.decode() if original else "")+"\nANIMATION_SPEED=0.00\n")
        setting("shaders","0")
        call("wait",ms=500)
        setting("shaders","1")
        setting("upscaler","dlss")
        setting("quality","quality")
        call("wait",ms=3000)

        for name,pose in {"grass-control":(-34.5,132,17,0,20),"water-control":(-37.5,132,30,0,35)}.items():
            call("camera",**dict(zip(("x","y","z","yaw","pitch"),pose)))
            call("wait",ms=1000)
            call("turn",dyaw=30,ticks=120,**{"async":True})
            call("wait",ms=300)
            call("reflexium",capture=name,dir=str(OUT))
            call("wait",ms=6000)
            print(name,flush=True)
        for command in ("fill 96 130 96 104 130 112 cobblestone", "fill 97 131 108 103 135 108 bricks",
                        "fill 97 131 103 103 135 103 glass"):
            call("command",command=command)
        call("command",command="tick step 2")
        for mode in ("native","quality","balanced","performance"):
            setting("upscaler","off" if mode=="native" else "dlss")
            if mode!="native": setting("quality",mode)
            call("camera",x=100.5,y=132,z=98.5,yaw=0,pitch=0)
            call("wait",ms=2500)
            prefix=f"glass-{mode}"
            result=call("capture_sequence",dir=str(OUT/prefix),count=12,interval_ms=100,dyaw=12,ticks=24)
            (OUT/(prefix+".json")).write_text(json.dumps(result,indent=2))
        call("camera",x=100.5,y=132,z=98.5,yaw=0,pitch=0)
        call("wait",ms=1000)
        call("turn",dyaw=30,ticks=120,**{"async":True})
        call("wait",ms=300)
        call("reflexium",capture="glass-control",dir=str(OUT))
        call("wait",ms=6000)
    finally:
        if original is None: options.unlink(missing_ok=True)
        else: options.write_bytes(original)
        setting("shaders","0")
        call("wait",ms=500)
        setting("shaders","1")
        call("wait",ms=3000)

def vector_metrics():
    import numpy as np
    from PIL import Image
    results={}
    for name in ("grass-control","water-control","glass-control","crop-motion"):
        m=json.loads((OUT/(name+".json")).read_text())
        assert m["observed"]["bsl_foliage_motion"]
        assert m["observed"]["primary_depth_source"]=="iris_pre_translucent"
        rgb=np.array(Image.open(m["files"]["motion"]["path"]),dtype=np.int64)[::4,::4]
        d=np.array(Image.open(m["files"]["depth"]["path"]),dtype=np.int64)[::4,::4]
        h,w=m["sizes"]["render_h"],m["sizes"]["render_w"]
        y,x=np.mgrid[0:h:4,0:w:4]
        ndc=np.stack(((x+.5)/w*2-1,1-(y+.5)/h*2,(d[:,:,0]*65536+d[:,:,1]*256+d[:,:,2])/16777215,np.ones_like(x)),axis=-1)
        def matrix(key): return np.array(m["matrices"][key]).reshape((4,4),order="F")
        p=ndc@matrix("current_to_previous").T
        expected=(p[:,:,:2]/p[:,:,3,None]-(ndc[:,:,:2]-[m["jitter"]["ndc_x"],m["jitter"]["ndc_y"]]))*[w/2,h/2]
        actual=np.stack(((rgb[:,:,0]*16+rgb[:,:,1]//16)/32-64,((rgb[:,:,1]%16)*256+rgb[:,:,2])/32-64),axis=-1)
        world=ndc@np.linalg.inv(matrix("world_view_projection_jittered")).T
        world=world[:,:,:3]/world[:,:,3,None]+[m["camera"][k] for k in ("x","y","z")]
        mask=(ndc[:,:,2]>0)&(x>w*.2)&(x<w*.65)&(y>h*.25)&(y<h*.7)
        if name=="grass-control":
            mask &= (world[:,:,1]>132.05)&(world[:,:,1]<133.1)&(world[:,:,0]<-30)
        if name=="water-control":
            mask &= (x>w*.35)&(x<w*.55)&(y>h*.30)&(y<h*.40)
        if name=="glass-control":
            mask &= (world[:,:,0]>97.1)&(world[:,:,0]<103.9)&(world[:,:,1]>131.1)&(world[:,:,1]<135.9)
        if name=="crop-motion":
            result["camera_only_difference_mean_px"]=result.pop("mean_px")
            result["camera_only_difference_p99_px"]=result.pop("p99_px")
            mask &= (world[:,:,0]>90.05)&(world[:,:,0]<95.95)&(world[:,:,1]>131.05)&(world[:,:,1]<132.1)&(world[:,:,2]>100.05)&(world[:,:,2]<106.95)
        error=np.linalg.norm(actual[mask]-expected[mask],axis=1)
        result=dict(samples=int(mask.sum()),mean_px=float(error.mean()),p99_px=float(np.percentile(error,99)),
                    expected_mean_px=float(np.linalg.norm(expected[mask],axis=1).mean()),
                    world_median=np.median(world[mask],axis=0).tolist())
        results[name]=result
        if name=="crop-motion":
            result["nonzero_motion_samples"]=int(np.sum(np.linalg.norm(actual[mask],axis=1)>.02))
            result["actual_mean_px"]=float(np.linalg.norm(actual[mask],axis=1).mean())
            assert result["samples"]>100 and result["expected_mean_px"]<.001 and result["nonzero_motion_samples"]>100,result
            continue
        assert result["samples"]>100 and result["expected_mean_px"]>.05 and result["p99_px"]<.05,result
        if name=="glass-control": assert abs(result["world_median"][2]-108)<.01, result
        if name=="water-control": assert abs(result["world_median"][1]-130)<.01, result
    target=ROOT/"docs/validation/iq-evidence-2026-10-19/round3/vector-metrics.json"
    target.write_text(json.dumps(results,indent=2))
    print(json.dumps(results,indent=2))

def smoke():
    call("command",command="gamemode spectator @a")
    call("command",command="tick unfreeze")
    call("wait",ms=500)
    call("command",command="tick freeze")
    setting("frame_generation","off")
    setting("shaders","1")
    setting("upscaler","dlss")
    setting("quality","quality")
    call("camera",x=93,y=132,z=97,yaw=0,pitch=20)
    call("wait",ms=3000)
    for command in ("fill 90 130 100 95 130 106 farmland[moisture=7]","fill 90 131 100 95 131 106 wheat[age=7]"):
        call("command",command=command)
    call("command",command="tick step 2")
    call("wait",ms=2000)
    call("reflexium",capture="crop-motion",dir=str(OUT))
    call("capture_sequence",dir=str(OUT/"crop-motion"),count=12,interval_ms=100)
    for mode in ("off","dlss"):
        setting("upscaler",mode)
        call("wait",ms=1000)
    for size in ((2432,1368),(2560,1440)):
        call("window",width=size[0],height=size[1],monitor="primary",behind=True)
        call("wait",ms=1500)
    for enabled in ("0","1"):
        setting("shaders",enabled)
        call("wait",ms=2500)
    setting("frame_generation","dlss")
    setting("fg_multiplier",2)
    call("wait",ms=3000)
    (OUT/"fg-smoke-status.json").write_text(json.dumps(call("status"),indent=2))
    setting("frame_generation","off")
    (OUT/"final-status.json").write_text(json.dumps(call("status"),indent=2))

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    if sys.argv[1] in ("before", "after"):
        capture(sys.argv[1])
    elif sys.argv[1] == "defaults":
        defaults()
    elif sys.argv[1] == "analyze":
        analyze()
    elif sys.argv[1] == "controls":
        controls()
    elif sys.argv[1] == "vector-metrics":
        vector_metrics()
    elif sys.argv[1] == "smoke":
        smoke()
