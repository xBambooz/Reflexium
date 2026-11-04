import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** ROI metrics in 8-bit display luma. Run: java tools/PackDitherMetrics.java root x y w h label. */
class PackDitherMetrics {
    static double[] luma(BufferedImage image, int x, int y, int w, int h) {
        double[] values = new double[w*h];
        for (int j=0;j<h;j++) for(int i=0;i<w;i++) {
            int c=image.getRGB(x+i,y+j);
            values[j*w+i]=0.2126*((c>>16)&255)+0.7152*((c>>8)&255)+0.0722*(c&255);
        }
        return values;
    }
    static double highPass(double[] a,int i,int w) {
        return a[i]-(a[i-1]+a[i+1]+a[i-w]+a[i+w])/4;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        int x=Integer.parseInt(args[1]), y=Integer.parseInt(args[2]), w=Integer.parseInt(args[3]), h=Integer.parseInt(args[4]);
        String label=args[5];
        List<double[]> reference=null;
        for(String mode:List.of("native","before","after")) {
            List<Path> files;
            try(var stream=Files.list(root.resolve(mode))) { files=stream.filter(p->p.toString().endsWith(".png")).sorted().toList(); }
            if(files.size()!=8) throw new IllegalArgumentException("Expected 8 frames: "+mode+" got "+files.size());
            List<double[]> frames=new ArrayList<>();
            Path roiDir=root.resolve("roi-"+label).resolve(mode);
            Files.createDirectories(roiDir);
            for(Path file:files) {
                BufferedImage image=ImageIO.read(file.toFile());
                frames.add(luma(image,x,y,w,h));
                ImageIO.write(image.getSubimage(x,y,w,h),"png",roiDir.resolve(file.getFileName()).toFile());
            }
            BufferedImage first=ImageIO.read(files.getFirst().toFile());
            ImageIO.write(first.getSubimage(x,y,w,h),"png",root.resolve(mode+"-"+label+"-crop.png").toFile());
            if(mode.equals("native")) reference=frames;
            double std=0, hp=0, spatial=0;
            for(int i=0;i<w*h;i++) {
                double mean=0; for(var f:frames) mean+=f[i]/frames.size();
                double variance=0; for(var f:frames) variance+=(f[i]-mean)*(f[i]-mean)/frames.size();
                std+=Math.sqrt(variance)/(w*h);
            }
            int n=0;
            for(int j=1;j<h-1;j++) for(int i=1;i<w-1;i++) {
                int k=j*w+i;
                double residual=highPass(frames.getFirst(),k,w)-highPass(reference.getFirst(),k,w);
                hp+=residual*residual;
                double high=highPass(frames.getFirst(),k,w); spatial+=high*high; n++;
            }
            System.out.printf(Locale.ROOT,"%s %s mean temporal SD=%.5f; high-pass residual RMS vs native=%.5f; high-pass RMS=%.5f%n",label,mode,std,Math.sqrt(hp/n),Math.sqrt(spatial/n));
        }
    }
}
