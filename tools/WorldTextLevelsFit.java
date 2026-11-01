import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Offline linear-light fit. No Minecraft, GPU, external libraries or image readback. */
public class WorldTextLevelsFit {
    static final String[] MODELS = {"levels", "constant", "varying", "varying_constant"};
    static final int[] RADII = {2, 4, 8};
    record Sample(double[] local, double[] text, double[] nativeRgb, int x, int y) {}
    record Group(String id, String state, String kind, int width, List<Sample> samples,
                 BufferedImage nativeImage, BufferedImage before) {}
    static double linear(double v) { return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
    static double encoded(double v) { v=Math.clamp(v,0,1); return v <= .0031308 ? 12.92*v : 1.055*Math.pow(v,1/2.4)-.055; }
    static double[] rgb(int p) { return new double[]{((p>>16)&255)/255., ((p>>8)&255)/255., (p&255)/255.}; }
    static double luma(double[] p) { return 255*(.2126*p[0]+.7152*p[1]+.0722*p[2]); }
    static int pixel(double[] p) { return 0xff000000 | ((int)Math.round(p[0]*255)<<16) | ((int)Math.round(p[1]*255)<<8) | (int)Math.round(p[2]*255); }
    static BufferedImage read(Path p) throws IOException { var im=ImageIO.read(p.toFile()); if(im==null)throw new IOException("unreadable "+p);return im; }
    static double[] features(Sample s, int c, String model) {
        double l=s.local[c], t=s.text[c];
        return switch(model) {
            case "levels" -> new double[]{l,t};
            case "constant" -> new double[]{l,t,1};
            case "varying" -> new double[]{l,t,l*l};
            case "varying_constant" -> new double[]{l,t,l*l,1};
            default -> throw new IllegalArgumentException(model);
        };
    }
    static double[] solve(double[][] matrix, double[] target) {
        int n=target.length; var a=new double[n][n+1];
        for(int i=0;i<n;i++){System.arraycopy(matrix[i],0,a[i],0,n);a[i][n]=target[i];a[i][i]+=1e-10;}
        for(int i=0;i<n;i++){
            int best=i;for(int j=i+1;j<n;j++)if(Math.abs(a[j][i])>Math.abs(a[best][i]))best=j;
            var tmp=a[i];a[i]=a[best];a[best]=tmp;
            if(Math.abs(a[i][i])<1e-14)throw new IllegalArgumentException("singular fit");
            double d=a[i][i];for(int j=i;j<=n;j++)a[i][j]/=d;
            for(int k=0;k<n;k++)if(k!=i){d=a[k][i];for(int j=i;j<=n;j++)a[k][j]-=d*a[i][j];}
        }
        double[] out=new double[n];for(int i=0;i<n;i++)out[i]=a[i][n];return out;
    }
    static double[][] fit(List<Group> groups, String model) {
        var out=new double[3][];
        for(int c=0;c<3;c++){
            int n=features(groups.getFirst().samples.getFirst(),c,model).length;
            var a=new double[n][n];var b=new double[n];
            // Equal group weight prevents dense fonts or one brightness class dominating.
            for(var g:groups)for(var s:g.samples){
                double[] x=features(s,c,model); double w=1./g.samples.size();
                for(int i=0;i<n;i++){b[i]+=w*x[i]*s.nativeRgb[c];for(int j=0;j<n;j++)a[i][j]+=w*x[i]*x[j];}
            }
            out[c]=solve(a,b);
        }
        return out;
    }
    static double[] predict(Sample s, String model, double[][] weights) {
        var out=new double[3];for(int c=0;c<3;c++){double[] x=features(s,c,model);for(int j=0;j<x.length;j++)out[c]+=x[j]*weights[c][j];out[c]=encoded(out[c]);}return out;
    }
    static double[][][] integral(BufferedImage scene) {
        int w=scene.getWidth(),h=scene.getHeight();var out=new double[3][h+1][w+1];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){var p=rgb(scene.getRGB(x,y));for(int c=0;c<3;c++)out[c][y+1][x+1]=linear(p[c])+out[c][y][x+1]+out[c][y+1][x]-out[c][y][x];}
        return out;
    }
    static Path destination(Path root) { return root.resolve("docs/validation/world-text-shading-2026-11-01/offline-fit"); }
    static void prepare(Path root, Path original) throws IOException {
        Path dest=destination(root).resolve("inputs");Files.createDirectories(dest);
        for(String state:new String[]{"day","shade","night","original_day","original_shade"}){
            boolean old=state.startsWith("original");String stem=state.endsWith("day")?"shade0":"shade1";
            Path dir=old?original:root.resolve("docs/validation/world-text-shading-2026-11-01");
            Path raw=old?original:root.resolve("run/world-text-shading-raw");
            String d=old?stem+"-ultra_performance":state+"-deferred",n=old?stem+"-native":state+"-native";
            var paths=Map.of("native",dir.resolve(n+".png"),"before",dir.resolve(d+".png"),
                "alpha",dir.resolve(d+"_world_text_alpha.png"),"text",raw.resolve(d+"_world_text.png"),"scene",raw.resolve(d+"_output.png"));
            for(var e:paths.entrySet()){
                var im=read(e.getValue());var crop=old?im.getSubimage(1090,480,380,300):im.getSubimage(820,360,280,210);
                ImageIO.write(crop,"png",dest.resolve(state+"_"+e.getKey()+".png").toFile());
            }
        }
    }
    static List<Group> load(Path root, int radius) throws IOException {
        var groups=new ArrayList<Group>();
        for(String state:new String[]{"day","shade","night","original_day","original_shade"}){
            boolean old=state.startsWith("original");Path dir=destination(root).resolve("inputs");
            var nativeIm=read(dir.resolve(state+"_native.png"));var before=read(dir.resolve(state+"_before.png"));
            var alpha=read(dir.resolve(state+"_alpha.png"));var text=read(dir.resolve(state+"_text.png"));var scene=read(dir.resolve(state+"_scene.png"));
            int w=scene.getWidth(),h=scene.getHeight(),fullWidth=old?2560:1920,r=(int)Math.round(radius*fullWidth/1920.);
            if(nativeIm.getWidth()!=w || before.getWidth()!=w || alpha.getWidth()!=w || text.getWidth()!=w)throw new AssertionError("mismatched image size");
            var sums=integral(scene);
            int[][] rois=old?new int[][]{{1330,668,120,60},{1120,668,120,60},{1330,510,120,60},{1120,510,120,60}}
                           :new int[][]{{997,501,90,45},{840,501,90,45},{1005,382,68,41}};
            String[] kinds={"standing_dark","standing_glow","hanging_dark","hanging_glow"};
            for(int k=0;k<rois.length;k++){
                var samples=new ArrayList<Sample>();var roi=rois[k];boolean glow=k%2==1;
                roi[0]-=old?1090:820;roi[1]-=old?480:360;
                for(int y=roi[1];y<roi[1]+roi[3];y++)for(int x=roi[0];x<roi[0]+roi[2];x++){
                    double coverage=(alpha.getRGB(x,y)&255)/255.;var dp=rgb(before.getRGB(x,y));
                    if(coverage<=0 || (glow?luma(dp)<=200:luma(dp)>=60))continue;
                    double[] l=new double[3],t=rgb(text.getRGB(x,y)),np=rgb(nativeIm.getRGB(x,y));
                    int x0=Math.max(0,x-r),x1=Math.min(w,x+r+1),y0=Math.max(0,y-r),y1=Math.min(h,y+r+1);
                    for(int c=0;c<3;c++){
                        l[c]=(sums[c][y1][x1]-sums[c][y0][x1]-sums[c][y1][x0]+sums[c][y0][x0])/((x1-x0)*(y1-y0));
                        t[c]=linear(Math.min(1,t[c]/coverage));np[c]=linear(np[c]);
                    }
                    samples.add(new Sample(l,t,np,x,y));
                }
                if(samples.isEmpty())throw new AssertionError("empty mask "+state+" "+kinds[k]);
                groups.add(new Group(state+"_"+kinds[k],state,kinds[k],fullWidth,samples,nativeIm,before));
            }
        }
        return groups;
    }
    static double saturation(double[] p) {double mx=Math.max(p[0],Math.max(p[1],p[2])),mn=Math.min(p[0],Math.min(p[1],p[2]));return mx==0?0:(mx-mn)/mx;}
    static double hue(double[] p) {
        double r=p[0],g=p[1],b=p[2],mx=Math.max(r,Math.max(g,b)),mn=Math.min(r,Math.min(g,b)),d=mx-mn;
        if(d<1e-9)return 0;double h=mx==r?(g-b)/d:mx==g?(b-r)/d+2:(r-g)/d+4;
        return (h*60+360)%360;
    }
    static void score(PrintWriter csv,Group g,String model,int radius,String split,double[][] weights,Path crops) throws IOException {
        double err=0,beforeErr=0;var nm=new double[3];var pm=new double[3];
        BufferedImage out=null;
        if(crops!=null){out=new BufferedImage(g.before.getWidth(),g.before.getHeight(),BufferedImage.TYPE_INT_RGB);var graphics=out.createGraphics();try{graphics.drawImage(g.before,0,0,null);}finally{graphics.dispose();}}
        for(var s:g.samples){var p=predict(s,model,weights);var n=s.nativeRgb.clone();for(int c=0;c<3;c++){n[c]=encoded(n[c]);nm[c]+=n[c];pm[c]+=p[c];}
            err+=Math.abs(luma(n)-luma(p));beforeErr+=Math.abs(luma(n)-luma(rgb(g.before.getRGB(s.x,s.y))));
            if(crops!=null)out.setRGB(s.x,s.y,pixel(p));
        }
        int count=g.samples.size();for(int c=0;c<3;c++){nm[c]/=count;pm[c]/=count;}
        double hn=hue(nm),hp=hue(pm),he=Math.abs(hn-hp);he=Math.min(he,360-he);
        csv.printf(Locale.ROOT,"%s,%d,%s,%s,%d,%.6f,%.6f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.6f,%.6f%n",model,radius,split,g.id,count,beforeErr/count,err/count,hn,hp,he,nm[0]*255,nm[1]*255,nm[2]*255,pm[0]*255,pm[1]*255,pm[2]*255,saturation(nm),saturation(pm));
        if(crops!=null)ImageIO.write(out,"png",crops.resolve(g.id+".png").toFile());
    }
    static void selfCheck() {
        assert Math.abs(encoded(linear(.5))-.5)<1e-12;
        double[] x=solve(new double[][]{{2,1},{1,2}},new double[]{5,4});assert Math.abs(x[0]-2)<1e-8 && Math.abs(x[1]-1)<1e-8;
        var im=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);for(int y=0;y<3;y++)for(int j=0;j<3;j++)im.setRGB(j,y,0xffffff);
        assert Math.abs(integral(im)[0][3][3]-9)<1e-10;
        assert Math.abs(hue(new double[]{.8,.4,.2})-20)<1e-10;
    }
    static void nativeStatistics(Path dest,List<Group> groups) throws IOException {
        try(var out=new PrintWriter(dest.resolve("native-statistics.csv").toFile())){
            out.println("group,pixels,p10_luma,median_luma,p90_luma,optimal_constant_luma_mae,common_native_bright_core_pixels");
            for(var g:groups){
                double[] ys=g.samples.stream().mapToDouble(s->{var p=s.nativeRgb.clone();for(int c=0;c<3;c++)p[c]=encoded(p[c]);return luma(p);}).sorted().toArray();
                double median=ys[ys.length/2],err=0;int core=0;
                for(double y:ys){err+=Math.abs(y-median);if(y>200)core++;}
                out.printf(Locale.ROOT,"%s,%d,%.6f,%.6f,%.6f,%.6f,%d%n",g.id,ys.length,ys[ys.length/10],median,ys[ys.length*9/10],err/ys.length,core);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        selfCheck();if(args.length==1 && args[0].equals("--self-check")){System.out.println("offline fit checks passed");return;}
        if(args[0].equals("--prepare")){prepare(Path.of(args[1]),Path.of(args[2]));return;}
        Path root=Path.of(args[0]),dest=destination(root);Files.createDirectories(dest);
        try(var csv=new PrintWriter(dest.resolve("scores.csv").toFile());var coefficients=new PrintWriter(dest.resolve("coefficients.txt").toFile())){
            csv.println("model,radius1080,split,group,pixels,before_luma_mae,predicted_luma_mae,native_hue,predicted_hue,hue_error,native_R,native_G,native_B,predicted_R,predicted_G,predicted_B,native_saturation,predicted_saturation");
            for(int radius:RADII){var groups=load(root,radius);if(radius==8)nativeStatistics(dest,groups);
                for(String model:MODELS){
                    for(String held:new String[]{"day","shade","night"}){
                        var train=groups.stream().filter(g->!g.state.startsWith("original")&&!g.state.equals(held)&&g.kind.startsWith("standing")).toList();
                        var weights=fit(train,model);coefficients.println(model+" r="+radius+" held="+held+" "+Arrays.deepToString(weights));
                        Path previews=dest.resolve("previews");if(radius==8&&model.equals("varying"))Files.createDirectories(previews);
                        for(var g:groups)if(g.state.equals(held))score(csv,g,model,radius,"leave_state_out",weights,radius==8&&model.equals("varying")?previews:null);
                    }
                    var train=groups.stream().filter(g->!g.state.startsWith("original")&&g.kind.startsWith("standing")).toList();var weights=fit(train,model);
                    coefficients.println(model+" r="+radius+" all1080 "+Arrays.deepToString(weights));
                    for(var g:groups)if(g.state.startsWith("original"))score(csv,g,model,radius,"resolution_holdout",weights,radius==8&&model.equals("varying")?dest.resolve("previews"):null);
                }
            }
        }
        System.out.println("Wrote "+dest);
    }
}
