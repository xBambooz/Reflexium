import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Offline check of real GPU captures, plus original-scale comparison sheets. */
class Round5Evidence {
    static Path root, out, evidence;
    static BufferedImage read(String name) throws Exception { return ImageIO.read(out.resolve(name).toFile()); }
    static long[] colors(BufferedImage im, Rectangle r) {
        long red=0,green=0;
        for(int y=r.y;y<r.y+r.height;y++)for(int x=r.x;x<r.x+r.width;x++) {
            int rgb=im.getRGB(x,y)&0xffffff;
            if(rgb==0xff0000)red++;if(rgb==0x00ff00)green++;
        }
        return new long[]{red,green};
    }
    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]);out=root.resolve("build/validation/round5");evidence=root.resolve("docs/validation/iq-evidence-2026-10-19/round5");Files.createDirectories(evidence);
        StringBuilder metrics=new StringBuilder("{\n");
        for(String stage:List.of("baseline","fixed"))for(String distance:List.of("near","far")) {
            String file=stage+"-depth-"+distance+".png";var im=read(file);
            var all=colors(im,new Rectangle(0,0,im.getWidth(),im.getHeight()));
            var panel=colors(im,new Rectangle(960,490,480,380));
            metrics.append(String.format(Locale.ROOT,"  \"%s\": {\"red\":%d,\"green\":%d,\"panel_red\":%d,\"panel_green\":%d},\n",file,all[0],all[1],panel[0],panel[1]));
            Files.copy(out.resolve(file),evidence.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        }
        // This screen corner contained a stale close leaf wall after moving the camera far away.
        var before=colors(read("baseline-depth-far.png"),new Rectangle(0,0,700,650));
        var after=colors(read("fixed-depth-far.png"),new Rectangle(0,0,700,650));
        if(before[0]<10000||after[0]!=0||after[1]!=0)throw new AssertionError("Stale coverage control failed "+Arrays.toString(before)+" / "+Arrays.toString(after));
        var near=colors(read("fixed-depth-near.png"),new Rectangle(960,490,480,380));
        if(near[1]<10000||near[0]>100)throw new AssertionError("Visible near foliage rejected "+Arrays.toString(near));
        for(String file:List.of("no-clear-far.png","clear-restored-far.png","final-depth-near.png","final-depth-far.png"))
            Files.copy(out.resolve(file),evidence.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        var dirty=colors(read("no-clear-far.png"),new Rectangle(400,150,500,450));
        var clean=colors(read("clear-restored-far.png"),new Rectangle(400,150,500,450));
        if(dirty[0]<10000||clean[0]!=0||clean[1]!=0)throw new AssertionError("Same-session clear A/B failed");
        metrics.append(String.format(Locale.ROOT,"  \"same_session_stale_roi\": {\"clear_off_red\":%d,\"clear_on_red\":%d,\"clear_on_green\":%d},\n",dirty[0],clean[0],clean[1]));
        metrics.setLength(metrics.length()-2);metrics.append("\n}\n");Files.writeString(evidence.resolve("coverage.json"),metrics);
        sheet("grass-pan",new String[]{"moving-native","moving-before-cnn","moving-after-cnn","moving-before-k","moving-after-k"},new Rectangle(1550,650,800,300));
        sheet("leaves-pan",new String[]{"dense-native","dense-before-k","dense-after-k","dense-after-cnn"},new Rectangle(700,300,800,600));
        System.out.println("Coverage assertions passed; wrote evidence");
    }
    static void sheet(String name,String[] labels,Rectangle crop)throws Exception {
        var sheet=new BufferedImage(crop.width*3,(crop.height+28)*labels.length,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
        g.setColor(Color.DARK_GRAY);g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,16));
        for(int row=0;row<labels.length;row++)for(int col=0;col<3;col++){
            int index=col+1;var im=read(labels[row]+String.format("/frame_%03d.png",index));
            g.drawImage(im.getSubimage(crop.x,crop.y,crop.width,crop.height),col*crop.width,row*(crop.height+28)+28,null);
            g.setColor(Color.WHITE);g.drawString(labels[row]+" frame "+index,col*crop.width+6,row*(crop.height+28)+20);
        }
        g.dispose();ImageIO.write(sheet,"png",evidence.resolve(name+".png").toFile());
    }
}
