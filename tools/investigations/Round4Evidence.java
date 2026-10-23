import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Locale;

/** Native-pixel contact sheets and descriptive RGB metrics for the Round 4 captures. */
class Round4Evidence {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]), source=root.resolve("build/validation/round4"), out=root.resolve("docs/validation/iq-evidence-2026-10-19/round4");
  Files.createDirectories(out);
  StringBuilder metrics=new StringBuilder("[\n");
  for(String pack:new String[]{"vanilla","bsl"})for(String scene:new String[]{"smoke","rain","fast"}) {
   String[] variants={"native-0","quality-0","quality-1","performance-0","performance-1"};
   int w=500,h=400, sx=scene.equals("fast")?1200:850,sy=scene.equals("fast")?450:450;
   BufferedImage sheet=new BufferedImage(w*2,(h+28)*variants.length,BufferedImage.TYPE_INT_RGB);
   Graphics2D g=sheet.createGraphics();g.setColor(new Color(25,25,25));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());g.setFont(new Font("SansSerif",Font.PLAIN,15));
   for(int row=0;row<variants.length;row++)for(int col=0;col<2;col++) {
    int frame=col==0?0:3;
    String prefix="final-"+pack+"-"+scene+"-"+variants[row];
    BufferedImage im=ImageIO.read(source.resolve(prefix+"/frame_%03d.png".formatted(frame)).toFile());
    if(im.getWidth()!=2560||im.getHeight()!=1440)throw new AssertionError("wrong capture dimensions");
    g.drawImage(im.getSubimage(sx,sy,w,h),col*w,row*(h+28)+28,null);g.setColor(Color.WHITE);g.drawString(pack+" "+scene+" "+variants[row]+" frame "+frame,col*w+5,row*(h+28)+19);
   }
   g.dispose();ImageIO.write(sheet,"png",out.resolve(pack+"-"+scene+".png").toFile());
   if(!scene.equals("smoke"))continue;
   BufferedImage nativeIm=ImageIO.read(source.resolve("final-"+pack+"-smoke-native-0/frame_003.png").toFile());
   for(int row=1;row<variants.length;row++) {
    BufferedImage im=ImageIO.read(source.resolve("final-"+pack+"-smoke-"+variants[row]+"/frame_003.png").toFile());
    double sum=0;int x0=400,y0=1100,rw=1000,rh=180;
    for(int y=y0;y<y0+rh;y++)for(int x=x0;x<x0+rw;x++) {
     int a=nativeIm.getRGB(x,y),b=im.getRGB(x,y);
     for(int shift:new int[]{0,8,16}) {int d=((a>>shift)&255)-((b>>shift)&255);sum+=d*d;}
    }
    double mse=sum/(rw*rh*3.0),psnr=10*Math.log10(255*255/mse);
    if(metrics.length()>2)metrics.append(",\n");
    metrics.append(String.format(Locale.ROOT," {\"pack\":\"%s\",\"variant\":\"%s\",\"region\":\"floor 400,1100,1000,180\",\"mse\":%.6f,\"psnr\":%.4f}",pack,variants[row],mse,psnr));
   }
  }
  metrics.append("\n]\n");Files.writeString(out.resolve("metrics.json"),metrics);System.out.println(metrics);
  for(String mode:new String[]{"native","quality","performance"}) {
   Files.copy(source.resolve("occlusion-final-"+mode+"/frame_001.png"),out.resolve("occlusion-"+mode+".png"),StandardCopyOption.REPLACE_EXISTING);
  }
  String[] bursts={"native-0","quality-0","quality-1","performance-0","performance-1"};
  BufferedImage sheet=new BufferedImage(1000,428*bursts.length,BufferedImage.TYPE_INT_RGB);
  Graphics2D g=sheet.createGraphics();g.setFont(new Font("SansSerif",Font.PLAIN,15));
  for(int row=0;row<bursts.length;row++)for(int col=0;col<2;col++) {
   BufferedImage im=ImageIO.read(source.resolve("burst-"+bursts[row]+"/frame_%03d.png".formatted(col)).toFile());
   g.drawImage(im.getSubimage(1100,450,500,400),col*500,row*428+28,null);g.setColor(Color.WHITE);g.drawString("block/flame "+bursts[row]+" frame "+col,col*500+5,row*428+19);
  }
  g.dispose();ImageIO.write(sheet,"png",out.resolve("vanilla-burst.png").toFile());
  for(String effect:new String[]{"smoke","rain","burst"})for(String variant:bursts) {
   String prefix=effect.equals("burst")?"burst-"+variant:"final-vanilla-"+effect+"-"+variant;
   BufferedImage im=ImageIO.read(source.resolve(prefix+"/frame_000.png").toFile());
   ImageIO.write(im.getSubimage(effect.equals("burst")?1050:800,420,728,360),"png",source.resolve("preview-"+effect+"-"+variant+".png").toFile());
  }
 }
}
