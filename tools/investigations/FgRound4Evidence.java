import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
public class FgRound4Evidence {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]), out=root.resolve("docs/validation/fg-evidence-2026-10-19/round4");Files.createDirectories(out);
  for(String scene:new String[]{"smoke","rain"}){
   BufferedImage sheet=new BufferedImage(1800,2080,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();g.setColor(Color.BLACK);g.fillRect(0,0,1800,2080);g.setFont(new Font("SansSerif",Font.PLAIN,22));
   int row=0;
   for(String mult:new String[]{"2","auto"})for(String output:new String[]{"real.png","generated_0.png"}){
    int col=0;for(String arm:new String[]{"before","after"}){
     BufferedImage image=ImageIO.read(root.resolve("build/fg-round4/"+arm+"-"+scene+"-"+mult+"/"+output).toFile());
     // Native-pixel crops preserve sprite and rain edges, with no resampling.
     g.drawImage(image.getSubimage(1050,150,900,480),col*900,row*520+40,null);
     g.setColor(Color.WHITE);g.drawString(arm+" "+scene+" "+(mult.equals("auto")?"Auto":mult+"x")+" "+output,col*900+8,row*520+27);col++;
    }row++;
   }
   g.dispose();ImageIO.write(sheet,"PNG",out.resolve(scene+".png").toFile());
  }
 }
}
