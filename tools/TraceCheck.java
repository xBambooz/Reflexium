import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline checker for Reflexium's bounded FG/Reflex API trace ({@code set reflex_trace}). Run with:
 *
 *   java tools/TraceCheck.java check <client.log|trace.txt> [generatedPerReal] [latency.json]
 *   java tools/TraceCheck.java self-check
 *
 * It accepts client logs ("[FG API] frame=N t=T event") or sanitized traces ("frame=N event"). Frames cut by the
 * armed window's start or end are reported as partial and excluded. Every complete frame must have each
 * low_latency2 marker pair exactly once in order, balanced out-of-band pairs, one real present, the expected
 * generated presents, and exactly one sleep (or one recorded skip). Submit lines from current builds carry
 * call identity and batch index; legacy traces without them are reported as ambiguous rather than counted as calls.
 * Timestamps are the CPU monotonic clock only; nothing here is correlated with GPU time. Optional driver latency
 * reports are joined by frame id, never by read order, and internally inconsistent reports are rejected.
 */
public final class TraceCheck {
	private static final Pattern LINE = Pattern.compile("frame=(\\d+)(?: t=(\\d+))? (.*)$");
	private static final int[] ORDER = {0, 6, 1, 2, 3, 4, 5};

	record Event(long frame, long t, String text) { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) throw new IllegalArgumentException("check <trace> [generatedPerReal] [latency.json] | self-check");
		switch (args[0]) {
			case "check" -> {
				Result result = check(Files.readAllLines(Path.of(args[1])), args.length > 2 ? Integer.parseInt(args[2]) : -1,
						args.length > 3 ? Files.readString(Path.of(args[3])) : null);
				System.out.println(result.json());
				if (!result.violations.isEmpty()) System.exit(1);
			}
			case "self-check" -> selfCheck();
			default -> throw new IllegalArgumentException("unknown command " + args[0]);
		}
	}

	static final class Result {
		final List<String> violations = new ArrayList<>();
		final List<String> limits = new ArrayList<>();
		final Map<String, Object> stats = new LinkedHashMap<>();
		String json() {
			StringBuilder out = new StringBuilder("{\n  \"violations\": ").append(strings(violations)).append(",\n  \"limits\": ").append(strings(limits));
			for (var entry : stats.entrySet()) out.append(",\n  \"").append(entry.getKey()).append("\": ").append(value(entry.getValue()));
			return out.append("\n}").toString();
		}
		private static String strings(List<String> values) {
			return values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList().toString();
		}
		private static String value(Object v) {
			return v instanceof String s ? "\"" + s + "\"" : v instanceof Double d ? String.format(Locale.ROOT, "%.3f", d) : String.valueOf(v);
		}
	}

	static Result check(List<String> lines, int generatedPerReal, String latencyJson) {
		Result result = new Result();
		TreeMap<Long, List<Event>> frames = new TreeMap<>();
		long lastT = Long.MIN_VALUE;
		boolean timed = false, newFormat = false, callIds = false;
		for (String line : lines) {
			Matcher m = LINE.matcher(line);
			if (!m.find()) continue;
			long frame = Long.parseLong(m.group(1)), t = m.group(2) == null ? -1 : Long.parseLong(m.group(2));
			String text = m.group(3).trim();
			if (t >= 0) {
				timed = true;
				if (t < lastT) result.violations.add("timestamp went backwards at frame " + frame + ": " + text);
				lastT = t;
			}
			newFormat |= text.startsWith("sleep ") || text.startsWith("encoder submit");
			callIds |= text.startsWith("submit call=");
			if (frame == 0) { result.violations.add("unattributed event while armed: " + text); continue; }
			frames.computeIfAbsent(frame, k -> new ArrayList<>()).add(new Event(frame, t, text));
		}
		if (frames.isEmpty()) { result.violations.add("no traced frames"); return result; }
		if (!timed) result.limits.add("no timestamps: legacy trace, durations unavailable");
		if (!newFormat) result.limits.add("no sleep or completion events: legacy trace, sleep counts unverified");
		if (!callIds) result.violations.add("ambiguous batch/call counts: submit lines lack call identity");
		long previous = -1;
		int complete = 0, partial = 0, segments = 0;
		List<Double> sleepCall = new ArrayList<>(), sleepWait = new ArrayList<>(), period = new ArrayList<>(), completion = new ArrayList<>();
		long calls = 0, batches = 0, waits = 0, generated = 0, real = 0;
		Long lastStart = null;
		for (var entry : frames.entrySet()) {
			long id = entry.getKey();
			List<Event> events = entry.getValue();
			// A gap starts another armed window (several captures can share one file); its edge frames may be cut.
			boolean first = previous < 0 || id != previous + 1, last = !frames.containsKey(id + 1);
			if (first) { segments++; lastStart = null; }
			previous = id;
			List<Integer> markers = new ArrayList<>();
			for (Event e : events) if (e.text.startsWith("marker=")) markers.add(Integer.parseInt(e.text.substring(7).trim()));
			if ((first || last) && (!markers.contains(0) || !markers.contains(5))) { partial++; continue; }
			complete++;
			String tag = "frame " + id + ": ";
			int position = -1;
			for (int marker : ORDER) {
				long count = markers.stream().filter(v -> v == marker).count();
				if (count != 1) { result.violations.add(tag + "marker " + marker + " seen " + count + " times"); continue; }
				int at = markers.indexOf(marker);
				if (at < position) result.violations.add(tag + "marker " + marker + " out of order");
				position = Math.max(position, at);
			}
			for (int[] pair : new int[][]{{8, 9}, {10, 11}}) {
				int open = 0;
				for (int marker : markers) {
					if (marker == pair[0]) { if (open++ != 0) result.violations.add(tag + "nested out-of-band marker " + pair[0]); }
					else if (marker == pair[1]) { if (--open != 0) result.violations.add(tag + "unmatched out-of-band marker " + pair[1]); open = 0; }
				}
				if (open != 0) result.violations.add(tag + "unclosed out-of-band marker " + pair[0]);
			}
			int sleepCalls = 0, sleepExits = 0, sleepWaits = 0, skips = 0, reals = 0, gens = 0, batchBegins = 0, batchEnds = 0;
			Map<Long, boolean[]> callBatches = new LinkedHashMap<>();
			long callT = -1, exitT = -1;
			for (Event e : events) {
				String text = e.text;
				if (text.startsWith("sleep call value=")) { sleepCalls++; callT = e.t; }
				else if (text.startsWith("sleep call exit")) { sleepExits++; exitT = e.t; if (callT >= 0 && e.t >= 0) sleepCall.add((e.t - callT) / 1000.0); }
				else if (text.startsWith("sleep wait exit")) { sleepWaits++; if (exitT >= 0 && e.t >= 0) sleepWait.add((e.t - exitT) / 1000.0); }
				else if (text.startsWith("sleep skipped")) skips++;
				else if (text.startsWith("present role=real")) reals++;
				else if (text.startsWith("present role=generated")) gens++;
				else if (text.startsWith("metering batch begin")) batchBegins++;
				else if (text.startsWith("metering batch end")) batchEnds++;
				else if (text.startsWith("submit call=")) {
					Matcher c = Pattern.compile("submit call=(\\d+) batch=(\\d+)/(\\d+)").matcher(text);
					if (!c.find()) { result.violations.add(tag + "malformed submit line"); continue; }
					boolean[] seen = callBatches.computeIfAbsent(Long.parseLong(c.group(1)), k -> new boolean[Integer.parseInt(c.group(3))]);
					int batch = Integer.parseInt(c.group(2));
					if (batch >= seen.length || seen[batch]) result.violations.add(tag + "ambiguous batch " + batch + " in call " + c.group(1));
					else seen[batch] = true;
					batches++;
				} else if (text.startsWith("submit ")) batches++;
				else if (text.startsWith("completion wait exit")) waits++;
			}
			for (int i = 0; i + 1 < events.size(); i++) {
				if (events.get(i).text.startsWith("completion wait index") && events.get(i + 1).text.startsWith("completion wait exit") && events.get(i).t >= 0)
					completion.add((events.get(i + 1).t - events.get(i).t) / 1000.0);
			}
			for (var call : callBatches.entrySet()) for (boolean seen : call.getValue()) if (!seen) result.violations.add(tag + "call " + call.getKey() + " missing a batch");
			calls += callBatches.size();
			if (newFormat && !(sleepCalls == 1 && sleepExits == 1 && sleepWaits == 1 && skips == 0 || sleepCalls == 0 && sleepExits == 0 && sleepWaits == 0 && skips == 1))
				result.violations.add(tag + "unexpected sleep events: calls " + sleepCalls + ", exits " + sleepExits + ", waits " + sleepWaits + ", skips " + skips);
			if (reals != 1) result.violations.add(tag + reals + " real presents");
			if (generatedPerReal >= 0 && gens != generatedPerReal) result.violations.add(tag + gens + " generated presents, expected " + generatedPerReal);
			if (newFormat && gens > 0 && (batchBegins != 1 || batchEnds != 1)) result.violations.add(tag + "metering batch begin/end " + batchBegins + "/" + batchEnds);
			generated += gens; real += reals;
			Event start = events.stream().filter(e -> e.text.equals("marker=0")).findFirst().orElse(null);
			if (start != null && lastStart != null && start.t >= 0) period.add((start.t - lastStart) / 1000.0);
			lastStart = start != null && start.t >= 0 ? start.t : null;
		}
		result.stats.put("armed_windows", segments);
		result.stats.put("frames_complete", complete);
		result.stats.put("frames_partial", partial);
		result.stats.put("real_presents", real);
		result.stats.put("generated_presents", generated);
		result.stats.put("submit_batches", batches);
		result.stats.put("submit_calls", callIds ? String.valueOf(calls) : "unknown (legacy)");
		result.stats.put("completion_waits", newFormat ? String.valueOf(waits) : "unknown (legacy)");
		summarize(result, "sleep_call_us", sleepCall);
		summarize(result, "sleep_wait_us", sleepWait);
		summarize(result, "completion_wait_us", completion);
		summarize(result, "sim_start_period_us", period);
		if (latencyJson != null) joinLatency(result, frames, latencyJson);
		return result;
	}

	private static void summarize(Result result, String name, List<Double> values) {
		if (values.isEmpty()) { result.stats.put(name, "unavailable"); return; }
		List<Double> sorted = values.stream().sorted().toList();
		result.stats.put(name + "_n", sorted.size());
		result.stats.put(name + "_median", sorted.get(sorted.size() / 2));
		result.stats.put(name + "_p90", sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1)));
		result.stats.put(name + "_mean", sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
	}

	/** Driver reports are matched by frame id only; internally inconsistent reports are rejected, not averaged. */
	private static void joinLatency(Result result, TreeMap<Long, List<Event>> frames, String json) {
		Matcher frame = Pattern.compile("\\{([^{}]*)\\}").matcher(json);
		int matched = 0, inconsistent = 0, reports = 0;
		while (frame.find()) {
			Map<String, Long> f = new LinkedHashMap<>();
			Matcher kv = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)").matcher(frame.group(1));
			while (kv.find()) f.put(kv.group(1), Long.parseLong(kv.group(2)));
			if (!f.containsKey("id")) continue;
			reports++;
			if (!frames.containsKey(f.get("id"))) continue;
			matched++;
			if (f.getOrDefault("sim_start", 0L) > f.getOrDefault("sim_end", 0L) || f.getOrDefault("submit_start", 0L) > f.getOrDefault("submit_end", 0L)
					|| f.getOrDefault("present_start", 0L) > f.getOrDefault("present_end", 0L) || f.getOrDefault("driver_start", 0L) > f.getOrDefault("driver_end", 0L)
					|| f.getOrDefault("gpu_start", 0L) > f.getOrDefault("gpu_end", 0L)) inconsistent++;
		}
		result.stats.put("latency_reports", reports);
		result.stats.put("latency_reports_matched_by_frame_id", matched);
		result.stats.put("latency_reports_inconsistent", inconsistent);
		if (inconsistent > 0) result.violations.add("internally inconsistent driver latency reports: " + inconsistent);
		if (matched == 0) result.limits.add("no driver latency report shares a frame id with the trace");
	}

	private static List<String> frame(long id, long t, int generated, boolean skipSleep) {
		List<String> out = new ArrayList<>();
		long[] clock = {t};
		java.util.function.Consumer<String> add = text -> out.add("[FG API] frame=" + id + " t=" + (clock[0] += 1000) + " " + text);
		if (skipSleep) add.accept("sleep skipped configured=true failed=false suspended=true paused=false");
		else { add.accept("sleep call value=" + id + " applied=ON interval_us=0 requested=ON"); add.accept("sleep call exit result=0"); add.accept("sleep wait exit value=" + id + " result=0"); }
		for (String text : new String[]{"marker=0", "marker=6", "marker=1", "marker=2", "encoder submit begin index=1 completed=0",
				"submit call=" + (2 * id) + " batch=0/2 queue=1 tag=implicit fence=false", "submit call=" + (2 * id) + " batch=1/2 queue=1 tag=implicit fence=false",
				"encoder submit end index=2 completed=0", "marker=3", "marker=4"}) add.accept(text);
		if (generated > 0) {
			add.accept("metering batch begin presents=" + (generated + 1));
			for (int i = 0; i < generated; i++) { add.accept("marker=10"); add.accept("marker=11"); add.accept("present role=generated result=0 feedback=0x1"); add.accept("marker=8");
				add.accept("submit call=" + (2 * id + 1) + " batch=0/1 queue=1 tag=implicit fence=false"); add.accept("marker=9"); }
			add.accept("marker=10"); add.accept("marker=11");
		}
		add.accept("present role=real result=0 feedback=0x1");
		if (generated > 0) add.accept("metering batch end presented=2/2 rejected=false");
		add.accept("marker=5");
		return out;
	}

	private static void selfCheck() {
		List<String> good = new ArrayList<>();
		for (long id = 10; id < 14; id++) good.addAll(frame(id, id * 100_000, 1, false));
		Result ok = check(good, 1, "{\"frames\":[{\"id\":11,\"sim_start\":1,\"sim_end\":2,\"gpu_start\":3,\"gpu_end\":4}]}");
		expect(ok.violations.isEmpty(), "valid FG2 trace: " + ok.violations);
		expect(ok.stats.get("frames_complete").equals(4) && ok.stats.get("latency_reports_matched_by_frame_id").equals(1), "complete frames and latency join");
		List<String> skipped = new ArrayList<>();
		for (long id = 10; id < 14; id++) skipped.addAll(frame(id, id * 100_000, 1, id == 12));
		expect(check(skipped, 1, null).violations.isEmpty(), "recorded skip counts as one sleep decision");
		List<String> duplicate = new ArrayList<>(good);
		duplicate.add(duplicate.indexOf("[FG API] frame=11 t=" + (11 * 100_000 + 5000) + " marker=0") + 1, "[FG API] frame=11 t=" + (11 * 100_000 + 5000) + " marker=0");
		expect(check(duplicate, 1, null).violations.stream().anyMatch(v -> v.contains("marker 0 seen 2")), "duplicate marker");
		List<String> missing = new ArrayList<>(good);
		missing.removeIf(l -> l.contains("frame=12 ") && l.endsWith("marker=3"));
		expect(check(missing, 1, null).violations.stream().anyMatch(v -> v.contains("marker 3 seen 0")), "missing marker");
		List<String> twoSleeps = new ArrayList<>(good);
		twoSleeps.add(twoSleeps.indexOf(twoSleeps.stream().filter(l -> l.contains("frame=11 ") && l.contains("sleep call exit")).findFirst().orElseThrow()),
				"[FG API] frame=11 t=" + (11 * 100_000 + 2500) + " sleep call value=99 applied=ON interval_us=0 requested=ON");
		expect(check(twoSleeps, 1, null).violations.stream().anyMatch(v -> v.contains("unexpected sleep events")), "unexpected sleep count");
		List<String> ambiguous = new ArrayList<>(good);
		ambiguous.replaceAll(l -> l.contains("frame=12 ") && l.contains("batch=1/2") ? l.replace("batch=1/2", "batch=0/2") : l);
		expect(check(ambiguous, 1, null).violations.stream().anyMatch(v -> v.contains("ambiguous batch")), "ambiguous batch");
		List<String> unattributed = new ArrayList<>(good);
		unattributed.add(5, "[FG API] frame=0 t=" + (10 * 100_000 + 4500) + " submit call=1 batch=0/1 queue=1 tag=implicit fence=false");
		expect(check(unattributed, 1, null).violations.stream().anyMatch(v -> v.contains("unattributed")), "missing attribution");
		List<String> legacy = good.stream().filter(l -> !l.contains(" sleep ") && !l.contains("encoder submit") && !l.contains("metering batch"))
				.map(l -> l.replaceAll("^\\[FG API\\] ", "").replaceAll(" t=\\d+", "").replaceAll("submit call=\\d+ batch=\\d+/\\d+ ", "submit ")).toList();
		Result old = check(legacy, 1, null);
		expect(old.violations.size() == 1 && old.violations.get(0).startsWith("ambiguous batch/call") && old.limits.size() == 2, "legacy trace limits: " + old.violations);
		expect(!check(good, 5, null).violations.isEmpty(), "wrong generated count");
		expect(check(good, 1, "[{\"id\":11,\"sim_start\":20,\"sim_end\":10}]").violations.stream().anyMatch(v -> v.contains("internally inconsistent")), "inverted driver timestamps must fail");
		System.out.println("TraceCheck self-check passed");
	}

	private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
