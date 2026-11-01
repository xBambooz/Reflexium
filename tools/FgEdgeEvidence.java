import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

// Signed horizontal-edge correlation measures repeated same-polarity edges, not frame identity.
// Natural texture repetition and blur also affect it; compare the same ROI across verified phases.
public class FgEdgeEvidence {
 static double luma(int c) { return .2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255); }
 static double[][] edges(BufferedImage im, int x, int y, int w, int h) {
  double[][] e=new double[h][w-1];
  for(int j=0;j<h;j++) for(int i=0;i<w-1;i++)
   e[j][i]=luma(im.getRGB(x+i+1,y+j))-luma(im.getRGB(x+i,y+j));
  return e;
 }
 static double ac(double[][] e, int lag) {
  double ab=0,aa=0,bb=0;
  for(var row:e) for(int x=0;x<row.length-lag;x++) {
   double a=row[x],b=row[x+lag]; ab+=a*b;aa+=a*a;bb+=b*b;
  }
  return aa*bb==0 ? 0 : ab/Math.sqrt(aa*bb);
 }
 static double energy(double[][] e) {
  double s=0;int n=0;for(var row:e)for(double v:row){s+=v*v;n++;}return s/n;
 }
 static int boardShift(BufferedImage reference, BufferedImage im) {
  // Independent translation anchor: left board boundary, above the crosshair, not an AC score.
  int best=0;double bestError=Double.POSITIVE_INFINITY;
  for(int shift=-12;shift<=12;shift++) {
   double error=0;
   for(int x=100;x<125;x++)for(int y=54;y<83;y++) {
    double d=luma(reference.getRGB(x,y))-luma(im.getRGB(x+shift,y));error+=d*d;
   }
   if(error<bestError){bestError=error;best=shift;}
  }
  return best;
 }
 public static void main(String[] args)throws Exception {
  Locale.setDefault(Locale.ROOT);
  if(args.length==0) {
   var single=new BufferedImage(64,8,BufferedImage.TYPE_INT_RGB);
   var doubled=new BufferedImage(64,8,BufferedImage.TYPE_INT_RGB);
   var ramp=new BufferedImage(64,8,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<8;y++)for(int x=0;x<64;x++) {
    single.setRGB(x,y,x>=20?0xffffff:0);
    int d=x<20?0:x<23?127:254;doubled.setRGB(x,y,d*0x010101);
    int r=x<20?0:x==20?85:x==21?170:255;ramp.setRGB(x,y,r*0x010101);
   }
   assert Math.abs(ac(edges(single,0,0,64,8),3))<1e-9;
   assert Math.abs(ac(edges(ramp,0,0,64,8),3))<1e-9;
   assert Math.abs(ac(edges(doubled,0,0,64,8),3)-.5)<1e-9;
   var shifted=new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB);
   var anchor=new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<180;y++)for(int x=0;x<320;x++) {
    anchor.setRGB(x,y,x>=110?0xffffff:0);shifted.setRGB(x,y,x>=114?0xffffff:0);
   }
   assert boardShift(anchor,shifted)==4;
   System.out.println("Edge check passed: isolated edge and ramp AC3=0, separated duplicate AC3=0.5");return;
  }
  Path dir=Path.of(args[0]);int first=Integer.parseInt(args[1]),last=Integer.parseInt(args[2]);
  int roiStart=args[3].startsWith("ref=")?4:3;
  var reference=roiStart==4?ImageIO.read(dir.resolve("frame_%05d.png".formatted(Integer.parseInt(args[3].substring(4)))).toFile()):null;
  System.out.println("frame,phase3,roi,anchor_dx,edge_energy,ac1,ac2,ac3,ac4,ac5,ac6");
  for(int frame=first;frame<=last;frame++) {
   var im=ImageIO.read(dir.resolve("frame_%05d.png".formatted(frame)).toFile());
   int shift=reference==null?0:boardShift(reference,im);
   for(int r=roiStart;r<args.length;r++) {
    var a=args[r].split(":");var e=edges(im,Integer.parseInt(a[1])+shift,Integer.parseInt(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]));
    System.out.printf("%d,%d,%s,%d,%.8f",frame,frame%3,a[0],shift,energy(e));
    for(int lag=1;lag<=6;lag++)System.out.printf(",%.8f",ac(e,lag));
    System.out.println();
   }
  }
 }
}
