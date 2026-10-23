import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/** Independent binary-majority occupancy check on vanilla assets, not a vanilla mip emulator. */
public class Round7MipCheck {
 static boolean[][] next(boolean[][] p){int n=p.length/2;boolean[][] q=new boolean[n][n];for(int y=0;y<n;y++)for(int x=0;x<n;x++){int count=0;for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)if(p[y*2+dy][x*2+dx])count++;q[y][x]=count>=2;}return q;}
 static double coverage(boolean[][] p){int count=0;for(boolean[] row:p)for(boolean v:row)if(v)count++;return count/(double)(p.length*p.length);}
 public static void main(String[] args)throws Exception{
  if(next(new boolean[][]{{true,false},{false,false}})[0][0])throw new AssertionError("sparse must disappear");
  if(!next(new boolean[][]{{true,true},{false,false}})[0][0])throw new AssertionError("half must survive");
  StringBuilder csv=new StringBuilder("sprite,level,size,binary_texel_coverage\n");
  try(ZipFile zip=new ZipFile(args[1])){for(String name:new String[]{"oak_leaves","short_grass","tall_grass_top","vine","fern"}){
   BufferedImage im=ImageIO.read(zip.getInputStream(zip.getEntry("assets/minecraft/textures/block/"+name+".png")));
   boolean[][] p=new boolean[im.getWidth()][im.getWidth()];for(int y=0;y<p.length;y++)for(int x=0;x<p.length;x++)p[y][x]=(im.getRGB(x,y)>>>24)>=128;
   for(int level=0;;level++){csv.append(name+","+level+","+p.length+","+coverage(p)+"\n");if(p.length==1)break;p=next(p);}
  }}
  Path dest=Path.of(args[0]).resolve("docs/validation/iq-evidence-2026-10-19/round7/binary-occupancy.csv");Files.writeString(dest,csv);System.out.println(csv);
 }
}
