import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import java.util.Locale;

// Fixed-pose diagnostic only: dark glyphs are the common pixels below luma 60 in these oak-sign crops.
public class WorldTextEvidence {
 public static void main(String[] a) throws Exception {
  if(a[0].equals("levels-live")){levelsLive(new File(a[1]),new File(a[2]));return;}
  if(a[0].equals("alpha-luma")){
   var n=ImageIO.read(new File(a[1]));var d=ImageIO.read(new File(a[2]));var alpha=ImageIO.read(new File(a[3]));
   int x=Integer.parseInt(a[4]),y=Integer.parseInt(a[5]),w=Integer.parseInt(a[6]),h=Integer.parseInt(a[7]);
   int count=0;double ns=0,ds=0;boolean bright=a[8].equals("bright");
   for(int j=y;j<y+h;j++)for(int i=x;i<x+w;i++){
    double dl=luma(d.getRGB(i,j));
    if((alpha.getRGB(i,j)&255)>0 && (bright?dl>200:dl<60)){count++;ns+=luma(n.getRGB(i,j));ds+=dl;}
   }
   if(count==0)throw new AssertionError("empty alpha/color mask");
   System.out.printf(Locale.ROOT,"{\"masked_pixels\":%d,\"native_mean_luma\":%.4f,\"deferred_mean_luma\":%.4f}%n",count,ns/count,ds/count);return;
  }
  if(a[0].equals("alpha-pair")){
   var normal=ImageIO.read(new File(a[1]));var bypass=ImageIO.read(new File(a[2]));
   int nc=0,bc=0,added=0,lost=0,different=0;
   for(int y=0;y<normal.getHeight();y++)for(int x=0;x<normal.getWidth();x++){
    int n=normal.getRGB(x,y)&255,b=bypass.getRGB(x,y)&255;
    if(n>0)nc++;if(b>0)bc++;if(n==0 && b>0)added++;if(n>0 && b==0)lost++;if(n!=b)different++;
   }
   System.out.printf("{\"normal_covered\":%d,\"bypass_covered\":%d,\"added\":%d,\"lost\":%d,\"different_alpha\":%d}%n",nc,bc,added,lost,different);return;
  }
  if(a[0].equals("mask")){
   var im=ImageIO.read(new File(a[1]));int x=Integer.parseInt(a[2]),y=Integer.parseInt(a[3]),w=Integer.parseInt(a[4]),h=Integer.parseInt(a[5]);int covered=0,partial=0,opaque=0;
   for(int j=y;j<y+h;j++)for(int i=x;i<x+w;i++){int v=im.getRGB(i,j)&255;if(v>0){covered++;if(v<255)partial++;else opaque++;}}
   System.out.printf("{\"covered\":%d,\"partial\":%d,\"opaque\":%d}%n",covered,partial,opaque);return;
  }
  if(a[0].equals("flip")){
   var im=ImageIO.read(new File(a[1]));var out=new BufferedImage(im.getWidth(),im.getHeight(),BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++)out.setRGB(x,im.getHeight()-1-y,im.getRGB(x,y));
   ImageIO.write(out,"png",new File(a[2]));return;
  }
  var nativeImage=ImageIO.read(new File(a[0]));var deferred=ImageIO.read(new File(a[1]));
  int x=Integer.parseInt(a[2]), y=Integer.parseInt(a[3]), w=Integer.parseInt(a[4]), h=Integer.parseInt(a[5]);
  double n=0,d=0;int count=0;
  for(int j=y;j<y+h;j++)for(int i=x;i<x+w;i++){
   double nl=luma(nativeImage.getRGB(i,j)),dl=luma(deferred.getRGB(i,j));
   if(nl<60 && dl<60){n+=nl;d+=dl;count++;}
  }
  if(count==0)throw new AssertionError("empty glyph intersection");
  System.out.printf(Locale.ROOT,"{\"common_dark_pixels\":%d,\"native_mean_luma\":%.4f,\"deferred_mean_luma\":%.4f}%n",count,n/count,d/count);
 }
 static double luma(int c){return .2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255);}
 static double laplacian(BufferedImage im,int x,int y,int w,int h){
  double sum=0,squares=0;int n=0;
  for(int j=y+1;j<y+h-1;j++)for(int i=x+1;i<x+w-1;i++){
   double v=luma(im.getRGB(i-1,j))+luma(im.getRGB(i+1,j))+luma(im.getRGB(i,j-1))+luma(im.getRGB(i,j+1))-4*luma(im.getRGB(i,j));
   sum+=v;squares+=v*v;n++;
  }
  return squares/n-(sum/n)*(sum/n);
 }
 static void levelsLive(File evidence,File raw) throws Exception {
  try(var csv=new java.io.PrintWriter(new File(evidence,"live-luma.csv"))){
   csv.println("state,group,pixels,native_luma,before_luma,after_luma,before_mae,after_mae,native_laplacian,before_laplacian,after_laplacian");
   for(String state:new String[]{"day","shade","night","cave"}){
    var n=ImageIO.read(new File(evidence,state+"-native.png"));var b=ImageIO.read(new File(evidence,state+"-before.png"));var d=ImageIO.read(new File(evidence,state+"-after.png"));
    // Same frozen pose and sign geometry in every state. Later captures can select the name-tag layer.
    var alpha=ImageIO.read(new File(raw,"day-before_world_text_alpha.png"));
    int[][] rois={{997,501,90,45},{840,501,90,45},{1005,382,68,41},{840,382,90,45}};
    String[] names={"standing_dark","standing_glow","hanging_dark","hanging_glow"};
    for(int k=0;k<4;k++){
     var r=rois[k];int count=0;double ns=0,bs=0,ds=0,be=0,de=0;
     for(int j=r[1];j<r[1]+r[3];j++)for(int i=r[0];i<r[0]+r[2];i++){
      double bv=luma(b.getRGB(i,j));if((alpha.getRGB(i,j)&255)==0 || (k%2==1?bv<=200:bv>=60))continue;
      double nv=luma(n.getRGB(i,j)),dv=luma(d.getRGB(i,j));ns+=nv;bs+=bv;ds+=dv;be+=Math.abs(nv-bv);de+=Math.abs(nv-dv);count++;
     }
     if(count==0)throw new AssertionError("empty "+state+" "+names[k]);
     csv.printf(Locale.ROOT,"%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",state,names[k],count,ns/count,bs/count,ds/count,be/count,de/count,laplacian(n,r[0],r[1],r[2],r[3]),laplacian(b,r[0],r[1],r[2],r[3]),laplacian(d,r[0],r[1],r[2],r[3]));
    }
    int x=820,y=360,w=280,h=210;
    for(int k=0;k<3;k++){var im=k==0?n:k==1?b:d;ImageIO.write(im.getSubimage(x,y,w,h),"png",new File(evidence,state+"-"+(k==0?"native":k==1?"before":"after")+"-crop.png"));}
   }
  }
  var images=evidence.listFiles((dir,name)->name.startsWith("shaderless-")&&name.endsWith(".png")&&!name.endsWith("-crop.png"));
  java.util.Arrays.sort(images,java.util.Comparator.comparing(File::getName));
  try(var csv=new java.io.PrintWriter(new File(evidence,"shaderless-pixel-comparison.csv"))){
   csv.println("first,second,different_pixels,max_channel_delta");
   for(int k=1;k<images.length;k++){
    var first=ImageIO.read(images[0]);var second=ImageIO.read(images[k]);int count=0,max=0;
    for(int y=0;y<first.getHeight();y++)for(int x=0;x<first.getWidth();x++){int p=first.getRGB(x,y),q=second.getRGB(x,y);if(p!=q){count++;for(int shift:new int[]{0,8,16})max=Math.max(max,Math.abs(((p>>shift)&255)-((q>>shift)&255)));}}
    csv.printf("%s,%s,%d,%d%n",images[0].getName(),images[k].getName(),count,max);
   }
  }
 }
}
