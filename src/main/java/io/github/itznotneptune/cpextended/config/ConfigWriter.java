// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes the raw JSON tree back, pretty-printed, in the tree's key order. Every stored string keeps its exact UTF-16
 * contents: control characters and unpaired surrogates are written as {@code \\uXXXX} escapes (UTF-8 can't encode a
 * lone surrogate, and an encoder would silently replace it). Numbers keep their stored text.
 */
public final class ConfigWriter {
	private ConfigWriter() {}

	public static byte[] toBytes(JsonObject root) {
		StringBuilder sb = new StringBuilder();
		write(sb, root, 0);
		sb.append('\n');
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void write(StringBuilder sb, JsonElement e, int indent) {
		if (e == null || e.isJsonNull()) sb.append("null");
		else if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append("{\n");
			int i = 0;
			for (Map.Entry<String, JsonElement> en : o.entrySet()) {
				pad(sb, indent + 1);
				string(sb, en.getKey());
				sb.append(": ");
				write(sb, en.getValue(), indent + 1);
				if (++i < o.size()) sb.append(',');
				sb.append('\n');
			}
			pad(sb, indent);
			sb.append('}');
		} else if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			if (a.isEmpty()) {
				sb.append("[]");
				return;
			}
			sb.append("[\n");
			for (int i = 0; i < a.size(); i++) {
				pad(sb, indent + 1);
				write(sb, a.get(i), indent + 1);
				if (i + 1 < a.size()) sb.append(',');
				sb.append('\n');
			}
			pad(sb, indent);
			sb.append(']');
		} else {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isString()) string(sb, p.getAsString());
			else sb.append(p.getAsString()); // numbers keep their stored text; booleans print as true/false
		}
	}

	private static void pad(StringBuilder sb, int indent) {
		for (int i = 0; i < indent; i++) sb.append("  ");
	}

	static void string(StringBuilder sb, String s) {
		sb.append('"');
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					boolean lone = Character.isHighSurrogate(c) ? i + 1 >= n || !Character.isLowSurrogate(s.charAt(i + 1))
							: Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
					if (c < 0x20 || c == 0x7F || lone || c == ' ' || c == ' ') sb.append(String.format("\\u%04X", (int) c));
					else sb.append(c);
				}
			}
		}
		sb.append('"');
	}
}
