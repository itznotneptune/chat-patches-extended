// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.model.TabConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads {@code cpextended.json} (schema §7.2, validation §7.3). Never repairs anything: problems are reported as issues,
 * the affected entries are suspended, and the raw tree keeps every stored value exactly.
 */
public final class ConfigParser {
	public static final String DUPLICATE_WINDOW = "duplicate_window_id";
	/** Placeholder mode for a filter entry that isn't an object or has no string pattern; invalid ("unknown mode"). */
	public static final String MALFORMED_FILTER = "<malformed filter entry>";

	public enum Status { OK, UNPARSEABLE, UNSUPPORTED_SCHEMA }

	/** {@code config} is set for OK and for UNSUPPORTED_SCHEMA when the root is readable (understood fields, read-only). */
	public record Result(Status status, @Nullable ExtendedConfig config, @Nullable String error) {}

	private ConfigParser() {}

	public static Result parse(byte[] bytes) {
		JsonObject root;
		try {
			String text = decodeUtf8(bytes);
			JsonElement e = readStrict(text);
			if (!e.isJsonObject()) return new Result(Status.UNPARSEABLE, null, "root is not a JSON object");
			root = e.getAsJsonObject();
		} catch (IOException | RuntimeException ex) {
			return new Result(Status.UNPARSEABLE, null, ex.getMessage());
		}
		return interpret(root);
	}

