import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

// WGC has no frame-kind label. This reports cadence and sharpness, never invents labels.
public class WgcPeriodEvidence {
 static double lap(BufferedImage im){
  double sum=0; int n=0;
  for(int y=1;y<im.getHeight()-1;y++)for(int x=1;x<im.getWidth()-1;x++){
   double v=4*luma(im.getRGB(x,y))-luma(im.getRGB(x-1,y))-luma(im.getRGB(x+1,y))-luma(im.getRGB(x,y-1))-luma(im.getRGB(x,y+1));
   sum+=v*v;n++;
  }
  return sum/n;
 }
 static double luma(int v){return .2126*((v>>16)&255)+.7152*((v>>8)&255)+.0722*(v&255);}
 public static void main(String[] args)throws Exception{
  if(args.length==0){
   var flat=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);assert lap(flat)==0;
   flat.setRGB(4,4,0xffffff);assert lap(flat)>0;
   System.out.println("WGC sharpness checks passed");return;
  }
  var dir=Path.of(args[0]);var lines=Files.readAllLines(dir.resolve("frames.csv"));
  var rows=new ArrayList<String>();rows.add("index,timestamp_ms,laplacian_variance,phase3");
  double[] sums=new double[3];int[] counts=new int[3];int duplicates=0,gaps=0;double previous=-1;
  var intervals=new ArrayList<Double>();
  for(int i=1;i<lines.size();i++){
   var a=lines.get(i).split(",");int index=Integer.parseInt(a[0]);double time=Double.parseDouble(a[1]);
   var im=ImageIO.read(dir.resolve("frame_%05d.png".formatted(index)).toFile());double sharp=lap(im);
   sums[index%3]+=sharp;counts[index%3]++;
   if(previous>=0){double dt=time-previous;intervals.add(dt);if(dt<=0)duplicates++;}
   previous=time;rows.add(String.format(Locale.ROOT,"%d,%.3f,%.5f,%d",index,time,sharp,index%3));
  }
  Collections.sort(intervals);double median=intervals.get(intervals.size()/2);
  for(double d:intervals)if(d>median*1.8)gaps++;
  Files.write(dir.resolve("sharpness.csv"),rows);
  System.out.printf(Locale.ROOT,"{\"frames\":%d,\"median_interval_ms\":%.3f,\"nonpositive_intervals\":%d,\"gaps_over_1_8_median\":%d,\"phase3_mean_laplacian\":[%.5f,%.5f,%.5f],\"labels_verified\":false}%n",lines.size()-1,median,duplicates,gaps,sums[0]/counts[0],sums[1]/counts[1],sums[2]/counts[2]);
 }
}
