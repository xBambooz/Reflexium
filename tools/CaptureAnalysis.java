import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Single-file analysis for the upscaling quality validation. Run with: java tools/CaptureAnalysis.java <command> ...
 *
 *   crop <in> <out> <x> <y> <w> <h> <scale>        nearest-neighbour crop for side-by-side images
 *   metrics <reference> <test> <x> <y> <w> <h>     detail correlation, Laplacian energy, MAE, PSNR in a matched crop
 *   stability <x> <y> <w> <h> <frame>...           mean luma change between consecutive frames in a crop
 *   capture-check <capture.json> [maxSamples]      motion vectors against the metadata's own matrices
 *   motion-stats <capture_motion.png> <x> <y> <w> <h>  decoded motion over a crop (render pixels)
 *
 * Absolute Laplacian energy rewards shimmer, so metrics reports it next to a high-pass correlation with the
 * reference: only detail that is in the same place as the reference counts as detail.
 */
public final class CaptureAnalysis {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) throw new IllegalArgumentException("command required");
		switch (args[0]) {
			case "crop" -> crop(args);
			case "metrics" -> metrics(args);
			case "stability" -> stability(args);
			case "capture-check" -> captureCheck(args);
			case "motion-stats" -> motionStats(args);
			case "self-check" -> decoderCheck();
			default -> throw new IllegalArgumentException("unknown command " + args[0]);
		}
	}

	private static BufferedImage read(String path) throws Exception {
		BufferedImage image = ImageIO.read(new File(path));
		if (image == null) throw new IllegalArgumentException("not an image: " + path);
		return image;
	}

	private static double[][] luma(BufferedImage image, int x, int y, int w, int h) {
		double[][] out = new double[h][w];
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int p = image.getRGB(x + i, y + j);
				out[j][i] = 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
			}
		}
		return out;
	}

	private static void crop(String[] a) throws Exception {
		BufferedImage in = read(a[1]);
		int x = Integer.parseInt(a[3]), y = Integer.parseInt(a[4]), w = Integer.parseInt(a[5]), h = Integer.parseInt(a[6]);
		int scale = Integer.parseInt(a[7]);
		BufferedImage out = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
		for (int j = 0; j < h * scale; j++) {
			for (int i = 0; i < w * scale; i++) out.setRGB(i, j, in.getRGB(x + i / scale, y + j / scale));
		}
		Files.createDirectories(Path.of(a[2]).toAbsolutePath().getParent());
		ImageIO.write(out, "png", new File(a[2]));
	}

	private static double[][] highPass(double[][] luma) {
		int h = luma.length, w = luma[0].length;
		double[][] out = new double[h][w];
		for (int j = 1; j < h - 1; j++) {
			for (int i = 1; i < w - 1; i++) {
				double blur = 0;
				for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) blur += luma[j + dj][i + di];
				out[j][i] = luma[j][i] - blur / 9.0;
			}
		}
		return out;
	}

	private static double laplacianEnergy(double[][] luma) {
		int h = luma.length, w = luma[0].length;
		double sum = 0;
		for (int j = 1; j < h - 1; j++) {
			for (int i = 1; i < w - 1; i++) {
				sum += Math.abs(4 * luma[j][i] - luma[j - 1][i] - luma[j + 1][i] - luma[j][i - 1] - luma[j][i + 1]);
			}
		}
		return sum / ((double) (w - 2) * (h - 2));
	}

	private static void metrics(String[] a) throws Exception {
		BufferedImage reference = read(a[1]), test = read(a[2]);
		int x = Integer.parseInt(a[3]), y = Integer.parseInt(a[4]), w = Integer.parseInt(a[5]), h = Integer.parseInt(a[6]);
		double[][] r = luma(reference, x, y, w, h), t = luma(test, x, y, w, h);
		double[][] hr = highPass(r), ht = highPass(t);
		double mae = 0, mse = 0, dot = 0, nr = 0, nt = 0;
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				double d = r[j][i] - t[j][i];
				mae += Math.abs(d);
				mse += d * d;
				dot += hr[j][i] * ht[j][i];
				nr += hr[j][i] * hr[j][i];
				nt += ht[j][i] * ht[j][i];
			}
		}
		double n = (double) w * h;
		double psnr = mse == 0 ? 99.0 : 10 * Math.log10(255.0 * 255.0 / (mse / n));
		double corr = nr == 0 || nt == 0 ? 0 : dot / Math.sqrt(nr * nt);
		System.out.printf(Locale.ROOT, "{\"mae\":%.3f,\"psnr\":%.2f,\"detail_correlation\":%.4f,\"laplacian_reference\":%.3f,\"laplacian_test\":%.3f}%n",
				mae / n, psnr, corr, laplacianEnergy(r), laplacianEnergy(t));
	}

	private static void stability(String[] a) throws Exception {
		int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
		List<double[][]> frames = new ArrayList<>();
		for (int i = 5; i < a.length; i++) frames.add(luma(read(a[i]), x, y, w, h));
		double total = 0;
		int pairs = 0;
		for (int f = 1; f < frames.size(); f++) {
			double sum = 0;
			for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) sum += Math.abs(frames.get(f)[j][i] - frames.get(f - 1)[j][i]);
			total += sum / ((double) w * h);
			pairs++;
		}
		System.out.printf(Locale.ROOT, "{\"frames\":%d,\"mean_frame_to_frame_luma_change\":%.4f}%n", frames.size(), pairs == 0 ? 0 : total / pairs);
	}

	// --- capture decoding -------------------------------------------------------------------------

	/** Screenshot readback keeps RGB and forces alpha opaque: R = x[11:4], G = x[3:0] << 4 | y[11:8], B = y[7:0], 1/32 px, +-64 px. */
	private static double[] motionAt(BufferedImage image, int x, int y) {
		int p = image.getRGB(x, y);
		int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
		int qx = (r << 4) | (g >> 4);
		int qy = ((g & 15) << 8) | b;
		return new double[] {qx / 32.0 - 64.0, qy / 32.0 - 64.0};
	}
	private static double depthAt(BufferedImage image, int x, int y) {
		int p = image.getRGB(x, y);
		int d = ((p >> 16) & 255) << 16 | ((p >> 8) & 255) << 8 | (p & 255);
		return d / 16777215.0;
	}

	private static void decoderCheck() {
		BufferedImage sample = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		// Hand-computed RGB bytes for (0, 0), (1, -1), (-64, 63.96875).
		int[] encoded = {0x800800, 0x8207e0, 0x000fff};
		double[][] expected = {{0, 0}, {1, -1}, {-64, 63.96875}};
		for (int i = 0; i < encoded.length; i++) {
			sample.setRGB(0, 0, encoded[i]);
			double[] actual = motionAt(sample, 0, 0);
			if (!Arrays.equals(actual, expected[i])) throw new AssertionError("motion decoder " + i);
		}
		sample.setRGB(0, 0, 0xffffff);
		if (depthAt(sample, 0, 0) != 1) throw new AssertionError("depth decoder");
		sample.setRGB(0, 0, 0);
		if (depthAt(sample, 0, 0) != 0) throw new AssertionError("depth clear decoder");
		System.out.println("Capture RGB motion/depth decoder checks passed");
	}

	private static double[] mul(double[] m, double x, double y, double z, double w) {
		return new double[] {
				m[0] * x + m[4] * y + m[8] * z + m[12] * w,
				m[1] * x + m[5] * y + m[9] * z + m[13] * w,
				m[2] * x + m[6] * y + m[10] * z + m[14] * w,
				m[3] * x + m[7] * y + m[11] * z + m[15] * w};
	}

	private static double[] numberArray(String json, String key) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
		if (!m.find()) throw new IllegalArgumentException("no array " + key);
		String[] parts = m.group(1).split(",");
		double[] out = new double[parts.length];
		for (int i = 0; i < out.length; i++) out[i] = Double.parseDouble(parts[i].trim());
		return out;
	}

	private static double number(String json, String key) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.eE+-]+)").matcher(json);
		if (!m.find()) throw new IllegalArgumentException("no number " + key);
		return Double.parseDouble(m.group(1));
	}

	private static String pathOf(String json, String block) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + block + "\"\\s*:\\s*\\{\\s*\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
		if (!m.find()) throw new IllegalArgumentException("no file " + block);
		return m.group(1).replace("\\\\", "\\");
	}
	private static void motionStats(String[] a) throws Exception {
		BufferedImage image = read(a[1]);
		int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
		double maxMagnitude = 0, sum = 0;
		int moving = 0;
		for (int j = y; j < y + h; j++) {
			for (int i = x; i < x + w; i++) {
				double[] m = motionAt(image, i, j);
				double magnitude = Math.hypot(m[0], m[1]);
				maxMagnitude = Math.max(maxMagnitude, magnitude);
				sum += magnitude;
				if (magnitude > 0.05) moving++;
			}
		}
		System.out.printf(Locale.ROOT, "{\"pixels\":%d,\"moving_pixels\":%d,\"mean_px\":%.4f,\"max_px\":%.4f}%n", w * h, moving, sum / ((double) w * h), maxMagnitude);
	}

	/**
	 * For a grid of pixels, recompute the motion the metadata's matrices imply from the decoded depth and
	 * compare it with the decoded motion vector. Static terrain must agree; moving objects legitimately differ.
	 */
	private static void captureCheck(String[] a) throws Exception {
		String meta = Files.readString(Path.of(a[1]));
		if (number(meta, "capture_encoding_version") != 1) throw new IllegalArgumentException("unsupported capture encoding");
		BufferedImage motion = read(pathOf(meta, "motion"));
		BufferedImage depth = read(pathOf(meta, "depth"));
		double[] currentToPrevious = numberArray(meta, "current_to_previous");
		double jx = number(meta, "ndc_x"), jy = number(meta, "ndc_y");		int w = motion.getWidth(), h = motion.getHeight();
		List<Double> residuals = new ArrayList<>();
		int step = Math.max(8, w / 160);
		int skippedBackground = 0;
		for (int y = step; y < h - step; y += step) {
			for (int x = step; x < w - step; x += step) {
				double d = depthAt(depth, x, y);
				if (d <= 0.0) { skippedBackground++; continue; }
				double ndcX = (x + 0.5) / w * 2.0 - 1.0;
				double ndcY = (y + 0.5) / h * 2.0 - 1.0;
				double[] prev = mul(currentToPrevious, ndcX, ndcY, d, 1.0);
				if (prev[3] == 0) continue;
				double px = prev[0] / prev[3], py = prev[1] / prev[3];
				double expectedX = (px - (ndcX - jx)) * 0.5 * w;
				double expectedY = (py - (ndcY - jy)) * 0.5 * h;
				double[] m = motionAt(motion, x, y);
				residuals.add(Math.hypot(m[0] - expectedX, m[1] - expectedY));
			}
		}
		residuals.sort(Double::compare);
		int n = residuals.size();
		if (n == 0) throw new IllegalStateException("no samples");
		long inside = residuals.stream().filter(r -> r <= 0.25).count();
		System.out.printf(Locale.ROOT,
				"{\"samples\":%d,\"sky_or_cleared_skipped\":%d,\"median_px\":%.4f,\"p90_px\":%.4f,\"p99_px\":%.4f,\"within_quarter_pixel\":%.4f}%n",
				n, skippedBackground, residuals.get(n / 2), residuals.get((int) (n * 0.90)), residuals.get(Math.min(n - 1, (int) (n * 0.99))),
				inside / (double) n);
	}
}
