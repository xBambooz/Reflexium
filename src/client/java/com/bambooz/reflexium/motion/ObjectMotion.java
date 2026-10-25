package com.bambooz.reflexium.motion;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Per-object motion for entities and animated block entities, so their motion vectors describe the
 * object's own movement instead of camera reprojection of its (moving) surface.
 * <p>
 * Why this design, in 26.3: models are not drawn from a scene graph with per-object matrices. Entity and
 * block entity renderers submit nodes, and {@code RenderTypeFeatureRenderer.prepareGroup} bakes every
 * model into a shared staged vertex buffer (final camera-relative positions) before any draw happens.
 * There is no per-vertex "previous position" stream and no spare color attachment on the level pass,
 * and the vertex formats and pipelines are vanilla's and shared with other mods. So instead of changing
 * those, this class records the final positions of every vertex that lands in the buffer, attributed to
 * the entity (or block entity) that submitted it, and keeps last frame's list. Matching vertices by
 * index gives the exact previous position of every animated vertex (limbs, lids, pistons) with no
 * knowledge of the model type. {@link ObjectVelocityPass} then re-draws just those moving quads after
 * the main pass, with both positions as vertex attributes, and writes screen-space velocity over the
 * camera-only vectors wherever the quad is the visible surface (depth equality against the world depth).
 * <p>
 * Attribution: the entity renderers run inside {@code LevelRenderer.submitEntities}, so a context set
 * around each {@code EntityRenderDispatcher.submit} call gives every submit node of that entity a slot in
 * submission order. Slot index is stable between frames as long as the entity submits the same things.
 * Anything that does not match (new entity, different submit or vertex count, moved more than eight
 * blocks) is left alone and keeps the camera-only vector. Static objects are skipped as well, because
 * for them camera reprojection is already exact.
 * <p>
 * Render thread only. Every entry point is a no-op unless {@link #beginFrame} enabled capture.
 */
public final class ObjectMotion {
	private static final ObjectMotion INSTANCE = new ObjectMotion();

	/** Hard cap on vertices captured and drawn per frame (28 GPU bytes each). */
	static final int MAX_VERTICES = 196_608;
	/** Largest per-vertex world displacement (blocks) that still counts as not moving. */
	private static final float STATIC_EPSILON = 2.0e-4f;
	/** A vertex further than this from its previous position is a teleport, not motion. */
	private static final float TELEPORT_BLOCKS = 8.0f;

	/** Submit kinds that never write geometry into depth or are not part of an object. */
	private static final FeatureRendererType<?>[] IGNORED_TYPES = {
			TextFeatureRenderer.TYPE, ShadowFeatureRenderer.TYPE, FlameFeatureRenderer.TYPE, LeashFeatureRenderer.TYPE,
			QuadParticleFeatureRenderer.TYPE, GizmoFeatureRenderer.TYPE, ShapeOutlineFeatureRenderer.TYPE};

	private final Int2ObjectOpenHashMap<Track> entityTracks = new Int2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Track> blockTracks = new Long2ObjectOpenHashMap<>();
	private final IdentityHashMap<Object, Slot> submitSlots = new IdentityHashMap<>();
	private final ArrayList<Track> trackPool = new ArrayList<>();
	private final ArrayList<Slot> slotPool = new ArrayList<>();

	private boolean active;
	private boolean drawStatic;
	private int frame;
	private Track context;
	private Slot current;
	private Capture lastCapture;
	private final ArrayList<Track> contexts = new ArrayList<>();
	private int lastDrawVertices;
	private int capturedVertices;

	// First-person hand and held item. Its vertices are recorded in model space and drawn with the hand's own
	// view and projection, so the camera delta of world tracks never applies to them.
	private final Track handTrack = new Track();
	private final Matrix4f handView = new Matrix4f();
	private boolean handSeen;
	private int lastHandVertices;
	private final Vector3f scratchNow = new Vector3f();
	private final Vector3f scratchBefore = new Vector3f();

	ObjectMotion() {
	}

	public static ObjectMotion get() {
		return INSTANCE;
	}

	// ---------------------------------------------------------------------------------------------
	// Frame lifecycle (UpscalingRenderer)

	/** Before the level submits anything. {@code enabled} false drops all history and records nothing. */
	public void beginFrame(boolean enabled, boolean drawStatic) {
		if (!enabled) {
			capturedVertices = 0;
			if (active || !entityTracks.isEmpty() || !blockTracks.isEmpty() || handTrack.hadPrevious) {
				clearAll();
			}
			active = false;
			return;
		}
		active = true;
		this.drawStatic = drawStatic;
		frame++;
		submitSlots.clear();
		context = null;
		contexts.clear();
		current = null;
		lastCapture = null;
		capturedVertices = 0;
		handSeen = false;
	}

	/** Camera cut or history reset: nothing recorded so far may be matched against what follows. */
	public void discardHistory() {
		for (Track track : entityTracks.values()) track.hadPrevious = false;
		for (Track track : blockTracks.values()) track.hadPrevious = false;
		handTrack.hadPrevious = false;
	}

	/** After the velocity pass (or instead of it when the frame ended early). Always leaves a clean state. */
	public void endFrame() {
		if (active) {
			rotate();
			active = false;
		}
		submitSlots.clear();
		context = null;
		contexts.clear();
		current = null;
		lastCapture = null;
	}

	public boolean isActive() {
		return active;
	}

	/** Vertices the last {@link #prepareDraws} queued (debug status). */
	int lastDrawVertices() {
		return lastDrawVertices;
	}

	public int capturedVertices() { return capturedVertices; }
	public int drawnVertices() { return lastDrawVertices; }
	public int trackedEntities() { return entityTracks.size(); }
	public int trackedBlockEntities() { return blockTracks.size(); }

	// ---------------------------------------------------------------------------------------------
	// Capture hooks (mixins)

	public void enterEntity(int entityId) {
		if (!active) {
			return;
		}
		contexts.add(context);
		context = null;
		if (entityId < 0) return;
		Track track = entityTracks.get(entityId);
		if (track == null) {
			track = newTrack();
			entityTracks.put(entityId, track);
		}
		enter(track);
	}

	public void enterBlockEntity(long packedPos) {
		if (!active) {
			return;
		}
		contexts.add(context);
		Track track = blockTracks.get(packedPos);
		if (track == null) {
			track = newTrack();
			blockTracks.put(packedPos, track);
		}
		enter(track);
	}

	/**
	 * First-person hand and held item submission. {@code modelView} is the GPU modelview the hand is drawn
	 * with: vanilla concatenates the camera rotation there, while Vitrail's own hand draw uses identity.
	 */
	public void enterHand(Matrix4fc modelView) {
		if (!active) {
			return;
		}
		contexts.add(context);
		handView.set(modelView);
		handSeen = true;
		enter(handTrack);
	}

	public void leaveContext() {
		context = contexts.isEmpty() ? null : contexts.remove(contexts.size() - 1);
	}

	private void enter(Track track) {
		if (track.frame != frame) {
			track.frame = frame;
			track.cursor = 0;
		}
		context = track;
	}

	/** A submit node is being queued while an entity or block entity context is open. */
	public void onSubmit(SubmitNode node) {
		Track track = context;
		if (track == null) {
			return;
		}
		FeatureRendererType<?> type = node.featureType();
		for (FeatureRendererType<?> ignored : IGNORED_TYPES) {
			if (type == ignored) {
				return;
			}
		}
		Slot slot = slot(track, track.cursor++);
		slot.count = 0;
		slot.signature = 1;
		slot.overflow = false;
		slot.feature = type;
		slot.model = node instanceof ModelFeatureRenderer.Submit<?> model ? model.model() : null;
		if (track == handTrack) {
			if (slot.view == null) {
				slot.view = new Matrix4f();
				slot.previousView = new Matrix4f();
			}
			slot.view.set(handView);
		}
		if (node instanceof MovingBlockFeatureRenderer.Submit moving) slot.model = moving.movingBlockRenderState().blockState;
		submitSlots.put(node, slot);
	}

	/**
	 * Wraps the submit list a feature renderer is about to bake, so each element it visits becomes the
	 * slot that receives the vertices of the following {@code getVertexBuilder} calls.
	 */
	public <E> List<E> observe(List<E> submits) {
		return new ObservedList<>(submits);
	}

	public void endGroup() {
		current = null;
		lastCapture = null;
	}

	private void onVisit(Object submit) {
		current = submitSlots.get(submit);
		lastCapture = null;
	}

	/** Called with the consumer {@code getVertexBuilder} is about to return. */
	public VertexConsumer capture(VertexConsumer consumer, RenderType renderType) {
		Slot slot = current;
		var depth = renderType.pipeline().getDepthStencilState();
		if (slot == null || renderType.primitiveTopology() != PrimitiveTopology.QUADS || renderType.isOutline()
				|| (renderType.hasBlending() && slot.view == null) || depth == null || !depth.writeDepth()
				|| depth.depthBiasConstant() != 0 || depth.depthBiasScaleFactor() != 0) {
			return consumer;
		}
		Capture capture = lastCapture;
		if (capture != null && capture.out == consumer && capture.slot == slot) {
			return capture;
		}
		slot.signature = 31 * slot.signature + System.identityHashCode(renderType);
		capture = FabricLoader.getInstance().isModLoaded("sodium")
				? SodiumCapture.create(consumer, slot, this, renderType.pipeline().isCull())
				: new Capture(consumer, slot, this, renderType.pipeline().isCull());
		lastCapture = capture;
		return capture;
	}

	// ---------------------------------------------------------------------------------------------
	// Velocity pass input

	/**
	 * Decides which slots are drawn this frame and returns their total vertex count.
	 *
	 * @param camDx camera position now minus camera position last frame (blocks), per axis
	 * @param reset history was discarded this frame (teleport, cut, first frame): draw nothing
	 */
	int prepareDraws(float camDx, float camDy, float camDz, boolean reset) {
		int total = 0;
		for (Track track : entityTracks.values()) for (Slot slot : track.slots) slot.draw = false;
		for (Track track : blockTracks.values()) for (Slot slot : track.slots) slot.draw = false;
		if (!reset) {
			for (Track track : entityTracks.values()) {
				total = markDraws(track, camDx, camDy, camDz, total);
			}
			for (Track track : blockTracks.values()) {
				total = markDraws(track, camDx, camDy, camDz, total);
			}
		}
		lastDrawVertices = total;
		return total;
	}

	private int markDraws(Track track, float camDx, float camDy, float camDz, int total) {
		if (track.frame != frame || !track.hadPrevious) {
			return total;
		}
		for (int i = 0; i < track.cursor; i++) {
			Slot slot = track.slots.get(i);
			slot.draw = false;
			// ponytail: submit order plus model/type/UV guards matches vanilla topology. Custom meshes
			// changing connectivity with identical counts and UVs need renderer-provided stable vertex IDs.
			if (slot.overflow || slot.count == 0 || slot.count % 4 != 0 || slot.count != slot.previousCount
					|| slot.feature != slot.previousFeature || slot.model != slot.previousModel
					|| slot.signature != slot.previousSignature || total + slot.count > MAX_VERTICES) {
				continue;
			}
			if (slot.isMoving(camDx, camDy, camDz, drawStatic)) {
				slot.draw = true;
				total += slot.count;
			}
		}
		return total;
	}

	/**
	 * Hand and held item quads that are the visible hand surface and moved since last frame. Positions are
	 * compared in eye space, so a camera turn that carries the hand along produces no vector.
	 */
	int prepareHandDraws(boolean reset, boolean projectionChanged) {
		for (Slot slot : handTrack.slots) slot.draw = false;
		int total = 0;
		if (!reset && handSeen && handTrack.frame == frame && handTrack.hadPrevious) {
			for (int i = 0; i < handTrack.cursor; i++) {
				Slot slot = handTrack.slots.get(i);
				if (slot.overflow || slot.count == 0 || slot.count % 4 != 0 || slot.count != slot.previousCount
						|| slot.feature != slot.previousFeature || slot.model != slot.previousModel
						|| slot.signature != slot.previousSignature || total + slot.count > MAX_VERTICES) {
					continue;
				}
				if (handMoving(slot, projectionChanged)) {
					slot.draw = true;
					total += slot.count;
				}
			}
		}
		lastHandVertices = total;
		return total;
	}

	private boolean handMoving(Slot slot, boolean projectionChanged) {
		boolean moving = drawStatic || projectionChanged;
		for (int v = 0, n = slot.count * 3; v < n; v += 3) {
			slot.view.transformPosition(slot.positions[v], slot.positions[v + 1], slot.positions[v + 2], scratchNow);
			slot.previousView.transformPosition(slot.previous[v], slot.previous[v + 1], slot.previous[v + 2], scratchBefore);
			float dx = scratchNow.x - scratchBefore.x;
			float dy = scratchNow.y - scratchBefore.y;
			float dz = scratchNow.z - scratchBefore.z;
			if (!Float.isFinite(dx + dy + dz) || dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) return false;
			moving |= Math.abs(dx) > STATIC_EPSILON || Math.abs(dy) > STATIC_EPSILON || Math.abs(dz) > STATIC_EPSILON;
		}
		return moving;
	}

	/** Eye-space position now and last frame per vertex, matching {@link #writeVertices}. */
	void writeHandVertices(FloatBuffer out) {
		for (int i = 0; i < handTrack.cursor; i++) {
			Slot slot = handTrack.slots.get(i);
			if (!slot.draw) {
				continue;
			}
			for (int v = 0, n = slot.count * 3; v < n; v += 3) {
				slot.view.transformPosition(slot.positions[v], slot.positions[v + 1], slot.positions[v + 2], scratchNow);
				slot.previousView.transformPosition(slot.previous[v], slot.previous[v + 1], slot.previous[v + 2], scratchBefore);
				out.put(scratchNow.x).put(scratchNow.y).put(scratchNow.z);
				out.put(scratchBefore.x).put(scratchBefore.y).put(scratchBefore.z);
				out.put(slot.culling[v / 3]);
			}
		}
	}

	public int handVertices() { return lastHandVertices; }
	public int handSlots() { return handSeen ? handTrack.cursor : 0; }
	/** Writes position (relative to this frame's camera) and previous position (relative to last frame's camera) per vertex. */
	void writeVertices(FloatBuffer out) {
		for (Track track : entityTracks.values()) {
			writeTrack(track, out);
		}
		for (Track track : blockTracks.values()) {
			writeTrack(track, out);
		}
	}

	private void writeTrack(Track track, FloatBuffer out) {
		if (track.frame != frame || !track.hadPrevious) {
			return;
		}
		for (int i = 0; i < track.cursor; i++) {
			Slot slot = track.slots.get(i);
			if (!slot.draw) {
				continue;
			}
			float[] now = slot.positions;
			float[] before = slot.previous;
			for (int v = 0, n = slot.count * 3; v < n; v += 3) {
				out.put(now, v, 3);
				out.put(before, v, 3);
				out.put(slot.culling[v / 3]);
			}
		}
	}

	/** Previous becomes current for every object seen this frame; objects not seen are forgotten. */
	private void rotate() {
		rotate(entityTracks.values().iterator());
		rotate(blockTracks.values().iterator());
		rotateHand();
	}

	private void rotateHand() {
		if (handTrack.frame != frame) {
			for (Slot slot : handTrack.slots) recycleSlot(slot);
			handTrack.slots.clear();
			handTrack.hadPrevious = false;
			return;
		}
		while (handTrack.slots.size() > handTrack.cursor) {
			recycleSlot(handTrack.slots.remove(handTrack.slots.size() - 1));
		}
		for (int i = 0; i < handTrack.cursor; i++) {
			Slot slot = handTrack.slots.get(i);
			float[] swap = slot.previous;
			slot.previous = slot.positions;
			slot.positions = swap;
			slot.previousCount = slot.count;
			slot.previousFeature = slot.feature;
			slot.previousModel = slot.model;
			slot.previousSignature = slot.overflow ? 0 : slot.signature;
			Matrix4f view = slot.previousView;
			slot.previousView = slot.view;
			slot.view = view;
			slot.count = 0;
			slot.draw = false;
		}
		handTrack.hadPrevious = true;
	}

	private void rotate(Iterator<Track> tracks) {
		while (tracks.hasNext()) {
			Track track = tracks.next();
			if (track.frame != frame) {
				tracks.remove();
				recycle(track);
				continue;
			}
			while (track.slots.size() > track.cursor) {
				recycleSlot(track.slots.remove(track.slots.size() - 1));
			}
			for (int i = 0; i < track.cursor; i++) {
				Slot slot = track.slots.get(i);
				float[] swap = slot.previous;
				slot.previous = slot.positions;
				slot.positions = swap;
				slot.previousCount = slot.count;
				slot.previousFeature = slot.feature;
				slot.previousModel = slot.model;
				slot.previousSignature = slot.overflow ? 0 : slot.signature;
				slot.count = 0;
				slot.draw = false;
			}
			track.hadPrevious = true;
		}
	}

	private void clearAll() {
		for (Track track : entityTracks.values()) {
			recycle(track);
		}
		for (Track track : blockTracks.values()) {
			recycle(track);
		}
		entityTracks.clear();
		blockTracks.clear();
		for (Slot slot : handTrack.slots) recycleSlot(slot);
		handTrack.slots.clear();
		handTrack.hadPrevious = false;
		handTrack.frame = -1;
		lastHandVertices = 0;
		submitSlots.clear();
		context = null;
		current = null;
		lastCapture = null;
		lastDrawVertices = 0;
		contexts.clear();
	}

	private Track newTrack() {
		Track track = trackPool.isEmpty() ? new Track() : trackPool.remove(trackPool.size() - 1);
		track.frame = -1;
		track.cursor = 0;
		track.hadPrevious = false;
		return track;
	}

	private void recycle(Track track) {
		for (Slot slot : track.slots) {
			recycleSlot(slot);
		}
		track.slots.clear();
		if (trackPool.size() < 256) trackPool.add(track);
	}

	private void recycleSlot(Slot slot) {
		slot.count = slot.previousCount = 0;
		slot.model = slot.previousModel = slot.feature = slot.previousFeature = null;
		if (slotPool.size() < 2048) slotPool.add(slot);
	}

	private Slot slot(Track track, int index) {
		if (index < track.slots.size()) {
			return track.slots.get(index);
		}
		Slot slot = slotPool.isEmpty() ? new Slot() : slotPool.remove(slotPool.size() - 1);
		slot.count = 0;
		slot.previousCount = 0;
		track.slots.add(slot);
		return slot;
	}

	// ---------------------------------------------------------------------------------------------

	private static final class Track {
		final ArrayList<Slot> slots = new ArrayList<>();
		/** Frame in which this object last submitted. A track not seen this frame is dropped at rotation. */
		int frame = -1;
		int cursor;
		/** Seen in the frame before this one, so {@link Slot#previous} holds real data. */
		boolean hadPrevious;
	}

	/** Final vertex positions of one submit node: this frame's and last frame's. */
	static final class Slot {
		float[] positions = new float[96];
		float[] previous = new float[96];
		float[] culling = new float[32];
		int count;
		int previousCount;
		boolean draw;
		boolean overflow;
		Object feature, previousFeature, model, previousModel;
		int signature, previousSignature;
		/** Hand slots only: the modelview this frame's and last frame's vertices were submitted for. */
		Matrix4f view, previousView;

		void add(float x, float y, float z, boolean cull) {
			int at = count * 3;
			if (at + 3 > positions.length) {
				positions = Arrays.copyOf(positions, positions.length * 2);
			}
			if (count == culling.length) culling = Arrays.copyOf(culling, culling.length * 2);
			positions[at] = x;
			positions[at + 1] = y;
			positions[at + 2] = z;
			culling[count] = cull ? 1 : 0;
			count++;
		}

		/**
		 * Positions are relative to the camera of their own frame, so a vertex that did not move in the
		 * world differs by exactly the camera delta. False for a static or teleported object.
		 */
		boolean isMoving(float camDx, float camDy, float camDz, boolean includeStatic) {
			boolean moving = includeStatic;
			for (int v = 0, n = count * 3; v < n; v += 3) {
				float dx = positions[v] - previous[v] + camDx;
				float dy = positions[v + 1] - previous[v + 1] + camDy;
				float dz = positions[v + 2] - previous[v + 2] + camDz;
				if (!Float.isFinite(dx + dy + dz) || dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) return false;
				moving |= Math.abs(dx) > STATIC_EPSILON || Math.abs(dy) > STATIC_EPSILON || Math.abs(dz) > STATIC_EPSILON;
			}
			return moving;
		}
	}

	/** Forwards everything to the real buffer builder and copies each vertex position into a slot. */
	static class Capture implements VertexConsumer {
		final VertexConsumer out;
		final Slot slot;
		final ObjectMotion owner;
		final boolean cull;

		Capture(VertexConsumer out, Slot slot, ObjectMotion owner, boolean cull) {
			this.out = out;
			this.slot = slot;
			this.owner = owner;
			this.cull = cull;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			out.addVertex(x, y, z);
			remember(x, y, z);
			return this;
		}

		final void remember(float x, float y, float z) {
			if (owner.capturedVertices < MAX_VERTICES) {
				slot.add(x, y, z, cull);
				owner.capturedVertices++;
			} else slot.overflow = true;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
				float nx, float ny, float nz) {
			// Preserve BufferBuilder's packed write instead of turning the common path into ten virtual calls.
			out.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
			remember(x, y, z);
			rememberUv(u, v);
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			out.setColor(r, g, b, a);
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			out.setColor(color);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			out.setUv(u, v);
			rememberUv(u, v);
			return this;
		}

		final void rememberUv(float u, float v) {
			slot.signature = 31 * (31 * slot.signature + Float.floatToIntBits(u)) + Float.floatToIntBits(v);
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			out.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			out.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			out.setUv3(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			out.setNormal(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			out.setLineWidth(width);
			return this;
		}
	}

	/** Loaded only with Sodium present. Inspect the packed stream before forwarding: a writer may mutate it. */
	static final class SodiumCapture extends Capture implements VertexBufferWriter {
		private final VertexBufferWriter writer;

		// The base return type keeps Sodium out of the outer class's verifier dependencies.
		static Capture create(VertexConsumer out, Slot slot, ObjectMotion owner, boolean cull) {
			return new SodiumCapture(out, slot, owner, cull);
		}

		SodiumCapture(VertexConsumer out, Slot slot, ObjectMotion owner, boolean cull) {
			super(out, slot, owner, cull);
			writer = VertexBufferWriter.tryOf(out);
		}

		@Override public boolean canUseIntrinsics() { return writer != null; }

		@Override public void push(MemoryStack stack, long pointer, int count, VertexFormat format) {
			long start = pointer;
			int stride = format.getVertexSize();
			int position = format.getElement("Position").offset();
			var uv = format.getElement("UV0");
			for (int i = 0; i < count; i++, pointer += stride) {
				remember(MemoryUtil.memGetFloat(pointer + position), MemoryUtil.memGetFloat(pointer + position + 4),
						MemoryUtil.memGetFloat(pointer + position + 8));
				if (uv != null) rememberUv(MemoryUtil.memGetFloat(pointer + uv.offset()), MemoryUtil.memGetFloat(pointer + uv.offset() + 4));
			}
			writer.push(stack, start, count, format);
		}
	}

	/** Read-only view that reports each element as it is visited by a for-each loop. */
	private final class ObservedList<E> extends AbstractList<E> {
		private final List<E> delegate;

		ObservedList(List<E> delegate) {
			this.delegate = delegate;
		}

		@Override
		public E get(int index) {
			E element = delegate.get(index);
			onVisit(element);
			return element;
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public Iterator<E> iterator() {
			return new Iterator<>() {
				private int index;

				@Override
				public boolean hasNext() {
					return index < delegate.size();
				}

				@Override
				public E next() {
					if (index >= delegate.size()) {
						throw new NoSuchElementException();
					}
					E element = delegate.get(index++);
					onVisit(element);
					return element;
				}
			};
		}
	}
}
