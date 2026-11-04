import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Match actual real-target screenshots to WGC frames before inspecting local 3x cadence. */
public class FgBobEvidence {
 static double luma(int c){return .2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255);}
 static double mae(BufferedImage a,BufferedImage b,int ox,int oy,int step){
  double error=0;int n=0;for(int y=0;y<b.getHeight();y+=step)for(int x=0;x<b.getWidth();x+=step){error+=Math.abs(luma(a.getRGB(x+500-ox,y+280-oy))-luma(b.getRGB(x,y)));n++;}
  return error/n;
 }
 static double[] edge(BufferedImage im,int axis,int x0,int y0,int w,int h){
  int stride=axis==0?w-1:w,rows=axis==0?h:h-1;double[] e=new double[stride*rows];
  for(int y=0;y<rows;y++)for(int x=0;x<stride;x++)e[y*stride+x]=luma(im.getRGB(x0+x+(axis==0?1:0),y0+y+(axis==1?1:0)))-luma(im.getRGB(x0+x,y0+y));
  return e;
 }
 static double ac(double[] e,int stride,int rows,int lag,int axis){
  double ab=0,aa=0,bb=0;for(int y=0;y<rows-(axis==1?lag:0);y++)for(int x=0;x<stride-(axis==0?lag:0);x++){
   double a=e[y*stride+x],b=e[(y+(axis==1?lag:0))*stride+x+(axis==0?lag:0)];ab+=a*b;aa+=a*a;bb+=b*b;
  }return aa*bb==0?0:ab/Math.sqrt(aa*bb);
 }
 public static void main(String[] args)throws Exception{
  Locale.setDefault(Locale.ROOT);Path root=Path.of(args[0]),dir=root.resolve("presentation-small");
  List<Path> paths;try(var files=Files.list(dir)){paths=files.filter(p->p.getFileName().toString().matches("frame_\\d+\\.png")).sorted().toList();}
  List<BufferedImage> frames=new ArrayList<>();for(Path p:paths)frames.add(ImageIO.read(p.toFile()));
  int ox=0,oy=0;double offsetError=Double.POSITIVE_INFINITY;
  BufferedImage firstReal=ImageIO.read(root.resolve("real-0.png").toFile());
  for(int x=0;x<=12;x+=2)for(int y=20;y<=40;y+=2)for(int f=0;f<frames.size();f++){
   double e=mae(firstReal,frames.get(f),x,y,6);if(e<offsetError){offsetError=e;ox=x;oy=y;}
  }
  int coarseX=ox,coarseY=oy;
  for(int x=Math.max(0,coarseX-2);x<=coarseX+2;x++)for(int y=coarseY-2;y<=coarseY+2;y++)for(int f=0;f<frames.size();f++){
   double e=mae(firstReal,frames.get(f),x,y,3);if(e<offsetError){offsetError=e;ox=x;oy=y;}
  }
  System.out.printf("WGC nonclient offset x=%d y=%d%n",ox,oy);
  StringBuilder anchors=new StringBuilder("anchor,best_frame,mae\n");
  for(int i=0;i<3;i++){
   BufferedImage real=ImageIO.read(root.resolve("real-"+i+".png").toFile());int best=-1;double error=Double.POSITIVE_INFINITY;
   for(int f=0;f<frames.size();f++){double e=mae(real,frames.get(f),ox,oy,1);if(e<error){error=e;best=f;}}
   if(error>1e-6)throw new AssertionError("No exact real-frame anchor: "+i+" MAE="+error);
   anchors.append(String.format("%d,%d,%.6f%n",i,best,error));
  }
  Files.writeString(root.resolve("anchors.csv"),anchors);System.out.print(anchors);
  StringBuilder scores=new StringBuilder("frame,roi,axis,energy,ac1,ac2,ac3,ac4,ac5,ac6\n");
  for(int f=0;f<frames.size();f++)for(int r=0;r<3;r++)for(int axis=0;axis<2;axis++){
   int x0=r==0?25:r==1?260:0,y0=10,w=r==0?145:r==1?50:55,h=160,stride=axis==0?w-1:w,rows=axis==0?h:h-1;
   double[] e=edge(frames.get(f),axis,x0,y0,w,h);double energy=0;for(double v:e)energy+=v*v;
   scores.append(String.format("%d,%s,%s,%.6f",f,r==0?"bookshelf":r==1?"glass":"chain",axis==0?"x":"y",energy/e.length));
   for(int lag=1;lag<=6;lag++)scores.append(String.format(",%.6f",ac(e,stride,rows,lag,axis)));scores.append('\n');
  }
  Files.writeString(root.resolve("edge-scores.csv"),scores);
 }
}
