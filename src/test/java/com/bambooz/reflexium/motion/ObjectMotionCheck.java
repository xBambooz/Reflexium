package com.bambooz.reflexium.motion;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;
import java.util.List;

/** Deterministic capture/history check. No graphics device or Minecraft process required. */
public final class ObjectMotionCheck {
	private static final FeatureRendererType<Node> TYPE = FeatureRendererType.create("motion check");
	private record Node(FeatureRendererType<Node> featureType) implements SubmitNode { }
	private static int forwarded;
	private static final VertexConsumer SINK = new VertexConsumer() {
		public VertexConsumer addVertex(float x, float y, float z) { forwarded++; return this; }
		public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
		public VertexConsumer setColor(int value) { return this; }
		public VertexConsumer setUv(float u, float v) { return this; }
		public VertexConsumer setUv1(int u, int v) { return this; }
		public VertexConsumer setUv2(int u, int v) { return this; }
		public VertexConsumer setUv3(float u, float v) { return this; }
		public VertexConsumer setNormal(float x, float y, float z) { return this; }
		public VertexConsumer setLineWidth(float value) { return this; }
	};

	public static void main(String[] args) throws Exception {
		ObjectMotion motion = new ObjectMotion();
		frame(motion, 0, 4, 0);
		check(motion.prepareDraws(0, 0, 0, false) == 0, "new object has no previous geometry");
		motion.endFrame();
		frame(motion, .25f, 4, 0);
		check(motion.prepareDraws(0, 0, 0, false) == 4, "moving quad");
		FloatBuffer vertices = FloatBuffer.allocate(28);
		motion.writeVertices(vertices);
		check(vertices.position() == 28 && vertices.get(0) == .25f && vertices.get(3) == 0 && vertices.get(6) == 1,
				"current, previous, culling GPU layout");
		check(motion.prepareDraws(0, 0, 0, true) == 0, "reset discards prepared draws");
		vertices.clear(); motion.writeVertices(vertices);
		check(vertices.position() == 0, "reset cannot replay stale draw flags");
		motion.endFrame();
		frame(motion, -.25f, 4, 0);
		check(motion.prepareDraws(.5f, 0, 0, false) == 0, "camera displacement is not object motion");
		check(motion.prepareDraws(.5f, 0, 0, false) == 0, "repeat preparation stable");
		motion.endFrame();
		frame(motion, 0, 4, 1);
		check(motion.prepareDraws(0, 0, 0, false) == 0, "same count with changed UV ordering is unmatched");
		motion.endFrame();
		frame(motion, 0, 8, 1);
		check(motion.prepareDraws(0, 0, 0, false) == 0, "topology count change");
		motion.endFrame();
		frame(motion, .1f, 8, 1);
		check(motion.prepareDraws(0, 0, 0, false) == 8, "new topology acquires fresh history");
		motion.endFrame();
		frame(motion, .2f, 8, 1);
		ObjectMotion.Slot slot = current(motion);
		slot.positions[21] += 20; // Teleport in the last vertex, not the first.
		check(motion.prepareDraws(0, 0, 0, false) == 0, "every vertex checked for discontinuity");
		motion.endFrame();
		motion.beginFrame(true, false); motion.endFrame();
		check(motion.trackedEntities() == 0, "invisible objects are forgotten after one missing frame");
		frame(motion, 1, 4, 0);
		check(motion.prepareDraws(0, 0, 0, false) == 0, "returning object cannot use stale history");
		motion.endFrame();
		motion.beginFrame(true, false);
		motion.enterEntity(7);
		motion.enterEntity(-1);
		Node unknown = new Node(TYPE); motion.onSubmit(unknown);
		motion.leaveContext();
		Node known = new Node(TYPE); motion.onSubmit(known); motion.leaveContext();
		motion.observe(List.of(unknown)).get(0);
		check(current(motion) == null, "synthetic GUI state cannot inherit a parent entity");
		motion.observe(List.of(known)).get(0);
		check(current(motion) != null, "nested context restores parent");
		motion.endFrame();
		frame(motion, 0, ObjectMotion.MAX_VERTICES + 4, 0);
		check(motion.capturedVertices() == ObjectMotion.MAX_VERTICES && current(motion).overflow,
				"capture memory budget drops the incomplete slot");
		check(motion.prepareDraws(0, 0, 0, false) == 0, "overflow never replays partial geometry");
		motion.endFrame(); motion.beginFrame(false, false);
		check(motion.trackedEntities() == 0 && motion.drawnVertices() == 0, "disable clears history and stats");
		check(forwarded > ObjectMotion.MAX_VERTICES, "capture cap never suppresses actual rendering");
		handAnimation();
		packedCapture();
		System.out.println("ObjectMotionCheck passed: identity, geometry history, camera/object displacement, GPU layout, nesting, budget and hand animation");
	}

