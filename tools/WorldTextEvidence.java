import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import java.util.Locale;

// Fixed-pose diagnostic only: dark glyphs are the common pixels below luma 60 in these oak-sign crops.
public class WorldTextEvidence {
 public static void main(String[] a) throws Exception {
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
}
