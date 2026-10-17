package io.github.itznotneptune.cpxspike;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spike-only structured evidence log: one JSON object per line, prefixed "[CPX-SPIKE]". */
public final class SpikeLog {
	public static final Logger LOG = LoggerFactory.getLogger("cpx-spike");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	public static final List<Map<String, Object>> EVENTS = Collections.synchronizedList(new ArrayList<>());

	private SpikeLog() {}

	public static Map<String, Object> ev(String type, Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("t", type);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		EVENTS.add(m);
		LOG.info("[CPX-SPIKE] {}", GSON.toJson(m));
		return m;
	}

	public static List<Map<String, Object>> of(String type) {
		synchronized (EVENTS) {
			return EVENTS.stream().filter(e -> type.equals(e.get("t"))).toList();
		}
	}

	public static void clear() {
		EVENTS.clear();
	}

	public static String json(Object o) {
		return GSON.toJson(o);
	}

	public static void dump(Path file) {
		try {
			Files.createDirectories(file.getParent());
			StringBuilder sb = new StringBuilder();
			synchronized (EVENTS) {
				for (Map<String, Object> e : EVENTS) sb.append(GSON.toJson(e)).append('\n');
			}
			Files.writeString(file, sb.toString());
		} catch (IOException e) {
			LOG.error("evidence dump failed", e);
		}
	}
}