	private static void packedCapture() {
		class PackedSink extends ObjectMotion.Capture implements VertexBufferWriter {
			int pushes;
			PackedSink() { super(SINK, new ObjectMotion.Slot(), new ObjectMotion(), true); }
			@Override public void push(MemoryStack stack, long pointer, int count, VertexFormat format) {
				check(count == 4, "packed stream forwarded as one batch");
				check(MemoryUtil.memGetFloat(pointer + format.getElement("Position").offset()) == .25f, "original stream forwarded");
				pushes++;
				MemoryUtil.memSet(pointer, 0, (long) count * format.getVertexSize());
			}
		}
		VertexFormat shuffled = VertexFormat.builder(0).addAttribute("UV0", GpuFormat.RG32_FLOAT)
				.addAttribute("Position", GpuFormat.RGB32_FLOAT).build();
		for (VertexFormat format : List.of(DefaultVertexFormat.ENTITY, DefaultVertexFormat.BLOCK, DefaultVertexFormat.POSITION, shuffled)) {
			ObjectMotion motion = new ObjectMotion();
			ObjectMotion.Slot slot = new ObjectMotion.Slot(), scalar = new ObjectMotion.Slot();
			PackedSink sink = new PackedSink();
			var capture = new ObjectMotion.SodiumCapture(sink, slot, motion, true);
			var fallback = new ObjectMotion.Capture(SINK, scalar, new ObjectMotion(), true);
			check(VertexBufferWriter.tryOf(capture) == capture, "Sodium fast path remains available");
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long buffer = stack.ncalloc(4, 4, format.getVertexSize());
				var uv = format.getElement("UV0");
				for (int i = 0; i < 4; i++) {
					long p = buffer + (long) i * format.getVertexSize() + format.getElement("Position").offset();
					MemoryUtil.memPutFloat(p, .25f + i); MemoryUtil.memPutFloat(p + 4, i); MemoryUtil.memPutFloat(p + 8, -5);
					fallback.addVertex(.25f + i, i, -5);
					if (uv != null) {
						long t = buffer + (long) i * format.getVertexSize() + uv.offset();
						MemoryUtil.memPutFloat(t, i * .25f); MemoryUtil.memPutFloat(t + 4, .75f);
						fallback.setUv(i * .25f, .75f);
					}
				}
				capture.push(stack, buffer, 4, format);
			}
			check(sink.pushes == 1 && slot.count == 4 && motion.capturedVertices() == 4, "packed positions counted before mutating delegate");
			check(java.util.Arrays.equals(slot.positions, scalar.positions) && slot.signature == scalar.signature,
					"packed positions and topology guard match scalar capture for every layout");
			check(slot.culling[3] == 1, "packed culling retained");
		}
		check(!new ObjectMotion.SodiumCapture(SINK, new ObjectMotion.Slot(), new ObjectMotion(), true).canUseIntrinsics(),
				"an unsupported delegate keeps the scalar path");
	}

	private static ObjectMotion.Slot current(ObjectMotion motion) throws Exception {
		var field = ObjectMotion.class.getDeclaredField("current"); field.setAccessible(true);
		return (ObjectMotion.Slot) field.get(motion);
	}

	private static void frame(ObjectMotion motion, float x, int count, int uvVariant) throws Exception {
		motion.beginFrame(true, false); motion.enterEntity(7);
		Node node = new Node(TYPE); motion.onSubmit(node); motion.leaveContext();
		motion.observe(List.of(node)).iterator().next();
		VertexConsumer capture = new ObjectMotion.Capture(SINK, current(motion), motion, true);
		for (int i = 0; i < count; i++) {
			capture.addVertex(x + i % 4, 0, -5, -1, uvVariant + i % 4, i % 4, 0, 0, 0, 1, 0);
		}
	}

	/** One first-person hand frame: a single quad submitted with the given modelview, model-space x offset and UV variant. */
	private static void handFrame(ObjectMotion motion, Matrix4f modelView, float x, int count, int uvVariant) throws Exception {
		motion.beginFrame(true, false);
		motion.enterHand(modelView);
		Node node = new Node(TYPE); motion.onSubmit(node); motion.leaveContext();
		motion.observe(List.of(node)).iterator().next();
		// The real arm uses entityTranslucent, including its fully opaque skin texels.
		VertexConsumer capture = motion.capture(SINK, RenderTypes.entityTranslucent(
				Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")));
		check(capture != SINK, "depth-writing blended hand must enter the velocity producer");
		for (int i = 0; i < count; i++) {
			capture.addVertex(x + i % 4 * 0.1f, i / 2 % 2 * 0.1f, -0.5f, -1, uvVariant + i % 4, i % 4, 0, 0, 0, 1, 0);
		}
	}

	private static void handAnimation() throws Exception {
		ObjectMotion motion = new ObjectMotion();
		// Vanilla's hand modelview is the camera rotation.
		Matrix4f view = new Matrix4f().rotationY(0.4f);
		handFrame(motion, view, 0.0f, 4, 0);
		check(motion.prepareHandDraws(false, false) == 0, "first hand frame has no history");
		motion.endFrame();
		// Swing: the model-space position changes while the view is unchanged.
		handFrame(motion, view, 0.05f, 4, 0);
		check(motion.prepareHandDraws(false, false) == 4, "swinging hand is drawn");
		FloatBuffer out = FloatBuffer.allocate(28);
		motion.writeHandVertices(out);
		Vector3f expectedNow = view.transformPosition(new Vector3f(0.05f, 0, -0.5f), new Vector3f());
		Vector3f expectedBefore = view.transformPosition(new Vector3f(0, 0, -0.5f), new Vector3f());
		check(near(out.get(0), expectedNow.x) && near(out.get(1), expectedNow.y) && near(out.get(2), expectedNow.z), "current eye position");
		check(near(out.get(3), expectedBefore.x) && near(out.get(4), expectedBefore.y) && near(out.get(5), expectedBefore.z), "previous eye position");
		// The projected pixel displacement matches the known swing, independently of the camera.
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 1024, 0.05f, true);
		Vector4f now = projection.transform(new Vector4f(expectedNow, 1));
		Vector4f before = projection.transform(new Vector4f(expectedBefore, 1));
		Vector4f nowRead = projection.transform(new Vector4f(out.get(0), out.get(1), out.get(2), 1));
		Vector4f beforeRead = projection.transform(new Vector4f(out.get(3), out.get(4), out.get(5), 1));
		check(near((beforeRead.x / beforeRead.w - nowRead.x / nowRead.w), (before.x / before.w - now.x / now.w)), "projected displacement");
		check(Math.abs(before.x / before.w - now.x / now.w) > 1.0e-4f, "swing is a visible displacement");
		motion.endFrame();
		// A camera turn that carries the screen-fixed hand along: model positions rotate, eye positions do not change.
		Matrix4f turned = new Matrix4f().rotationY(0.55f);
		Matrix4f carry = new Matrix4f(turned).invert().mul(view);
		motion.beginFrame(true, false);
		motion.enterHand(turned);
		Node node = new Node(TYPE); motion.onSubmit(node); motion.leaveContext();
		motion.observe(List.of(node)).iterator().next();
		VertexConsumer capture = new ObjectMotion.Capture(SINK, current(motion), motion, true);
		for (int i = 0; i < 4; i++) {
			Vector3f p = carry.transformPosition(new Vector3f(0.05f + i % 4 * 0.1f, i / 2 % 2 * 0.1f, -0.5f), new Vector3f());
			capture.addVertex(p.x, p.y, p.z, -1, i % 4, i % 4, 0, 0, 0, 1, 0);
		}
		check(motion.prepareHandDraws(false, false) == 0, "camera rotation alone is no hand motion");
		motion.endFrame();
		// New topology (an item swap changes UVs) must not be matched against the previous item.
		handFrame(motion, turned, 0.0f, 4, 3);
		check(motion.prepareHandDraws(false, false) == 0, "changed item has no stale hand history");
		motion.endFrame();
		handFrame(motion, turned, 0.0f, 4, 3);
		check(motion.prepareHandDraws(false, false) == 0, "static item adds no draw");
		check(motion.prepareHandDraws(false, true) == 4, "a changed hand projection needs vectors even with static vertices");
		motion.endFrame();
		handFrame(motion, turned, 0.04f, 4, 3);
		check(motion.prepareHandDraws(true, false) == 0, "history reset draws no hand vectors");
		check(motion.prepareHandDraws(false, false) == 4, "moving again after the reset check");
		motion.discardHistory();
		check(motion.prepareHandDraws(false, false) == 0, "a camera cut discards hand history");
		motion.endFrame();
		motion.beginFrame(true, false); motion.endFrame();
		handFrame(motion, turned, 0.0f, 4, 3);
		check(motion.prepareHandDraws(false, false) == 0, "a frame without the hand drops its history");
		motion.endFrame();
		motion.beginFrame(false, false);
		check(motion.handVertices() == 0, "disabled capture clears hand state");
	}

	private static boolean near(float a, float b) {
		return Float.isFinite(a) && Math.abs(a - b) < 2.0e-5f;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
