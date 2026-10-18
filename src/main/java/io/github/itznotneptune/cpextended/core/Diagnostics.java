// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import io.github.itznotneptune.cpextended.CpExtended;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer diagnostics, off unless {@code -Dcpextended.debug=true}. Events carry identifiers, counts and flags only,
 * never message text (privacy).
 */
public final class Diagnostics {
	public static final boolean ENABLED = Boolean.getBoolean("cpextended.debug");
	private static final int RING = 20_000;
	private static final ArrayDeque<Map<String, Object>> EVENTS = new ArrayDeque<>();

	private Diagnostics() {}

	/** Records an event; {@code kv} alternates keys and values (numbers, booleans, enums, identifiers; never chat text). */
	public static void event(String type, Object... kv) {
		if (!ENABLED) return;
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("t", type);
		for (int i = 0; i + 1 < kv.length; i += 2) e.put(String.valueOf(kv[i]), kv[i + 1] instanceof Enum<?> en ? en.name() : kv[i + 1]);
		synchronized (EVENTS) {
			if (EVENTS.size() == RING) EVENTS.pollFirst();
			EVENTS.addLast(e);
		}
		CpExtended.LOGGER.info("[diag] {}", Json.write(e));
	}

	public static List<Map<String, Object>> events(String type) {
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (EVENTS) {
			for (Map<String, Object> e : EVENTS) if (type.equals(e.get("t"))) out.add(e);
		}
		return out;
	}

	public static void clear() {
		synchronized (EVENTS) { EVENTS.clear(); }
	}

	/** State snapshot for debugging (no message text). */
	public static String dump() {
		Map<String, Object> s = new LinkedHashMap<>();
		ChatPatchesAdapter a = ChatPatchesAdapter.get();
		s.put("degraded", a.isDegraded());
		s.put("searchIntegrationOff", a.isSearchIntegrationOff());
		s.put("failedOps", a.failedOps().toString());
		SessionMirror m = SessionMirror.get();
		s.put("records", m.size());
		List<Object> tabs = new ArrayList<>();
		for (TabState t : m.tabStates()) {
			Map<String, Object> tm = new LinkedHashMap<>();
			tm.put("id", t.id());
			tm.put("entries", t.size());
			tm.put("runtimeSuspended", t.isRuntimeSuspended());
			tabs.add(tm);
		}
		s.put("tabs", tabs);
		s.put("configErrors", Router.get().config().configErrorSummary());
		s.put("warnings", Warnings.counts());
		String json = Json.write(s);
		if (ENABLED) CpExtended.LOGGER.info("[diag-dump] {}", json);
		return json;
	}
}
