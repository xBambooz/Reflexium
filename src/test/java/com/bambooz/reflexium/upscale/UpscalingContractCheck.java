package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.upscale.dlss.DlssModel;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Vendor-contract checks for the upscaling inputs: material bind selection, mip bias, jitter policy,
 * reprojection against an independent projection, camera metadata, history cuts and model presets.
 * Run by Gradle check; no Minecraft process or graphics device is needed.
 */
public final class UpscalingContractCheck {
	private static final float EPS = 1.0e-4f;

	public static void main(String[] args) {
		materialSelection();
		mipBias();
		samplerReplacement();
		jitterPolicy();
		independentReprojection();
		cloudReprojection();
		cameraMetadata();
		historyCuts();
		modelPresets();
		captureCancellation();
		System.out.println("UpscalingContractCheck passed: material binds, mip bias, sampler ownership, jitter phases, "
				+ "independent reprojection, physical camera metadata, history cuts and model presets");
	}

	private static void cloudReprojection() {
		// Vanilla's cloud texture advances +X in camera-relative sampling, so its world surface moves -X.
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, .05f, 1024f, true);
		float timeDelta = .25f, cameraDelta = .1f;
		Vector4f previous = projection.transform(new Vector4f(2, 3, -20, 1));
		Vector4f current = projection.transform(new Vector4f(2 - .03f * timeDelta - cameraDelta, 3, -20, 1));
		Vector4f predicted = DlssMath.reprojection(projection, projection, cameraDelta + .03f * timeDelta, 0, 0)
				.transform(new Vector4f(current.x / current.w, current.y / current.w, current.z / current.w, 1));
		check(Math.abs(predicted.x / predicted.w - previous.x / previous.w) < EPS, "cloud time and camera displacement reproject to the previous surface");
	}

	private static void captureCancellation() {
		UpscaleCapture request = new UpscaleCapture(true);
		var delivered = new NativeImage(1, 1, false);
		var late = new NativeImage(1, 1, false);
		request.deliver(request.input, delivered);
		check(!delivered.isClosed(), "completed capture images stay alive for the HTTP writer");
		request.cancel();
		request.cancel();
		check(delivered.isClosed(), "cancellation closes completed images even if HTTP never claimed them");
		request.deliver(request.output, late);
		check(late.isClosed(), "late readback images are closed after cancellation");
		check(request.output.isCancelled() && request.metadata.isCancelled(), "uncompleted capture futures are cancelled");
	}

	// 1. The binding boundary selects color materials by name and texture, and nothing else.
	private static void materialSelection() {
		check(MaterialBinding.uniformPath("Sampler0") == MaterialBinding.SAMPLER0, "vanilla material uniform");
		check(MaterialBinding.uniformPath("u_BlockTex") == MaterialBinding.BLOCK_TEX, "Sodium terrain atlas uniform");
		for (String excluded : new String[] {"Sampler1", "Sampler2", "u_LightTex", "DepthSampler", "InSampler", "shadowtex0",
				"gtexture", "colortex0", "sampler0", "Sampler0 ", "", "CloudSampler"}) {
			check(MaterialBinding.uniformPath(excluded) == -1, "'" + excluded + "' is not a color material");
		}
		check(MaterialBinding.uniformPath(null) == -1, "null uniform");
		check(MaterialBinding.isMipmappedColor(5, true), "mipmapped color atlas");
		check(!MaterialBinding.isMipmappedColor(1, true), "one-level targets and GUI sprites keep their sampler");
		check(!MaterialBinding.isMipmappedColor(5, false), "depth textures keep their sampler");
	}

	// 2. log2(render / display) - 1, zero at native resolution and for invalid sizes.
	private static void mipBias() {
		near(DlssMath.mipBias(1280, 1920), -1.5849625f, "Quality");
		near(DlssMath.mipBias(1129, 1920), (float) (Math.log(1129.0 / 1920.0) / Math.log(2.0)) - 1.0f, "Balanced");
		near(DlssMath.mipBias(960, 1920), -2.0f, "Performance");
		near(DlssMath.mipBias(640, 1920), -2.5849625f, "Ultra Performance");
		near(DlssMath.mipBias(1920, 1920), 0.0f, "DLAA / native stays at zero");
		near(DlssMath.mipBias(2560, 1920), 0.0f, "supersampling never biases negatively");
		near(DlssMath.mipBias(0, 1920), 0.0f, "invalid render size");
		near(DlssMath.mipBias(960, 0), 0.0f, "invalid display size");
		near(DlssMath.mipBias(-1, -1), 0.0f, "negative sizes");
	}

	private static final class FakeSampler implements GpuSampler, AppliedLodBias {
		boolean closed;
		final double maxLod;
		final float bias;
		FakeSampler(double maxLod) { this(maxLod, 0.0f); }
		FakeSampler(double maxLod, float bias) { this.maxLod = maxLod; this.bias = bias; }
		public float reflexium$appliedLodBias() { return bias; }
		public AddressMode getAddressModeU() { return AddressMode.CLAMP_TO_EDGE; }
		public AddressMode getAddressModeV() { return AddressMode.CLAMP_TO_EDGE; }
		public FilterMode getMinFilter() { return FilterMode.LINEAR; }
		public FilterMode getMagFilter() { return FilterMode.LINEAR; }
		public int getMaxAnisotropy() { return 1; }
		public OptionalDouble getMaxLod() { return maxLod < 0 ? OptionalDouble.empty() : OptionalDouble.of(maxLod); }
		public boolean isClosed() { return closed; }
		public void close() {
			if (closed) throw new AssertionError("sampler closed twice");
			closed = true;
		}
	}

	// 3. Replacements are idempotent, owned once, and never recreated from a replacement.
	private static void samplerReplacement() {
		List<FakeSampler> created = new ArrayList<>();
		MipBiasSamplers samplers = new MipBiasSamplers((original, bias) -> {
			FakeSampler replacement = new FakeSampler(-1, bias);
			created.add(replacement);
			return replacement;
		});
		FakeSampler atlas = new FakeSampler(-1);
		GpuSampler first = samplers.get(atlas, -2.0f);
		check(first != atlas && created.size() == 1, "mipmapped material is replaced once");
		check(samplers.get(atlas, -2.0f) == first && created.size() == 1, "same original maps to the same replacement");
		check(samplers.get(first, -2.0f) == first && created.size() == 1, "a corrected sampler is not corrected again");
		check(samplers.owns(first) && !samplers.owns(atlas), "ownership tracks replacements only");
		check(samplers.get(atlas, 0.0f) == atlas, "zero bias returns the native sampler");
		check(samplers.get(new FakeSampler(0.0), -2.0f).getMaxLod().orElse(9) == 0.0, "single level samplers are untouched");
		FakeSampler flat = new FakeSampler(0.0);
		check(samplers.get(flat, -2.0f) == flat && created.size() == 1, "max LOD zero keeps its sampler");
		check(samplers.get(null, -2.0f) == null, "null passes through");
		samplers.close();
		check(created.get(0).closed && !atlas.closed, "close releases replacements, never the originals");
		check(samplers.size() == 0, "cache is empty after close");
		GpuSampler again = samplers.get(atlas, -1.5f);
		check(again != first && !again.isClosed() && created.size() == 2, "a new bias after close builds a fresh live sampler");
		samplers.close();
		check(created.get(1).closed, "second generation released");
		FakeSampler nativeBiased = new FakeSampler(-1, -0.25f);
		FakeSampler biased = (FakeSampler) samplers.get(nativeBiased, -2.0f);
		near(biased.bias, -2.25f, "original native LOD bias is preserved");
		check(samplers.get(biased, -2.0f) == biased, "total native and upscale bias remains idempotent");
		check(samplers.get(nativeBiased, 0.0f) == nativeBiased, "DLAA keeps the original native bias");
		samplers.close();
		check(!nativeBiased.closed, "borrowed native-biased sampler stays alive");
	}

	// 4. Jitter phase counts: DLSS at least 32, FSR keeps its ratio policy; sequence stays inside the pixel.
	private static void jitterPolicy() {
		check(DlssMath.jitterPhases(1920, 1920, Upscaler.DLSS) == 32, "DLAA has 32 phases");
		check(DlssMath.jitterPhases(1920, 1920, Upscaler.FSR) == 8, "FSR native keeps AMD's eight phases");
		check(DlssMath.jitterPhases(1280, 1920, Upscaler.DLSS) == 32, "DLSS Quality has at least 32");
		check(DlssMath.jitterPhases(1280, 1920, Upscaler.FSR) == 18, "FSR Quality follows 8 * ratio^2");
		check(DlssMath.jitterPhases(960, 1920, Upscaler.DLSS) == 32, "DLSS Performance has 32");
		check(DlssMath.jitterPhases(960, 1920, Upscaler.FSR) == 32, "FSR Performance has 32");
		check(DlssMath.jitterPhases(640, 1920, Upscaler.DLSS) == 72, "Ultra Performance keeps the ratio requirement");
		check(DlssMath.jitterPhases(640, 1920, Upscaler.FSR) == 72, "FSR Ultra Performance");
		for (int phases : new int[] {32, 72}) {
			Set<String> positions = new HashSet<>();
			for (int index = 1; index <= phases; index++) {
				float x = DlssMath.halton(index, 2) - 0.5f;
				float y = DlssMath.halton(index, 3) - 0.5f;
				check(Math.abs(x) <= 0.5f && Math.abs(y) <= 0.5f, "jitter stays inside the pixel");
				check(positions.add(x + "," + y), "each phase is a distinct sample");
			}
			check(positions.size() == phases, "full sequence of " + phases + " distinct phases");
			// The renderer wraps index % phases + 1: after the last phase it repeats the first.
			int wrapped = phases % phases + 1;
			check(wrapped == 1, "sequence repeats");
		}
		// Stationary camera, changing jitter: zero reconstructed motion.
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 1024, 0.05f, true);
		for (int index = 1; index <= 32; index++) {
			float jx = 2 * (DlssMath.halton(index, 2) - 0.5f) / 1280;
			float jy = 2 * (DlssMath.halton(index, 3) - 0.5f) / 720;
			Matrix4f jittered = new Matrix4f().translation(jx, jy, 0).mul(projection);
			Vector4f motion = shaderMotion(jittered, projection, 0, 0, 0, new Vector4f(0.3f, 0.1f, -7f, 1f), jx, jy, 1280, 720);
			near(motion.x, 0, "stationary camera x motion");
			near(motion.y, 0, "stationary camera y motion");
		}
	}

	/** The fragment shader of dlss_motion.fsh on the CPU, returning the motion vector in render pixels. */
	private static Vector4f shaderMotion(Matrix4f currentJitteredVp, Matrix4f previousVp, float dx, float dy, float dz,
			Vector4f cameraRelativeWorld, float jitterNdcX, float jitterNdcY, int width, int height) {
		Vector4f clip = currentJitteredVp.transform(new Vector4f(cameraRelativeWorld));
		float depth = clip.z / clip.w;
		Vector4f ndc = new Vector4f(clip.x / clip.w, clip.y / clip.w, depth, 1.0f);
		Matrix4f currentToPrevious = DlssMath.reprojection(previousVp, currentJitteredVp, dx, dy, dz);
		Vector4f previous = currentToPrevious.transform(new Vector4f(ndc));
		float px = previous.x / previous.w;
		float py = previous.y / previous.w;
		return new Vector4f((px - (ndc.x - jitterNdcX)) * 0.5f * width, (py - (ndc.y - jitterNdcY)) * 0.5f * height, 0, 1);
	}

	// 4b. Reprojection against projections computed independently of the reprojection matrix: translation on
	// every axis, rotation on every axis, a field of view and aspect change, and asymmetric jitter.
	private static void independentReprojection() {
		int width = 1280, height = 720;
		Matrix4f previousProjection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 1024, 0.05f, true);
		Matrix4f currentProjection = new Matrix4f().setPerspective((float) Math.toRadians(52), 1.9f, 1024, 0.05f, true);
		Quaternionf previousOrientation = new Quaternionf().rotationYXZ(0.3f, -0.2f, 0.05f);
		Quaternionf currentOrientation = new Quaternionf().rotationYXZ(0.62f, 0.1f, -0.08f);
		Vector3f previousPosition = new Vector3f(100.25f, 64.5f, -33.125f);
		Vector3f currentPosition = new Vector3f(100.9f, 63.7f, -32.4f);
		Matrix4f previousView = new Matrix4f().rotation(previousOrientation).invert();
		Matrix4f currentView = new Matrix4f().rotation(currentOrientation).invert();
		Matrix4f previousVp = new Matrix4f(previousProjection).mul(previousView);
		// Asymmetric jitter of a 32-phase sequence member.
		float jx = 2 * (DlssMath.halton(13, 2) - 0.5f) / width;
		float jy = 2 * (DlssMath.halton(13, 3) - 0.5f) / height;
		check(Math.abs(jx) != Math.abs(jy), "jitter is asymmetric");
		Matrix4f currentVp = new Matrix4f(currentProjection).mul(currentView);
		Matrix4f currentJitteredVp = new Matrix4f().translation(jx, jy, 0).mul(currentVp);
		Vector3f delta = new Vector3f(currentPosition).sub(previousPosition);
		int points = 0;
		for (Vector3f world : new Vector3f[] {new Vector3f(102, 65, -38), new Vector3f(97.5f, 60.2f, -40.5f),
				new Vector3f(104.1f, 66.3f, -36.7f), new Vector3f(100, 64, -34)}) {
			Vector4f camRelative = new Vector4f(new Vector3f(world).sub(currentPosition), 1);
			Vector4f currentClip = currentVp.transform(new Vector4f(camRelative));
			if (currentClip.w <= 0) continue;
			Vector4f previousClip = previousVp.transform(new Vector4f(new Vector3f(world).sub(previousPosition), 1));
			if (previousClip.w <= 0) continue;
			float expectedX = (previousClip.x / previousClip.w - currentClip.x / currentClip.w) * 0.5f * width;
			float expectedY = (previousClip.y / previousClip.w - currentClip.y / currentClip.w) * 0.5f * height;
			Vector4f motion = shaderMotion(currentJitteredVp, previousVp, delta.x, delta.y, delta.z, camRelative, jx, jy, width, height);
			check(Math.abs(motion.x - expectedX) < 0.01f && Math.abs(motion.y - expectedY) < 0.01f,
					"reprojected motion " + motion.x + "," + motion.y + " != projected " + expectedX + "," + expectedY);
			points++;
		}
		check(points >= 3, "enough visible reference points exercised the check");
	}

	// 7. FSR planes and FOV come from the pure projection, not the bobbed one.
	private static void cameraMetadata() {
		float fov = (float) Math.toRadians(63);
		Matrix4f physical = new Matrix4f().setPerspective(fov, 16f / 9f, 1024, 0.05f, true);
		DlssMath.CameraMetadata meta = DlssMath.cameraMetadata(physical);
		check(meta.valid(), "pure perspective is valid");
		near(meta.near(), 0.05f, "near plane");
		near(meta.far() / 1024f, 1.0f, "far plane");
		near(meta.verticalFov(), fov, "vertical field of view");
		// View bob translation, bob roll and damage tilt, as multiplied into the drawn projection.
		Matrix4f bob = new Matrix4f().translate(0.12f, -0.07f, 0.0f).rotateZ(0.03f).rotateX(-0.02f);
		Matrix4f drawn = new Matrix4f(physical).mul(bob);
		DlssMath.CameraMetadata distorted = DlssMath.cameraMetadata(drawn);
		check(Math.abs(distorted.near() - 0.05f) > 1.0e-4f || Math.abs(distorted.verticalFov() - fov) > 1.0e-4f,
				"extracting metadata from the bobbed matrix is wrong, which is why it is not used");
		// World reprojection still uses the drawn transform: a point rotates with the bob.
		Vector4f world = new Vector4f(0.4f, 0.2f, -6f, 1f);
		Vector4f withBob = drawn.transform(new Vector4f(world));
		Vector4f without = physical.transform(new Vector4f(world));
		check(Math.abs(withBob.x / withBob.w - without.x / without.w) > 1.0e-3f, "bob changes projected position");
		// Rejects non-finite and impossible values.
		check(!new DlssMath.CameraMetadata(Float.NaN, 1024f, 1f).valid(), "NaN near rejected");
		check(!new DlssMath.CameraMetadata(0.05f, Float.POSITIVE_INFINITY, 1f).valid(), "infinite far rejected");
		check(!new DlssMath.CameraMetadata(0.05f, 0.04f, 1f).valid(), "far before near rejected");
		check(!new DlssMath.CameraMetadata(0.05f, 1024f, 0f).valid(), "zero field of view rejected");
		check(!DlssMath.cameraMetadata(new Matrix4f().zero()).valid(), "zero matrix rejected");
	}

	// 6. History cuts: identity, large angle, q/-q, ordinary turning and zoom.
	private static void historyCuts() {
		Quaternionf identity = new Quaternionf();
		Quaternionf negated = new Quaternionf(-identity.x, -identity.y, -identity.z, -identity.w);
		near((float) DlssMath.rotationAngle(identity, negated), 0.0f, "q and -q are the same rotation");
		Quaternionf yaw5 = new Quaternionf().rotateY((float) Math.toRadians(5));
		Quaternionf yaw59 = new Quaternionf().rotateY((float) Math.toRadians(59));
		Quaternionf yaw61 = new Quaternionf().rotateY((float) Math.toRadians(61));
		Quaternionf yaw120 = new Quaternionf().rotateY((float) Math.toRadians(120));
		Quaternionf yaw120Negated = new Quaternionf(-yaw120.x, -yaw120.y, -yaw120.z, -yaw120.w);
		check(!DlssMath.isRotationCut(identity, yaw5), "ordinary mouse turn is not a cut");
		check(!DlssMath.isRotationCut(identity, yaw59), "59 degrees continues");
		check(DlssMath.isRotationCut(identity, yaw61), "61 degrees is a cut");
		check(DlssMath.isRotationCut(identity, yaw120Negated), "a cut is detected through the negated quaternion");
		check(!DlssMath.isRotationCut(yaw120, yaw120Negated), "equal rotations with opposite sign are not a cut");
		long frame = 4_000_000L;
		check(DlssMath.cutReason(false, true, 0, frame, true, identity, identity) == ResetReason.FIRST_FRAME, "first frame");
		check(DlssMath.cutReason(true, false, 0, frame, true, identity, identity) == ResetReason.LEVEL, "level change");
		check(DlssMath.cutReason(true, true, 81, frame, true, identity, identity) == ResetReason.TELEPORT, "9 blocks teleports");
		check(DlssMath.cutReason(true, true, 49, frame, true, identity, identity) == null, "7 blocks continues");
		check(DlssMath.cutReason(true, true, 0, 300_000_000L, true, identity, identity) == ResetReason.GAP, "frame gap");
		check(DlssMath.cutReason(true, true, 0, frame, false, identity, identity) == ResetReason.CAMERA, "camera mode or entity change");
		check(DlssMath.cutReason(true, true, 0.04, frame, true, identity, yaw120) == ResetReason.ROTATION, "angular cut");
		check(DlssMath.cutReason(true, true, 0.04, frame, true, identity, yaw5) == null, "smooth turn and strafe continue");
		// A smooth 90 degree turn over 20 frames never reaches the threshold in any single frame.
		Quaternionf previous = new Quaternionf();
		for (int step = 1; step <= 20; step++) {
			Quaternionf current = new Quaternionf().rotateY((float) Math.toRadians(90.0 * step / 20));
			check(DlssMath.cutReason(true, true, 0.01, frame, true, previous, current) == null, "step " + step + " of a smooth turn");
			previous = current;
		}
	}

	// 5. Explicit model presets and the shader-aware Auto backend policy.
	private static void modelPresets() {
		check(Upscaler.AUTO.resolve(true, true, false) == Upscaler.OFF, "Shaderless Auto keeps native rendering");
		check(Upscaler.AUTO.resolve(true, true, true) == Upscaler.DLSS, "Shader-pack Auto prefers DLSS");
		check(Upscaler.AUTO.resolve(false, true, true) == Upscaler.FSR, "Shader-pack Auto falls back to FSR");
		check(Upscaler.AUTO.resolve(false, false, true) == Upscaler.OFF, "No runtime keeps native rendering");
		check(Upscaler.DLSS.resolve(true, true, false) == Upscaler.DLSS, "Explicit shaderless DLSS remains available");
		check(Upscaler.OFF.resolve(true, true, true) == Upscaler.OFF, "Explicit Off stays off with shaders");
		for (UpscaleQuality quality : UpscaleQuality.values()) {
			check(DlssModel.AUTO.preset(quality) == 0, "Auto passes zero for the runtime default");
			check(DlssModel.CNN.preset(quality) == 5, "CNN stays preset E");
		}
		check(DlssModel.TRANSFORMER.preset(UpscaleQuality.NATIVE_AA) == 11, "DLAA K");
		check(DlssModel.TRANSFORMER.preset(UpscaleQuality.QUALITY) == 11, "Quality K");
		check(DlssModel.TRANSFORMER.preset(UpscaleQuality.BALANCED) == 11, "Balanced K");
		check(DlssModel.TRANSFORMER.preset(UpscaleQuality.PERFORMANCE) == 11, "Performance K retains detail at lower cost than M");
		check(DlssModel.TRANSFORMER.preset(UpscaleQuality.ULTRA_PERFORMANCE) == 12, "Ultra Performance L");
	}

	private static void near(float actual, float expected, String label) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > EPS * Math.max(1.0f, Math.abs(expected))) {
			throw new AssertionError(label + ": " + actual + " != " + expected);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
