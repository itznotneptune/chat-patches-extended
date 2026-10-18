// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evidence records for one launch, written to {@code cpx-evidence/<mode>.jsonl}. Synthetic test content only. */
final class Evidence {
	static final Logger LOG = LoggerFactory.getLogger("cpextended-test");
	enum Outcome { PASS, FAIL, INCONCLUSIVE }

	private final String mode;
	private final List<Map<String, Object>> records = new ArrayList<>();

	Evidence(String mode) {
		this.mode = mode;
	}

	void result(String test, Outcome outcome, Object... details) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("t", "result");
		e.put("mode", mode);
		e.put("test", test);
		e.put("outcome", outcome.name());
		for (int i = 0; i + 1 < details.length; i += 2) e.put(String.valueOf(details[i]), details[i + 1]);
		add(e);
	}

	void result(String test, boolean pass, Object... details) {
		result(test, pass ? Outcome.PASS : Outcome.FAIL, details);
	}

	void data(String type, Object... kv) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("t", type);
		for (int i = 0; i + 1 < kv.length; i += 2) e.put(String.valueOf(kv[i]), kv[i + 1]);
		add(e);
	}

	private void add(Map<String, Object> e) {
		records.add(e);
		LOG.info("[CPX-M1A] {}", Json.write(e));
	}

	void write() {
		try {
			Path p = Path.of("cpx-evidence", mode + ".jsonl");
			Files.createDirectories(p.getParent());
			StringBuilder sb = new StringBuilder();
			for (Map<String, Object> e : records) sb.append(Json.write(e)).append('\n');
			Files.writeString(p, sb.toString());
		} catch (Exception ex) {
			LOG.error("could not write evidence", ex);
		}
	}
}
