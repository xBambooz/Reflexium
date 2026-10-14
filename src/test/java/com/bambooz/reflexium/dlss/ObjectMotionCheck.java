package com.bambooz.reflexium.dlss;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
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
		System.out.println("ObjectMotionCheck passed: identity, geometry history, camera/object displacement, GPU layout, nesting and budget");
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

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
