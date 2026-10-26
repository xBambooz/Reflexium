package com.bambooz.reflexium.upscale.dlss;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compares every Streamline bridge handle with the signature native/reflexium_sl.cpp exports, and
 * every call site with the handle it dispatches. A wrong FunctionDescriptor or a call that passes a
 * different carrier type (the 2026-10-24 shutdown WrongMethodTypeException) fails here, not during
 * native dispatch in game.
 */
public final class StreamlineBridgeCheck {
	private static final String METHOD_HANDLE = "Ljava/lang/invoke/MethodHandle;";

	/** Expected field name to native signature, derived from native/reflexium_sl.cpp. */
	private static final Map<String, MethodType> EXPECTED = new LinkedHashMap<>();

	private static void expect(String field, Class<?> result, Class<?>... arguments) {
		EXPECTED.put(field, MethodType.methodType(result, arguments));
	}

	static {
		Class<?> a = MemorySegment.class;
		Class<?> i = int.class;
		Class<?> j = long.class;
		expect("init", i, a, a, a);
		expect("instanceExtensions", i, a, i);
		expect("deviceExtensions", i, a, i);
		expect("features12", i, a, i);
		expect("features13", i, a, i);
		expect("queueRequirements", i, a, a, a);
		expect("setDevice", i, a, a, a, i, i, i, i, i, i);
		expect("dlssAvailable", i);
		expect("dlssgAvailable", i);
		expect("reflexAvailable", i);
		expect("pclAvailable", i);
		expect("dlssOptimal", i, i, i, i, i, a, a);
		expect("dlssSetOptions", i, i, i, i, i, i);
		expect("dlssgState", i, a, i);
		expect("dlssgSetOptions", i, i, i, i, i, i, i, i, i, i);
		expect("dlssgSetLoaded", i, i);
		expect("dlssgLoaded", i);
		expect("dlssgTags", i, a, a, a, a, a, i);
		expect("dlssgClearTags", i, a);
		expect("beginFrame", i, i, i);
		expect("setConstants", i, a, i, i, i, i);
		expect("dlssEvaluate", i, a, a, a, a, a);
		expect("dlssFree", void.class);
		expect("dlssVersion", i, a, i);
		expect("reflexOptions", i, i, i);
		expect("reflexSleep", i);
		expect("pclMarker", i, i);
		expect("pclStatsMessage", i, a);
		expect("reflexReport", i, a, a);
		expect("reflexFlags", i, a, a);
		expect("swapchainReady", i);
		expect("createSwapchain", i, a, a, a, a);
		expect("destroySwapchain", void.class, a, j, a);
		expect("getSwapchainImages", i, a, j, a, a);
		expect("acquireNextImage", i, a, j, j, j, j, a);
		expect("present", i, a, a);
		expect("createWin32Surface", i, a, a, a);
		expect("ownsSurface", i, j);
		expect("destroySurface", void.class, a, j, a);
		expect("deviceWaitIdle", i, a);
		expect("shutdown", void.class);
		expect("takeLog", i, a, i);
	}

	public static void main(String[] args) throws Exception {
		callSites();
		handleTypes();
		System.out.println("Streamline bridge checks passed: " + EXPECTED.size()
				+ " handles match their native signatures and every call site");
	}

