import java.awt.*;
import java.awt.image.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
public class Round6Evidence {
 public static void main(String[] a)throws Exception{
  Path root=Path.of(a[0]),out=root.resolve("build/validation/round6");
  String[] labels=a[1].split(",");int x=Integer.parseInt(a[2]),y=Integer.parseInt(a[3]),w=Integer.parseInt(a[4]),h=Integer.parseInt(a[5]);
  BufferedImage sheet=new BufferedImage(w*2,(h+24)*labels.length,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();
  for(int r=0;r<labels.length;r++){String label=labels[r];g.setColor(Color.WHITE);g.drawString(label+" frames 0 / 3",5,r*(h+24)+17);for(int c=0;c<2;c++){BufferedImage im=ImageIO.read(out.resolve(label).resolve(String.format("frame_%03d.png",c==0?0:3)).toFile());g.drawImage(im.getSubimage(x,y,w,h),c*w,r*(h+24)+24,null);}}
  g.dispose();ImageIO.write(sheet,"png",out.resolve(a[6]+".png").toFile());
 }
}