	/** Builds the understood view of an already-parsed tree (also used after in-memory edits). */
	public static Result interpret(JsonObject root) {
		List<String> issues = new ArrayList<>();
		int version;
		JsonElement v = root.get("schemaVersion");
		if (v == null) {
			version = ExtendedConfig.SCHEMA_VERSION;
			issues.add("schemaVersion missing; read as " + ExtendedConfig.SCHEMA_VERSION);
		} else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && isInteger(v.getAsJsonPrimitive())) {
			version = v.getAsInt();
		} else {
			return new Result(Status.UNSUPPORTED_SCHEMA, build(root, -1, true, issues), "schemaVersion is not an integer");
		}
		if (version != ExtendedConfig.SCHEMA_VERSION) {
			issues.add("schemaVersion " + version + " is not supported (this version reads " + ExtendedConfig.SCHEMA_VERSION + ")");
			return new Result(Status.UNSUPPORTED_SCHEMA, build(root, version, true, issues), "unsupported schemaVersion " + version);
		}
		return new Result(Status.OK, build(root, version, false, issues), null);
	}

	private static ExtendedConfig build(JsonObject root, int version, boolean newer, List<String> issues) {
		JsonObject general = object(root, "general", issues, "general");
		boolean enabled = bool(general, "enabled", true, issues, "general.enabled");
		boolean hiding = bool(general, "hidingEnabled", true, issues, "general.hidingEnabled");
		JsonObject ap = object(root, "appearance", issues, "appearance");
		ExtendedConfig.Appearance appearance = new ExtendedConfig.Appearance(num(ap, "backgroundOpacity", issues), num(ap, "textOpacity", issues),
				num(ap, "scale", issues), num(ap, "lineSpacing", issues));

		List<ExtendedConfig.Window> windows = new ArrayList<>();
		JsonElement ws = root.get("windows");
		if (ws != null && !ws.isJsonArray()) issues.add("windows is not an array; no windows loaded (value kept)");
		Set<String> windowIds = new HashSet<>();
		if (ws != null && ws.isJsonArray()) {
			JsonArray arr = ws.getAsJsonArray();
			for (int i = 0; i < arr.size(); i++) {
				JsonElement we = arr.get(i);
				if (!we.isJsonObject()) {
					issues.add("windows[" + i + "] is not an object (kept, ignored)");
					continue;
				}
				windows.add(window(we.getAsJsonObject(), i, windowIds, issues));
			}
		}
		return new ExtendedConfig(root, version, newer, enabled, hiding, appearance, List.copyOf(windows), List.copyOf(issues));
	}

	private static ExtendedConfig.Window window(JsonObject w, int index, Set<String> ids, List<String> issues) {
		List<String> errors = new ArrayList<>();
		String id = string(w, "id");
		String where = "windows[" + index + "]";
		if (id == null || id.isEmpty()) {
			errors.add("no usable id");
			id = null;
			issues.add(where + " has no usable id (kept, suspended)");
		} else if (!ids.add(id)) {
			errors.add(DUPLICATE_WINDOW);
			issues.add(where + " duplicates window id '" + id + "' (kept, suspended)");
		}
		boolean enabled = bool(w, "enabled", true, issues, where + ".enabled");
		ExtendedConfig.Anchor anchor = ExtendedConfig.Anchor.TOP_LEFT;
		JsonElement a = w.get("anchor");
		if (a != null) {
			String s = a.isJsonPrimitive() && a.getAsJsonPrimitive().isString() ? a.getAsString() : null;
			try {
				anchor = ExtendedConfig.Anchor.valueOf(s == null ? "" : s.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				issues.add(where + ".anchor '" + a + "' unknown; drawn as TOP_LEFT (value kept)");
			}
		}
		double x = geometry(w, "x", 0.01, issues, where), y = geometry(w, "y", 0.10, issues, where);
		double width = geometry(w, "width", 0.30, issues, where), height = geometry(w, "height", 0.25, issues, where);
		String selected = string(w, "selectedTab");
		List<ExtendedConfig.Tab> tabs = new ArrayList<>();
		JsonElement ts = w.get("tabs");
		if (ts != null && !ts.isJsonArray()) issues.add(where + ".tabs is not an array (value kept)");
		if (ts != null && ts.isJsonArray()) {
			JsonArray arr = ts.getAsJsonArray();
			for (int i = 0; i < arr.size(); i++) {
				JsonElement te = arr.get(i);
				if (!te.isJsonObject()) {
					issues.add(where + ".tabs[" + i + "] is not an object (kept, ignored)");
					continue;
				}
				tabs.add(tab(te.getAsJsonObject(), where + ".tabs[" + i + "]", issues));
			}
		}
		if (selected != null && tabs.stream().noneMatch(t -> selected.equals(t.id()))) {
			issues.add(where + ".selectedTab '" + selected + "' not found; the first tab is shown (value kept)");
		}
		return new ExtendedConfig.Window(w, id, enabled, anchor, x, y, width, height, selected, List.copyOf(tabs), List.copyOf(errors), index);
	}

	private static ExtendedConfig.Tab tab(JsonObject t, String where, List<String> issues) {
		String id = string(t, "id");
		if (id == null || id.isEmpty()) {
			issues.add(where + " has no usable id (kept, suspended)");
			return new ExtendedConfig.Tab(t, null, null, List.of("no usable id"));
		}
		String name = string(t, "name");
		TabConfig cfg = new TabConfig(id, name == null ? id : name,
				bool(t, "enabled", true, issues, where + ".enabled"),
				bool(t, "hideFromMainChat", false, issues, where + ".hideFromMainChat"),
				bool(t, "restoreHistory", true, issues, where + ".restoreHistory"),
				bool(t, "matchAll", false, issues, where + ".matchAll"),
				filters(t, "include", where, issues), filters(t, "exclude", where, issues));
		return new ExtendedConfig.Tab(t, id, cfg, List.of());
	}

	private static List<FilterSpec> filters(JsonObject t, String key, String where, List<String> issues) {
		JsonElement e = t.get(key);
		List<FilterSpec> out = new ArrayList<>();
		if (e == null) return out;
		if (!e.isJsonArray()) {
			// Treated as one malformed filter so the tab is suspended rather than silently matching differently.
			issues.add(where + "." + key + " is not an array (tab suspended, value kept)");
			out.add(new FilterSpec(MALFORMED_FILTER, null, false));
			return out;
		}
		for (JsonElement fe : e.getAsJsonArray()) {
			if (!fe.isJsonObject()) {
				out.add(new FilterSpec(MALFORMED_FILTER, null, false));
				continue;
			}
			JsonObject f = fe.getAsJsonObject();
			JsonElement m = f.get("mode");
			String mode;
			if (m == null || m.isJsonNull()) mode = null;
			else if (m.isJsonPrimitive() && m.getAsJsonPrimitive().isString()) mode = m.getAsString();
			else mode = MALFORMED_FILTER;
			JsonElement p = f.get("pattern");
			String pattern = p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isString() ? p.getAsString() : null;
			if (p != null && pattern == null) mode = MALFORMED_FILTER;
			JsonElement cs = f.get("caseSensitive");
			boolean caseSensitive = false;
			if (cs != null && !cs.isJsonNull()) {
				if (cs.isJsonPrimitive() && cs.getAsJsonPrimitive().isBoolean()) caseSensitive = cs.getAsBoolean();
				else mode = MALFORMED_FILTER;
			}
			out.add(new FilterSpec(mode, pattern, caseSensitive));
		}
		return out;
	}

	// ---------------------------------------------------------------- field helpers (no repair, defaults are view-only)

	private static JsonObject object(JsonObject parent, String key, List<String> issues, String where) {
		JsonElement e = parent.get(key);
		if (e == null || e.isJsonNull()) return new JsonObject();
		if (e.isJsonObject()) return e.getAsJsonObject();
		issues.add(where + " is not an object; defaults used (value kept)");
		return new JsonObject();
	}

	private static boolean bool(JsonObject o, String key, boolean def, List<String> issues, String where) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return def;
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
		issues.add(where + " is not a boolean; " + def + " used (value kept)");
		return def;
	}

	private static @Nullable Double num(JsonObject o, String key, List<String> issues) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return null;
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
		issues.add("appearance." + key + " is not a number; vanilla setting used (value kept)");
		return null;
	}

	private static double geometry(JsonObject o, String key, double def, List<String> issues, String where) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return def;
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			double d = e.getAsDouble();
			if (Double.isFinite(d)) return d;
		}
		issues.add(where + "." + key + " is not a finite number; " + def + " used when drawing (value kept)");
		return def;
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean isInteger(JsonPrimitive p) {
		try {
			new java.math.BigDecimal(p.getAsString()).intValueExact();
			return true;
		} catch (ArithmeticException | NumberFormatException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- strict reading

	private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
		String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		return s.startsWith("﻿") ? s.substring(1) : s;
	}

	/** Strict JSON (no comments, no trailing data); a repeated key in one object is rejected rather than silently dropped. */
	static JsonElement readStrict(String text) throws IOException {
		try (JsonReader r = new JsonReader(new StringReader(text))) {
			r.setStrictness(Strictness.STRICT);
			JsonElement e = read(r);
			if (r.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing data after the JSON value");
			return e;
		}
	}

	private static JsonElement read(JsonReader r) throws IOException {
		switch (r.peek()) {
			case BEGIN_OBJECT -> {
				JsonObject o = new JsonObject();
				r.beginObject();
				while (r.hasNext()) {
					String name = r.nextName();
					if (o.has(name)) throw new IOException("duplicate key '" + name + "'");
					o.add(name, read(r));
				}
				r.endObject();
				return o;
			}
			case BEGIN_ARRAY -> {
				JsonArray a = new JsonArray();
				r.beginArray();
				while (r.hasNext()) a.add(read(r));
				r.endArray();
				return a;
			}
			case STRING -> { return new JsonPrimitive(r.nextString()); }
			case NUMBER -> { return new JsonPrimitive(new LazilyParsedNumber(r.nextString())); }
			case BOOLEAN -> { return new JsonPrimitive(r.nextBoolean()); }
			case NULL -> {
				r.nextNull();
				return JsonNull.INSTANCE;
			}
			default -> throw new IOException("unexpected token " + r.peek());
		}
	}
}