	/**
	 * Every invokeExact on a bridge field, and every shared helper, must dispatch the handle's own
	 * type. A helper's dispatch signature is fixed by its own invokeExact; every field passed to it
	 * has to match that signature.
	 */
	private static void callSites() throws Exception {
		ClassNode node = new ClassNode();
		try (var in = StreamlineBridge.class.getResourceAsStream("StreamlineBridge.class")) {
			new ClassReader(in).accept(node, 0);
		}
		Map<String, MethodType> helpers = new LinkedHashMap<>();
		for (var method : node.methods) {
			Type[] arguments = Type.getArgumentTypes(method.desc);
			if (arguments.length == 0 || arguments[0].getSort() != Type.OBJECT
					|| !arguments[0].getInternalName().equals("java/lang/invoke/MethodHandle")) continue;
			MethodType dispatch = null;
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call
						&& call.owner.equals("java/lang/invoke/MethodHandle") && call.name.equals("invokeExact")) {
					MethodType current = methodType(call.desc);
					if (dispatch != null && !dispatch.equals(current)) {
						throw new AssertionError("Helper " + method.name + " dispatches two different signatures");
					}
					dispatch = current;
				}
			}
			if (dispatch != null) helpers.put(method.name + method.desc, dispatch);
		}
		Set<String> used = new LinkedHashSet<>();
		int checked = 0;
		for (var method : node.methods) {
			String pending = null;
			for (int index = 0; index < method.instructions.size(); index++) {
				var instruction = method.instructions.get(index);
				if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
						&& field.owner.equals(node.name) && field.desc.equals(METHOD_HANDLE)
						&& index > 0 && method.instructions.get(index - 1) instanceof VarInsnNode load
						&& load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
					if (!EXPECTED.containsKey(field.name)) throw new AssertionError("No expected signature for " + field.name);
					pending = field.name;
					used.add(field.name);
					continue;
				}
				if (!(instruction instanceof MethodInsnNode call)) continue;
				MethodType dispatched = null;
				if (call.owner.equals("java/lang/invoke/MethodHandle") && call.name.equals("invokeExact")) {
					dispatched = methodType(call.desc);
				} else if (call.owner.equals(node.name) && helpers.containsKey(call.name + call.desc)) {
					dispatched = helpers.get(call.name + call.desc);
				}
				if (dispatched == null) continue;
				if (pending == null) {
					// A helper's own dispatch is validated by the map above.
					if (!helpers.containsKey(method.name + method.desc)) {
						throw new AssertionError("invokeExact with no handle source in " + method.name);
					}
				} else if (!EXPECTED.get(pending).equals(dispatched)) {
					throw new AssertionError(pending + " has type " + EXPECTED.get(pending)
							+ " but is dispatched as " + dispatched + " in " + method.name);
				}
				pending = null;
				checked++;
			}
		}
		if (!used.equals(EXPECTED.keySet())) {
			Set<String> missing = new LinkedHashSet<>(EXPECTED.keySet());
			missing.removeAll(used);
			throw new AssertionError("Handles never dispatched by a checked call site: " + missing);
		}
		if (checked == 0) throw new AssertionError("No native invocations checked");
	}
	private static MethodType methodType(String descriptor) {
		Type[] arguments = Type.getArgumentTypes(descriptor);
		Class<?>[] types = new Class<?>[arguments.length];
		for (int i = 0; i < arguments.length; i++) types[i] = carrier(arguments[i]);
		return MethodType.methodType(carrier(Type.getReturnType(descriptor)), types);
	}

	private static Class<?> carrier(Type type) {
		return switch (type.getSort()) {
			case Type.VOID -> void.class;
			case Type.BOOLEAN -> boolean.class;
			case Type.CHAR -> char.class;
			case Type.BYTE -> byte.class;
			case Type.SHORT -> short.class;
			case Type.INT -> int.class;
			case Type.LONG -> long.class;
			case Type.OBJECT -> switch (type.getInternalName()) {
				case "java/lang/foreign/MemorySegment" -> MemorySegment.class;
				case "java/lang/invoke/MethodHandle" -> MethodHandle.class;
				default -> throw new AssertionError("Unsupported native carrier " + type.getInternalName());
			};
			default -> throw new AssertionError("Unsupported native carrier " + type);
		};
	}

	/** Loads the shipped bridge and compares each handle's linked type with the expected signature. */
	private static void handleTypes() throws Exception {
		if (!System.getProperty("os.name").startsWith("Windows")) return;
		Path dll = Path.of("src/client/resources/natives/windows-x86_64/reflexium_sl.dll").toAbsolutePath();
		if (!Files.isRegularFile(dll)) throw new AssertionError("Missing bridge DLL: " + dll);
		StreamlineBridge bridge = new StreamlineBridge(SymbolLookup.libraryLookup(dll, Arena.global()));
		int checked = 0;
		for (Field field : StreamlineBridge.class.getDeclaredFields()) {
			if (field.getType() != MethodHandle.class) continue;
			field.setAccessible(true);
			MethodType expected = EXPECTED.get(field.getName());
			if (expected == null) throw new AssertionError("No expected signature for field " + field.getName());
			MethodType actual = ((MethodHandle) field.get(bridge)).type();
			if (!expected.equals(actual)) {
				throw new AssertionError(field.getName() + ": expected " + expected + " but the bridge linked " + actual);
			}
			checked++;
		}
		if (checked != EXPECTED.size()) throw new AssertionError("Checked " + checked + " of " + EXPECTED.size() + " handles");
	}
}


