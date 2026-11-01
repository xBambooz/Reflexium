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
			case "drawn-view-check" -> captureCheck(args);
			case "motion-stats" -> motionStats(args);
			case "glyph-diff" -> glyphDiff(args);
			case "registered-stability" -> registeredStability(args);
			case "continuity" -> continuity(args);
			case "ui-compose" -> uiCompose(args);
			case "layer-compose" -> uiCompose(args);
			case "hdr-preview" -> hdrPreview(args);
			case "pack-leaf-check" -> packLeafCheck(args);
			case "cloud-check" -> cloudCheck(args);
			case "self-check" -> decoderCheck();
			default -> throw new IllegalArgumentException("unknown command " + args[0]);
		}
	}

	/** glyph-diff <normal prefix> <omitted prefix> <x> <y> <w> <h>, on no-jitter frozen pairs. */
	private static void glyphDiff(String[] args) throws Exception {
		BufferedImage color = read(args[1] + "_input.png"), blank = read(args[2] + "_input.png");
		BufferedImage depth = read(args[1] + "_depth.png"), blankDepth = read(args[2] + "_depth.png");
		BufferedImage motion = read(args[1] + "_motion.png"), blankMotion = read(args[2] + "_motion.png");
		int x = Integer.parseInt(args[3]), y = Integer.parseInt(args[4]);
		int w = Integer.parseInt(args[5]), h = Integer.parseInt(args[6]);
		int glyphs = 0, changedDepth = 0, changedMotion = 0;
		double depthDelta = 0, motionMagnitude = 0;
		for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) {
			if (!glyphDifference(color.getRGB(i, j), blank.getRGB(i, j))) continue;
			glyphs++;
			double delta = Math.abs(depthAt(depth, i, j) - depthAt(blankDepth, i, j));
			if (delta > 1.0 / 16777215.0) changedDepth++;
			depthDelta = Math.max(depthDelta, delta);
			double[] m = motionAt(motion, i, j), b = motionAt(blankMotion, i, j);
			if (!Arrays.equals(m, b)) changedMotion++;
			motionMagnitude += Math.hypot(m[0], m[1]);
		}
		if (glyphs == 0) throw new AssertionError("no glyph difference in selected ROI");
		System.out.printf(Locale.ROOT, "{\"glyph_pixels\":%d,\"changed_depth_pixels\":%d,\"max_depth_delta\":%.9f,\"changed_motion_pixels\":%d,\"glyph_mean_motion_px\":%.6f}%n",
				glyphs, changedDepth, depthDelta, changedMotion, motionMagnitude / glyphs);
	}

	private static boolean glyphDifference(int a, int b) {
		for (int shift : new int[]{0, 8, 16}) if (Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)) > 24) return true;
		return false;
	}

	/** registered-stability <fov degrees> <reference yaw> <pitch> <x> <y> <w> <h> <TSV: image TAB yaw>. */
	private static void registeredStability(String[] args) throws Exception {
		double fov = Math.toRadians(Double.parseDouble(args[1]));
		double referenceYaw = Double.parseDouble(args[2]), pitch = Double.parseDouble(args[3]);
		int x = Integer.parseInt(args[4]), y = Integer.parseInt(args[5]);
		int w = Integer.parseInt(args[6]), h = Integer.parseInt(args[7]);
		double[] previous = null;
		double residual = 0;
		int frames = 0;
		for (String line : Files.readAllLines(Path.of(args[8]))) {
			if (line.isBlank()) continue;
			String[] fields = line.split("\t");
			BufferedImage image = read(fields[0]);
			double yaw = Double.parseDouble(fields[1]);
			double[] registered = new double[w * h];
			for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
				double[] uv = rotatedPixel(x + i, y + j, image.getWidth(), image.getHeight(), fov, referenceYaw, yaw, pitch);
				int sx = (int) Math.floor(uv[0]), sy = (int) Math.floor(uv[1]);
				if (sx < 0 || sy < 0 || sx + 1 >= image.getWidth() || sy + 1 >= image.getHeight())
					throw new IllegalArgumentException("registered ROI leaves image");
				double fx = uv[0] - sx, fy = uv[1] - sy;
				double a = pixelLuma(image.getRGB(sx, sy)) * (1 - fx) + pixelLuma(image.getRGB(sx + 1, sy)) * fx;
				double b = pixelLuma(image.getRGB(sx, sy + 1)) * (1 - fx) + pixelLuma(image.getRGB(sx + 1, sy + 1)) * fx;
				registered[j * w + i] = a * (1 - fy) + b * fy;
			}
			if (previous != null) for (int i = 0; i < registered.length; i++) residual += Math.abs(registered[i] - previous[i]);
			previous = registered;
			frames++;
		}
		if (frames < 2) throw new IllegalArgumentException("two frames required");
		System.out.printf(Locale.ROOT, "{\"frames\":%d,\"registered_mean_luma_change\":%.6f}%n", frames, residual / ((frames - 1.0) * w * h));
	}

	private static double pixelLuma(int p) { return .2126 * ((p >> 16) & 255) + .7152 * ((p >> 8) & 255) + .0722 * (p & 255); }

	/** continuity <x> <y> <w> <h> <TSV: image TAB yaw>, fixed camera only. Counts sampled outliers, not uncaptured frames. */
	private static void continuity(String[] args) throws Exception {
		int x=Integer.parseInt(args[1]), y=Integer.parseInt(args[2]), w=Integer.parseInt(args[3]), h=Integer.parseInt(args[4]);
		List<double[]> frames=new ArrayList<>();
		double yaw=Double.NaN;
		for (String line : Files.readAllLines(Path.of(args[5]))) {
			if (line.isBlank()) continue;
			String[] fields=line.split("\t");
			double currentYaw=Double.parseDouble(fields[1]);
			if (!Double.isNaN(yaw) && Math.abs(currentYaw-yaw)>0.001) throw new IllegalArgumentException("continuity requires fixed yaw");
			yaw=currentYaw;
			BufferedImage image=read(fields[0]);
			double[] values=new double[w*h];
			for (int j=0;j<h;j++) for(int i=0;i<w;i++) values[j*w+i]=pixelLuma(image.getRGB(x+i,y+j));
			frames.add(values);
		}
		if (frames.size()<3) throw new IllegalArgumentException("three samples required");
		List<Integer> candidates=new ArrayList<>();
		for(int i=1;i<frames.size()-1;i++) if (sampledDip(frames.get(i-1),frames.get(i),frames.get(i+1))) candidates.add(i);
		System.out.printf(Locale.ROOT,"{\"samples\":%d,\"one_sample_candidates\":%s,\"all_rendered_frames_verified\":false}%n",frames.size(),candidates);
	}

	private static boolean sampledDip(double[] before,double[] current,double[] after) {
		int changed=0;
		double restored=0;
		for(int i=0;i<before.length;i++) {
			double a=current[i]-before[i], b=current[i]-after[i];
			if(Math.abs(a)>20 && Math.abs(b)>20 && a*b>0) changed++;
			restored+=Math.abs(before[i]-after[i]);
		}
		return changed>before.length*0.15 && restored/before.length<1.5;
	}

	/** ui-compose <flipped hudless> <unflipped UI RGB> <encoded alpha> <final> <x> <y> <w> <h>. */
	private static void uiCompose(String[] args) throws Exception {
		BufferedImage scene = read(args[1]), ui = read(args[2]), opacity = read(args[3]), output = read(args[4]);
		int x = Integer.parseInt(args[5]), y = Integer.parseInt(args[6]), w = Integer.parseInt(args[7]), h = Integer.parseInt(args[8]);
		int covered = 0, partial = 0, invalidClear = 0;
		double sum = 0, max = 0;
		for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) {
			int a = ui.getRGB(i, j), b = scene.getRGB(i, args[0].equals("ui-compose") ? scene.getHeight() - 1 - j : j), c = output.getRGB(i, j);
			int alpha = opacity.getRGB(i, j) & 255;
			if (alpha > 0) covered++;
			if (alpha > 0 && alpha < 255) partial++;
			if (alpha == 0 && (a & 0xffffff) != 0) invalidClear++;
			for (int shift : new int[]{0, 8, 16}) {
				double expected = ((a >> shift) & 255) + (1 - alpha / 255.0) * ((b >> shift) & 255);
				double error = Math.abs(expected - ((c >> shift) & 255));
				sum += error; max = Math.max(max, error);
			}
		}
		System.out.printf(Locale.ROOT, "{\"covered_pixels\":%d,\"partial_pixels\":%d,\"invalid_clear_pixels\":%d,\"mean_channel_error\":%.6f,\"max_channel_error\":%.6f}%n",
				covered, partial, invalidClear, sum / (3.0 * w * h), max);
		if (covered == 0 || invalidClear != 0) throw new AssertionError("UI coverage contract");
	}

	/** Pure camera rotation; assumes fixed vertical FOV and pitch, with no translation. */
	private static double[] rotatedPixel(double x, double y, int width, int height, double fov, double referenceYaw, double yaw, double pitch) {
		double t = Math.tan(fov / 2), aspect = (double) width / height;
		double rx = ((x + .5) / width * 2 - 1) * t * aspect, ry = (1 - (y + .5) / height * 2) * t;
		double p = Math.toRadians(pitch), d = Math.toRadians(referenceYaw - yaw);
		// Lift the reference eye ray into yaw-only coordinates, rotate it, then restore the fixed pitch.
		double vy = Math.cos(p) * ry - Math.sin(p), vz = Math.sin(p) * ry + Math.cos(p);
		double nx = Math.cos(d) * rx + Math.sin(d) * vz;
		double nz = -Math.sin(d) * rx + Math.cos(d) * vz;
		double ny = Math.cos(p) * vy + Math.sin(p) * nz;
		double depth = -Math.sin(p) * vy + Math.cos(p) * nz;
		return new double[]{(nx / depth / (t * aspect) + 1) * width / 2 - .5, (1 - ny / depth / t) * height / 2 - .5};
	}

	/** Independent world-space cloud drift plus camera reprojection, selected by the cloud slab's height. */
	private static void cloudCheck(String[] args) throws Exception {
		String meta = Files.readString(Path.of(args[1]));
		BufferedImage motion = read(pathOf(meta, "motion")), depth = read(pathOf(meta, "depth"));
		double[] inv = inverse(numberArray(meta, "world_view_projection_jittered")), prev = numberArray(meta, "previous_world_view_projection");
		double cx = number(meta, "x"), cy = number(meta, "y"), cz = number(meta, "z");
		double px = number(meta, "previous_x"), py = number(meta, "previous_y"), pz = number(meta, "previous_z");
		double displacement = number(meta, "vanilla_cloud_previous_displacement_x"), bottom = Double.parseDouble(args[2]);
		double jx = number(meta, "ndc_x"), jy = number(meta, "ndc_y");
		int w = motion.getWidth(), h = motion.getHeight();
		List<Double> errors = new ArrayList<>();
		double expectedEnergy = 0, measuredEnergy = 0;
		for (int y = 8; y < h - 8; y += 4) for (int x = 8; x < w - 8; x += 4) {
			double d = depthAt(depth, x, y); if (d <= 0) continue;
			double nx = (x + .5) / w * 2 - 1, ny = screenshotNdcY(y, h);
			double[] point = mul(inv, nx, ny, d, 1);
			double wx = point[0] / point[3] + cx, wy = point[1] / point[3] + cy, wz = point[2] / point[3] + cz;
			if (wy < bottom - .02 || wy > bottom + 4.02) continue;
			double[] q = mul(prev, wx + displacement - px, wy - py, wz - pz, 1);
			double ex = (q[0] / q[3] - (nx - jx)) * .5 * w, ey = (q[1] / q[3] - (ny - jy)) * .5 * h;
			double[] measured = motionAt(motion, x, y);
			errors.add(Math.hypot(measured[0] - ex, measured[1] - ey));
			expectedEnergy += Math.hypot(ex, ey); measuredEnergy += Math.hypot(measured[0], measured[1]);
		}
		errors.sort(Double::compare);
		if (errors.size() < 100) throw new AssertionError("not enough cloud surface samples");
		int n = errors.size(); double p99 = errors.get((int) (n * .99));
		System.out.printf(Locale.ROOT, "{\"samples\":%d,\"expected_mean_px\":%.6f,\"actual_mean_px\":%.6f,\"p99_error_px\":%.6f}%n", n, expectedEnergy/n, measuredEnergy/n, p99);
		if (expectedEnergy/n < .05 || measuredEnergy < expectedEnergy * .75 || p99 > .05) throw new AssertionError("cloud correspondence failed");
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
		System.out.printf(Locale.ROOT, "{\"mae\":%.3f,\"mse\":%.3f,\"psnr\":%.2f,\"detail_correlation\":%.4f,\"laplacian_reference\":%.3f,\"laplacian_test\":%.3f}%n",
				mae / n, mse / n, psnr, corr, laplacianEnergy(r), laplacianEnergy(t));
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
		if (!sampledDip(new double[]{100,100},new double[]{200,200},new double[]{100,100})
				|| sampledDip(new double[]{100,100},new double[]{101,101},new double[]{100,100})
				|| sampledDip(new double[]{100,100},new double[]{200,200},new double[]{200,200}))
			throw new AssertionError("sampled terrain dip, small noise and persistent cut controls");
		for (double[] pixel : new double[][]{{10, 20}, {1280, 720}, {2500, 1400}}) {
			double[] mapped = rotatedPixel(pixel[0], pixel[1], 2560, 1440, Math.toRadians(70), 90, 90, 10);
			if (Math.hypot(mapped[0] - pixel[0], mapped[1] - pixel[1]) > 1e-8) throw new AssertionError("rotation identity");
		}
		double[] turned = rotatedPixel(1279.5, 719.5, 2560, 1440, Math.PI / 2, 0, 10, 0);
		if (Math.abs(turned[0] - (1279.5 - 720 * Math.tan(Math.toRadians(10)))) > 1e-8)
			throw new AssertionError("camera yaw registration sign and projection");
		if (glyphDifference(0xffffff, 0xe7e7e7) || !glyphDifference(0xffffff, 0xe6ffff))
			throw new AssertionError("glyph difference threshold");
		if (screenshotNdcY(0, 2) != 0.5 || screenshotNdcY(1, 2) != -0.5)
			throw new AssertionError("screenshot rows must be inverted before reprojection");
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
		for (double linear : new double[]{0, 0.1, 1, 4, 100, 1000, 65535}) {
			int q = (int) Math.round(Math.log1p(linear) / Math.log(2) / 16 * 255);
			if (Math.abs(hdrChannel(q) - linear) > (1 + linear) * 0.023) throw new AssertionError("HDR log diagnostic decoder");
		}
		double[] affine = {2,0,0,0, 0,4,0,0, 0,0,8,0, 3,0,0,1};
		double[] point = mul(inverse(affine), 7, 8, 16, 1);
		if (!Arrays.equals(point, new double[]{2,2,2,1})) throw new AssertionError("independent affine inversion");
		System.out.println("Capture RGB motion/depth and HDR diagnostic decoder checks passed");
	}

	private static double hdrChannel(int channel) { return Math.pow(2, 16.0 * channel / 255) - 1; }
	/** Identical Reinhard plus sRGB presentation for both logarithmically encoded linear diagnostic images. */
	private static void hdrPreview(String[] args) throws Exception {
		BufferedImage input = read(args[1]), output = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
			int rgb = input.getRGB(x, y), pixel = 0;
			for (int shift : new int[]{16, 8, 0}) {
				double c = hdrChannel((rgb >> shift) & 255); c /= 1 + c;
				c = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
				pixel |= (int) Math.round(c * 255) << shift;
			}
			output.setRGB(x, y, pixel);
		}
		ImageIO.write(output, "png", new File(args[2]));
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
	private static double[] inverse(double[] matrix) {
		double[][] a = new double[4][8];
		for (int r = 0; r < 4; r++) { for (int c = 0; c < 4; c++) a[r][c] = matrix[c * 4 + r]; a[r][r + 4] = 1; }
		for (int c = 0; c < 4; c++) {
			int pivot = c; for (int r = c + 1; r < 4; r++) if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) pivot = r;
			double[] swap = a[c]; a[c] = a[pivot]; a[pivot] = swap;
			if (Math.abs(a[c][c]) < 1e-12) throw new IllegalArgumentException("singular projection");
			double d = a[c][c]; for (int k = 0; k < 8; k++) a[c][k] /= d;
			for (int r = 0; r < 4; r++) if (r != c) { d = a[r][c]; for (int k = 0; k < 8; k++) a[r][k] -= d * a[c][k]; }
		}
		double[] out = new double[16]; for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) out[c * 4 + r] = a[r][c + 4];
		return out;
	}
	/** Independent numerical correspondence for the standard arena's exposed oak leaves, default pack wave settings.
	 * Reconstruct a world point from depth, solve its undeformed location, then project the previous deformation.
	 * Fragment interpolation of vertex deformation and light-map interpolation limit this to a quarter-pixel check.
	 */
	private static void packLeafCheck(String[] args) throws Exception {
		String meta = Files.readString(Path.of(args[1]));
		if (!meta.contains("\"previous_parameters_valid\":true")) throw new IllegalArgumentException("no valid previous pack parameters");
		BufferedImage motion = read(pathOf(meta, "motion")), depth = read(pathOf(meta, "depth"));
		double[] inv = inverse(numberArray(meta, "world_view_projection_jittered")), prev = numberArray(meta, "previous_world_view_projection");
		double cx = number(meta, "x"), cy = number(meta, "y"), cz = number(meta, "z"), px = number(meta, "previous_x"), py = number(meta, "previous_y"), pz = number(meta, "previous_z");
		double time = number(meta, "wave_time"), previousTime = number(meta, "previous_wave_time"), snow = number(meta, "wave_snow"), previousSnow = number(meta, "previous_wave_snow");
		double jx = number(meta, "ndc_x"), jy = number(meta, "ndc_y");
		int w = motion.getWidth(), h = motion.getHeight(), moving = 0;
		boolean isolated = args.length > 2 && args[2].equals("isolated");
		List<Double> errors = new ArrayList<>();
		double measuredEnergy = 0, expectedEnergy = 0;
		double dotX = 0, dotY = 0, xx = 0, yy = 0;
		for (int y = 8; y < h - 8; y += 4) for (int x = 8; x < w - 8; x += 4) {
			double d = depthAt(depth, x, y); if (d <= 0) continue;
			double nx = (x + .5) / w * 2 - 1, ny = screenshotNdcY(y, h);
			double[] p = mul(inv, nx, ny, d, 1);
			double[] now = {p[0] / p[3] + cx, p[1] / p[3] + cy, p[2] / p[3] + cz};
			if (isolated) {
				if (now[0] < -43.2 || now[0] > -41.8 || now[1] < 136.8 || now[1] > 138.2 || now[2] < 23.8 || now[2] > 25.2) continue;
			} else if (now[0] < -45.2 || now[0] > -40.8 || now[1] < 130.8 || now[1] > 136.2 || now[2] < 21.8 || now[2] > 25.2) continue;
			double[] base = now.clone();
			for (int iteration = 0; iteration < 8; iteration++) {
				double[] offset = leafWave(base, time, snow);
				for (int k = 0; k < 3; k++) base[k] = now[k] - offset[k];
			}
			double[] before = leafWave(base, previousTime, previousSnow);
			double[] q = mul(prev, base[0] + before[0] - px, base[1] + before[1] - py, base[2] + before[2] - pz, 1);
			double ex = (q[0] / q[3] - (nx - jx)) * .5 * w, ey = (q[1] / q[3] - (ny - jy)) * .5 * h;
			double[] actual = motionAt(motion, x, y);
			measuredEnergy += Math.hypot(actual[0], actual[1]);
			expectedEnergy += Math.hypot(ex, ey);
			dotX += actual[0] * ex; dotY += actual[1] * ey; xx += ex * ex; yy += ey * ey;
			if (Math.hypot(ex, ey) > .02) moving++;
			errors.add(Math.hypot(actual[0] - ex, actual[1] - ey));
		}
		errors.sort(Double::compare); if (errors.isEmpty()) throw new IllegalStateException("no arena leaf samples");
		int n = errors.size(); long inside = errors.stream().filter(e -> e <= .25).count();
		System.out.printf(Locale.ROOT, "{\"measured_mean_px\":%.5f,\"expected_mean_px\":%.5f}%n", measuredEnergy / n, expectedEnergy / n);
		System.out.printf(Locale.ROOT, "{\"x_slope\":%.5f,\"y_slope\":%.5f}%n", dotX / xx, dotY / yy);
		System.out.printf(Locale.ROOT, "{\"leaf_samples\":%d,\"nonzero_expected\":%d,\"median_px\":%.4f,\"p99_px\":%.4f,\"within_quarter_pixel\":%.4f,\"scope\":\"default waves, exposed persistent arena leaves\"}%n", n, moving, errors.get(n / 2), errors.get(Math.min(n - 1, (int)(n * .99))), (double)inside / n);
		// This stationary-camera fixture must improve on zero vectors, even when all errors fit the
		// interpolation tolerance. Otherwise camera-only motion can falsely pass at high frame rates.
		double residual = errors.stream().mapToDouble(Double::doubleValue).sum();
		if (moving < 20 || inside < n * .99 || measuredEnergy < expectedEnergy * .25 || residual >= expectedEnergy * .85)
			throw new IllegalStateException("leaf correspondence not established (including nonzero deformation)");
	}
	private static double[] leafWave(double[] p, double seconds, double snow) {
		double x = p[0] * .75, y = p[1] * .375, z = p[2] * .75, t = seconds * 170;
		double magnitude = Math.sin(t * .0027 + x + y) * .04 + .04, a = Math.sin(t * .0127), b = Math.sin(t * .0089), c = Math.sin(t * .0114);
		double scale = .09875 * (1 - snow); // Open-sky lightmap 0.96875, pack's indoor-wave clamp starts at 0.87.
		return new double[]{magnitude * Math.sin(t * .0224 + b + c + x - z + y) * 4 * scale,
				magnitude * Math.sin(t * .0015 + c + a + x) * 3 * scale,
				magnitude * Math.sin(t * .0063 + a + b - x + z + y) * 8 * scale};
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
		boolean drawn = a[0].equals("drawn-view-check");
		double[] inverseDrawn = drawn ? inverse(numberArray(meta, "drawn_world_view_projection")) : null;
		double[] previousDrawn = drawn ? numberArray(meta, "previous_drawn_world_view_projection") : null;
		double dx = drawn ? number(meta, "x") - number(meta, "previous_x") : 0;
		double dy = drawn ? number(meta, "y") - number(meta, "previous_y") : 0;
		double dz = drawn ? number(meta, "z") - number(meta, "previous_z") : 0;
		double jx = number(meta, "ndc_x"), jy = number(meta, "ndc_y");		int w = motion.getWidth(), h = motion.getHeight();
		List<Double> residuals = new ArrayList<>();
		int step = Math.max(8, w / 160);
		int skippedBackground = 0;
		for (int y = step; y < h - step; y += step) {
			for (int x = step; x < w - step; x += step) {
				// Static range geometry, excluding the hand, sky edges and nearby animated plants.
				if (drawn && (x < w / 4 || x >= w * 3 / 4 || y < h / 3 || y >= h * 2 / 3)) continue;
				double d = depthAt(depth, x, y);
				if (d <= 0.0) { skippedBackground++; continue; }
				double ndcX = (x + 0.5) / w * 2.0 - 1.0;
				double ndcY = screenshotNdcY(y, h);
				double[] prev = mul(currentToPrevious, ndcX, ndcY, d, 1.0);
				if (drawn) {
					double[] world = mul(inverseDrawn, ndcX - jx, ndcY - jy, d, 1);
					prev = mul(previousDrawn, world[0] + dx * world[3], world[1] + dy * world[3], world[2] + dz * world[3], world[3]);
				}
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
	/** Screenshot.takeScreenshot writes GPU row y into image row height-y-1. */
	private static double screenshotNdcY(int y, int height) {
		return 1.0 - (y + 0.5) / height * 2.0;
	}
}
