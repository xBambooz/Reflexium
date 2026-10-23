import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

/** Native-pixel evidence and descriptive static temporal/green-coverage measurements. */
public class Round7Evidence {
 static int[][] regions={{1188,656,130,90},{100,1100,600,220},{1490,715,340,45}};
 static BufferedImage read(Path dir,int i)throws Exception{return ImageIO.read(dir.resolve("frame_%03d.png".formatted(i)).toFile());}
 static boolean green(int c){int r=c>>16&255,g=c>>8&255,b=c&255;return g>r*1.1&&g>b*1.1;}
 static double coverage(BufferedImage im,int[] r){int n=0;for(int y=r[1];y<r[1]+r[3];y++)for(int x=r[0];x<r[0]+r[2];x++)if(green(im.getRGB(x,y)))n++;return n/(double)(r[2]*r[3]);}
 static double difference(BufferedImage a,BufferedImage b,int[] r){long sum=0;for(int y=r[1];y<r[1]+r[3];y++)for(int x=r[0];x<r[0]+r[2];x++){int ca=a.getRGB(x,y),cb=b.getRGB(x,y);for(int shift:new int[]{0,8,16})sum+=Math.abs((ca>>shift&255)-(cb>>shift&255));}return sum/(3.0*r[2]*r[3]);}
 public static void main(String[] args)throws Exception{
  if(green(0xff8080ff)||!green(0xff204020))throw new AssertionError("green classifier");
  Path root=Path.of(args[0]),raw=root.resolve("build/validation/round7"),dest=root.resolve("docs/validation/iq-evidence-2026-10-19/round7");Files.createDirectories(dest);
  StringBuilder csv=new StringBuilder("label,region,static_adjacent_capture_rgb_mae_0_255,green_coverage_fraction\n");
  for(String shader:new String[]{"off","bsl"})for(String motion:new String[]{"static","pan"}){
   BufferedImage sheet=new BufferedImage(1000,6*244,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();int row=0;
   for(String binary:new String[]{"vanilla","binary"})for(String quality:new String[]{"native","quality","performance"}){
    String label=shader+"-"+binary+"-"+quality+"-"+motion;Path dir=raw.resolve(label);if(!Files.exists(dir.resolve("frame_005.png")))continue;
    BufferedImage first=read(dir,0),last=read(dir,5);if(first.getWidth()!=2560||first.getHeight()!=1440)throw new AssertionError("dimensions");
    g.setColor(Color.WHITE);g.drawString(label+" frames 0 / 5; leaf fixture + forest",5,row*244+18);
    for(int c=0;c<2;c++){BufferedImage im=c==0?first:last;g.drawImage(im.getSubimage(1110,610,300,180),c*500,row*244+24,null);g.drawImage(im.getSubimage(180,1100,200,180),c*500+300,row*244+24,null);}
    if(motion.equals("static"))for(int region=0;region<regions.length;region++){
     double delta=0,cov=coverage(first,regions[region]);BufferedImage prev=first;
     for(int i=1;i<6;i++){BufferedImage im=read(dir,i);delta+=difference(prev,im,regions[region]);cov+=coverage(im,regions[region]);prev=im;}
     csv.append(String.format(Locale.ROOT,"%s,%s,%.6f,%.6f%n",label,new String[]{"leaf","forest","grass"}[region],delta/5,cov/6));
    }
    row++;
   }
   g.dispose();ImageIO.write(sheet,"png",dest.resolve(shader+"-"+motion+".png").toFile());
  }
  for(String shader:new String[]{"off","bsl"})for(String motion:new String[]{"static","pan"}){
   BufferedImage sheet=new BufferedImage(1200,6*244,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();int row=0;
   for(String binary:new String[]{"vanilla","binary"})for(String quality:new String[]{"native","quality","performance"}){
    String label="grass-"+shader+"-"+binary+"-"+quality+"-"+motion;Path dir=raw.resolve(label);if(!Files.exists(dir.resolve("frame_005.png")))continue;
    BufferedImage first=read(dir,0),last=read(dir,5);g.setColor(Color.WHITE);g.drawString(label+" frames 0 / 5",5,row*244+18);
    for(int c=0;c<2;c++)g.drawImage((c==0?first:last).getSubimage(1800,650,600,220),c*600,row*244+24,null);
    if(motion.equals("static")){
     int[] r={1850,700,500,80};double delta=0,cov=coverage(first,r);BufferedImage prev=first;
     for(int i=1;i<6;i++){BufferedImage im=read(dir,i);delta+=difference(prev,im,r);cov+=coverage(im,r);prev=im;}
     csv.append(String.format(Locale.ROOT,"%s,close_grass,%.6f,%.6f%n",label,delta/5,cov/6));
    }
    row++;
   }
   g.dispose();ImageIO.write(sheet,"png",dest.resolve("grass-"+shader+"-"+motion+".png").toFile());
  }
  Files.writeString(dest.resolve("metrics.csv"),csv);
  System.out.println(csv);
 }
}
