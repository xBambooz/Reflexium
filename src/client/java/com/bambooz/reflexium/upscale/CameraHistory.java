package com.bambooz.reflexium.upscale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * Camera, depth range and first-person hand matrices for the current frame and the frame before it, which motion
 * vectors, both upscalers and frame generation reproject between. A cut restarts all of them together and the end
 * of a completed scene promotes all of them together. Render thread only.
 */
final class CameraHistory {
	// Physical lens from the projection Minecraft built before view bob, damage tilt and nausea.
	float cameraNear = 0.05f, cameraFar = 1024f, cameraFov = 1.22f;
	/** Far plane given to the upscalers; extends past {@link #cameraFar} when Distant Horizons draws farther. */
	float motionFar = 1024f;
	long invalidCameraMetadata;
	final Matrix4f depthTransform = new Matrix4f(), previousDepthTransform = new Matrix4f();

	/** The projection drawn this frame, including view effects, before jitter. */
	final Matrix4f currentProjection = new Matrix4f();
	final Matrix4f physicalProjection = new Matrix4f();
	/** View-projection with and without this frame's jitter, and last frame's unjittered one. */
	final Matrix4f currentJittered = new Matrix4f();
	final Matrix4f currentUnjittered = new Matrix4f();
	final Matrix4f previousUnjittered = new Matrix4f();
	Vec3 currentCamera = Vec3.ZERO;
	Vec3 previousCamera = Vec3.ZERO;
	final Quaternionf currentOrientation = new Quaternionf();
	final Quaternionf previousOrientation = new Quaternionf();
	int currentViewIdentity;
	int previousViewIdentity;
	Object previousLevel;
	boolean havePrevious;

	// First-person hand projection: the unjittered and jittered matrices it was drawn with this frame, and last frame's.
	final Matrix4f handProjection = new Matrix4f();
	final Matrix4f handProjectionJittered = new Matrix4f();
	final Matrix4f handProjectionPrevious = new Matrix4f();
	boolean haveHandProjection;

	/** The level projection about to be drawn, its jittered copy, and the camera it was built for. */
	void recordLevel(Matrix4f projection, Matrix4f jittered, CameraRenderState camera) {
		currentUnjittered.set(projection).mul(camera.viewRotationMatrix);
		currentProjection.set(projection);
		// View bob, damage tilt and nausea were multiplied into the drawn projection. The physical camera
		// planes and field of view come from the matrix Minecraft built before those effects.
		physicalProjection.set(camera.projectionMatrix);
		DlssMath.CameraMetadata physical = DlssMath.cameraMetadata(physicalProjection);
		if (physical.valid()) {
			cameraNear = physical.near();
			cameraFar = physical.far();
			cameraFov = physical.verticalFov();
		} else {
			invalidCameraMetadata++;
		}
		currentJittered.set(jittered).mul(camera.viewRotationMatrix);
		currentCamera = camera.pos;
		currentOrientation.set(camera.orientation);
	}

	/** Camera translation since the previous frame, in world units. */
	Vec3 delta() {
		return currentCamera.subtract(previousCamera);
	}

	/** Whether this frame starts a new temporal history, and why. Null when history continues. */
	ResetReason cutReason(Object level, long sinceLastEvaluationNs) {
		return DlssMath.cutReason(havePrevious, level == previousLevel, currentCamera.distanceToSqr(previousCamera),
				sinceLastEvaluationNs, currentViewIdentity == previousViewIdentity, previousOrientation, currentOrientation);
	}

	/** A cut: the previous frame becomes this frame, so reprojection describes no motion. */
	void restart() {
		previousUnjittered.set(currentUnjittered);
		previousDepthTransform.set(depthTransform);
		previousCamera = currentCamera;
		previousOrientation.set(currentOrientation);
		if (haveHandProjection) handProjectionPrevious.set(handProjection);
	}

	/** A completed scene becomes the previous frame. History only continues from frames with captured world depth. */
	void promote(boolean worldDepthCaptured) {
		previousUnjittered.set(currentUnjittered);
		previousDepthTransform.set(depthTransform);
		previousCamera = currentCamera;
		previousOrientation.set(currentOrientation);
		previousViewIdentity = currentViewIdentity;
		if (haveHandProjection) handProjectionPrevious.set(handProjection);
		havePrevious = worldDepthCaptured;
	}

	JsonObject physicalJson() {
		JsonObject camera = new JsonObject();
		camera.addProperty("near", cameraNear);
		camera.addProperty("far", cameraFar);
		camera.addProperty("upscaler_far", motionFar);
		camera.addProperty("vertical_fov_rad", cameraFov);
		camera.addProperty("invalid_metadata_frames", invalidCameraMetadata);
		return camera;
	}

	JsonObject matricesJson(Matrix4fc currentToPrevious) {
		JsonObject matrices = new JsonObject();
		matrices.add("world_view_projection_jittered", floats(currentJittered));
		matrices.add("world_view_projection", floats(currentUnjittered));
		matrices.add("previous_world_view_projection", floats(previousUnjittered));
		matrices.add("current_to_previous", floats(currentToPrevious));
		matrices.add("physical_projection", floats(physicalProjection));
		matrices.add("drawn_projection", floats(currentProjection));
		matrices.add("upscaler_depth_transform", floats(depthTransform));
		matrices.addProperty("hand_projection_present", haveHandProjection);
		matrices.add("hand_projection_jittered", floats(handProjectionJittered));
		matrices.add("hand_projection", floats(handProjection));
		matrices.add("hand_projection_previous", floats(handProjectionPrevious));
		return matrices;
	}

	JsonObject positionJson() {
		JsonObject camera = new JsonObject();
		camera.addProperty("x", currentCamera.x);
		camera.addProperty("y", currentCamera.y);
		camera.addProperty("z", currentCamera.z);
		camera.addProperty("previous_x", previousCamera.x);
		camera.addProperty("previous_y", previousCamera.y);
		camera.addProperty("previous_z", previousCamera.z);
		JsonArray q = new JsonArray();
		q.add(currentOrientation.x); q.add(currentOrientation.y); q.add(currentOrientation.z); q.add(currentOrientation.w);
		camera.add("orientation_xyzw", q);
		return camera;
	}

	private static JsonArray floats(Matrix4fc matrix) {
		float[] values = matrix.get(new float[16]);
		JsonArray out = new JsonArray();
		for (float value : values) out.add(value);
		return out;
	}
}
