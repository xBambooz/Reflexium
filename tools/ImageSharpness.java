import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class ImageSharpness {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            args = new String[] {
                "C:/Users/Ryan/source/repos/Reflexium/run/screenshots/iq_native_1080p.png",
                "C:/Users/Ryan/source/repos/Reflexium/run/screenshots/iq_dlss_quality_1080p.png",
                "C:/Users/Ryan/source/repos/Reflexium/run/screenshots/iq_dlss_ultra_perf_1080p.png"
            };
        }
        for (String path : args) {
            File f = new File(path);
            if (!f.exists()) {
                System.out.println("File not found: " + path);
                continue;
            }
            BufferedImage img = ImageIO.read(f);
            // Measure distant terrain region (e.g. center-right mountains / trees)
            // x from 0.4 to 0.8, y from 0.3 to 0.6
            int x0 = (int) (img.getWidth() * 0.35);
            int y0 = (int) (img.getHeight() * 0.30);
            int w = (int) (img.getWidth() * 0.45);
            int h = (int) (img.getHeight() * 0.30);
            
            double lapVar = computeLaplacianVariance(img, x0, y0, w, h);
            double tenengrad = computeTenengrad(img, x0, y0, w, h);
            System.out.printf("%-30s | Dimensions: %dx%d | Distant Region: [%d,%d %dx%d] | Laplacian Variance: %8.2f | Tenengrad Gradient: %10.2f%n",
                    f.getName(), img.getWidth(), img.getHeight(), x0, y0, w, h, lapVar, tenengrad);
        }
    }

    private static double computeLaplacianVariance(BufferedImage img, int x0, int y0, int w, int h) {
        double[][] gray = new double[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x0 + x, y0 + y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[x][y] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                // Laplacian kernel: [0, 1, 0; 1, -4, 1; 0, 1, 0]
                double lap = gray[x + 1][y] + gray[x - 1][y] + gray[x][y + 1] + gray[x][y - 1] - 4 * gray[x][y];
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private static double computeTenengrad(BufferedImage img, int x0, int y0, int w, int h) {
        double[][] gray = new double[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x0 + x, y0 + y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[x][y] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        double sumSq = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                // Sobel
                double gx = (gray[x + 1][y - 1] + 2 * gray[x + 1][y] + gray[x + 1][y + 1])
                          - (gray[x - 1][y - 1] + 2 * gray[x - 1][y] + gray[x - 1][y + 1]);
                double gy = (gray[x - 1][y + 1] + 2 * gray[x][y + 1] + gray[x + 1][y + 1])
                          - (gray[x - 1][y - 1] + 2 * gray[x][y - 1] + gray[x + 1][y - 1]);
                sumSq += (gx * gx + gy * gy);
                count++;
            }
        }
        return sumSq / count;
    }
}
