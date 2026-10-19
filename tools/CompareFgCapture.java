import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** java tools/CompareFgCapture.java <batch directory>. Compare scene pixels, excluding the HUD. */
class CompareFgCapture {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        BufferedImage real = ImageIO.read(dir.resolve("real.png").toFile());
        BufferedImage first = ImageIO.read(dir.resolve("generated_0.png").toFile());
        compare("real/generated0", real, first);
        if (java.nio.file.Files.isRegularFile(dir.resolve("generated_1.png"))) {
            BufferedImage second = ImageIO.read(dir.resolve("generated_1.png").toFile());
            compare("real/generated1", real, second);
            compare("generated0/generated1", first, second);
        }
    }
    static void compare(String name, BufferedImage a, BufferedImage b) {
        long changed = 0, error = 0, pixels = 0;
        for (int y = 0; y < a.getHeight() * 3 / 4; y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                if (p != q) changed++;
                for (int shift = 0; shift <= 16; shift += 8) error += Math.abs((p >> shift & 255) - (q >> shift & 255));
                pixels++;
            }
        }
        System.out.printf("%s: changed=%.4f%%, mean RGB error=%.6f/255%n", name, 100.0 * changed / pixels, error / (pixels * 3.0));
        if (changed == 0) throw new AssertionError(name + " is an exact scene duplicate");
    }
}
