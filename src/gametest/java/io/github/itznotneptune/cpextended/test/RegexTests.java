// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonObject;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.Matcher;
import io.github.itznotneptune.cpextended.filter.PatternValidator;
import io.github.itznotneptune.cpextended.filter.RegexMatcher;
import io.github.itznotneptune.cpextended.filter.RegexSupport;
import io.github.itznotneptune.cpextended.gui.TabScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.github.itznotneptune.cpextended.test.Phase2Tests.cfg;
import static io.github.itznotneptune.cpextended.test.Phase2Tests.doc;
import static io.github.itznotneptune.cpextended.test.Phase2Tests.window;
import static io.github.itznotneptune.cpextended.test.Phase2Tests.write;

/** Regex filters (D6.1; Q15 resolved 2026-11-22: RE2/J with a bounded program). Mode {@code regex}. */
final class RegexTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase4Tests p4;

	RegexTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p4 = new Phase4Tests(t);
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	void regex() {
		validator();
		boundNeverUndershoots();
		performance();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			routing(sp);
			hostileConfig(sp);
			gui(sp);
		}
		t.waitT(20);
	}

	// =================================================================== validation

	void validator() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("\\[SC\\]", null);
		expected.put("^<\\w{3,16}> ", null);
		expected.put("(a+)+$", null);                       // catastrophic for backtracking engines; linear on RE2/J
		expected.put(".{400}", null);                       // real program ~403 instructions
		expected.put("[\\s\\S]{900}x", null);               // real ~903: under the cap although the estimate is higher
		expected.put("(a|b", RegexSupport.INVALID);
		expected.put("(a)\\1", RegexSupport.INVALID);       // backreferences aren't supported
		expected.put("(?=a)", RegexSupport.INVALID);        // lookarounds aren't supported
		expected.put("x{1001}", RegexSupport.INVALID);      // RE2/J's own repeat limit
		expected.put("a{1000}", RegexSupport.TOO_COMPLEX);  // real ~1002 > 1000
		expected.put("(a{1000}){1000}", RegexSupport.TOO_COMPLEX);
		expected.put("((a{1000}){1000}){1000}", RegexSupport.TOO_COMPLEX); // would exhaust the heap if compiled
		expected.put("", "empty");
		expected.put("x".repeat(1025), "too_long");
		Map<String, Object> observed = new LinkedHashMap<>();
		boolean ok = true;
		long worstMs = 0;
		for (Map.Entry<String, String> e : expected.entrySet()) {
			long t0 = System.nanoTime();
			String got = PatternValidator.validate(FilterSpec.regex(e.getKey()));
			worstMs = Math.max(worstMs, (System.nanoTime() - t0) / 1_000_000);
			String label = e.getKey().length() > 40 ? e.getKey().substring(0, 20) + "...(" + e.getKey().length() + ")" : e.getKey();
			observed.put(label, got == null ? "valid" : got);
			if (!java.util.Objects.equals(got, e.getValue())) ok = false;
		}
		String detail = PatternValidator.regexDetail(FilterSpec.regex("(a|b"));
		boolean detailOk = detail != null && detail.contains("missing closing )");
		// Case sensitivity changes what matches, not validity.
		boolean caseOk = RegexMatcher.of("hello", false).matches("HeLLo there") && !RegexMatcher.of("hello", true).matches("HeLLo there")
				&& RegexMatcher.of("hello", true).matches("say hello");
		ev.result("RX.validator", ok && detailOk && caseOk && worstMs < 200, "observed", observed, "syntaxErrorDetail", detail,
				"caseSensitivity", caseOk, "worstValidationMs", worstMs);
	}

	/** The pre-compile bound must never be below RE2/J's real program size, or a hostile pattern could reach the compiler. */
	void boundNeverUndershoots() {
		String[] atoms = {"a", "ab", ".", "\\w", "\\d", "[a-z]", "[^x]", "\\pL", "^", "$", "\\b", "é", "😀", "(?i:k)", "x|y"};
		String[] ops = {"", "", "*", "+", "?", "*?", "{2}", "{0,3}", "{3,}", "{0}", "{1,9}"};
		Random r = new Random(7);
		int checked = 0, under = 0;
		String example = null;
		for (int i = 0; i < 20_000; i++) {
			String p = gen(r, atoms, ops, 0);
			boolean cs = r.nextBoolean();
			long est = RegexSupport.estimate(p, cs);
			if (est < 0 || est > 4 * RegexSupport.MAX_PROGRAM) continue;
			RegexSupport.Result res = RegexSupport.compile(p, cs);
			if (res.pattern() == null) continue;
			checked++;
			if (est < res.pattern().programSize()) {
				under++;
				if (example == null) example = p + " est=" + est + " real=" + res.pattern().programSize();
			}
		}
		ev.result("RX.boundSafety", under == 0 && checked > 10_000, "checked", checked, "underestimates", under, "firstUnderestimate", example);
	}

	private static String gen(Random r, String[] atoms, String[] ops, int depth) {
		StringBuilder sb = new StringBuilder();
		int n = 1 + r.nextInt(3);
		for (int i = 0; i < n; i++) {
			if (depth < 3 && r.nextInt(4) == 0) {
				String inner = gen(r, atoms, ops, depth + 1);
				if (r.nextBoolean()) inner += "|" + gen(r, atoms, ops, depth + 1);
				sb.append(r.nextBoolean() ? "(" : "(?:").append(inner).append(')');
			} else sb.append(atoms[r.nextInt(atoms.length)]);
			sb.append(ops[r.nextInt(ops.length)]);
		}
		return sb.toString();
	}

	/** Worst accepted pattern shape (about the program cap) against long messages; and a typical pattern. */
	void performance() {
		Matcher worst = RegexMatcher.of("[\\s\\S]{900}x", false);
		Matcher typical = RegexMatcher.of("^\\[(Admin|Mod|Helper)\\] \\w{3,16}:", false);
		String chat256 = ("<Steve> hello there, how are you doing today? " + "y".repeat(256)).substring(0, 256);
		String long4k = "a".repeat(4096);
		double worst256 = time(worst, chat256), worst4k = time(worst, long4k), typical256 = time(typical, chat256);
		ev.result("RX.performance", worst256 < 25 && typical256 < 1, "worstPattern256CharsMs", worst256, "worstPattern4096CharsMs", worst4k,
				"typicalPattern256CharsMs", typical256, "programCap", RegexSupport.MAX_PROGRAM,
				"note", "worst accepted program (~900 instructions); time per message per filter, best of 10 after warm-up");
	}

	private static double time(Matcher m, String text) {
		for (int i = 0; i < 5; i++) m.matches(text);
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 10; i++) {
			long t0 = System.nanoTime();
			m.matches(text);
			best = Math.min(best, System.nanoTime() - t0);
		}
		return best / 1e6;
	}

	// =================================================================== in game

	static String rx(String pattern, boolean caseSensitive) {
		return "{\"mode\": \"regex\", \"pattern\": " + Phase2Tests.jsonStr(pattern) + (caseSensitive ? ", \"caseSensitive\": true" : "") + "}";
	}

	static String tab(String id, String include, String exclude) {
		return "{\"id\": \"" + id + "\", \"name\": \"" + id + "\", \"include\": [" + include + "], \"exclude\": [" + exclude + "]}";
	}

	long tokInTab(String tab, String token) {
		return t.tabEntries(tab).stream().filter(r -> r.original() != null && r.original().getString().contains(token)).count();
	}

	void restart() { ctx.runOnClient(c -> mgr().devSimulateRestart()); }

	void routing(TestSingleplayerContext sp) {
		write(cfg(), doc(window("w-rx",
				tab("rx-shop", rx("^\\[Shop\\] ", false), "") + ","
				+ tab("rx-ci", rx("hello (world|there)", false), "") + ","
				+ tab("rx-cs", rx("Hello", true), "") + ","
				+ tab("rx-ex", Phase2Tests.filterJson("literal", "cpx#RX"), rx("\\bspam\\b", false)))));
		restart();
		Map<String, List<String>> errs = onClient(() -> Router.get().config().configErrorSummary());
		String[] msgs = {"[Shop] apples cpx#RX-1", "not [Shop] at start cpx#RX-2", "HELLO WORLD cpx#RX-3", "Hello friend cpx#RX-4",
				"buy spam now cpx#RX-5", "spammy but fine cpx#RX-6"};
		for (String m : msgs) t.sys(sp, m);
		t.waitT(10);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("anchoredMatch", tokInTab("rx-shop", "cpx#RX-1") == 1);
		m.put("anchoredNoMatchMidText", tokInTab("rx-shop", "cpx#RX-2") == 0);
		m.put("caseInsensitiveMatch", tokInTab("rx-ci", "cpx#RX-3") == 1);
		m.put("caseInsensitiveNoMatch", tokInTab("rx-ci", "cpx#RX-4") == 0);
		m.put("caseSensitiveMatch", tokInTab("rx-cs", "cpx#RX-4") == 1);
		m.put("caseSensitiveRejectsOtherCase", tokInTab("rx-cs", "cpx#RX-3") == 0);
		m.put("regexExcludeApplied", tokInTab("rx-ex", "cpx#RX-5") == 0);
		m.put("wordBoundaryRespected", tokInTab("rx-ex", "cpx#RX-6") == 1);
		boolean ok = errs.isEmpty() && m.values().stream().allMatch(Boolean.TRUE::equals);
		ev.result("RX.routing", ok, "configErrors", errs, "observed", m);
	}

	/** A hand-edited pattern that would exhaust the heap if compiled: rejected at once, tab suspended, the rest unaffected. */
	void hostileConfig(TestSingleplayerContext sp) {
		write(cfg(), doc(window("w-rh",
				tab("h-bomb", rx("((a{1000}){1000}){1000}", false), "") + ","
				+ tab("h-syntax", rx("(unclosed", false), "") + ","
				+ tab("h-ok", Phase2Tests.filterJson("literal", "cpx#RH"), ""))));
		long t0 = System.nanoTime();
		restart();
		long loadMs = (System.nanoTime() - t0) / 1_000_000;
		Map<String, List<String>> errs = onClient(() -> Router.get().config().configErrorSummary());
		t.sys(sp, "still routing cpx#RH-1 " + "a".repeat(200));
		t.waitT(8);
		boolean ok = String.valueOf(errs.get("h-bomb")).contains(RegexSupport.TOO_COMPLEX) && String.valueOf(errs.get("h-syntax")).contains(RegexSupport.INVALID)
				&& !errs.containsKey("h-ok") && tokInTab("h-ok", "cpx#RH-1") == 1 && t.tabEntries("h-bomb").isEmpty() && loadMs < 3000;
		ev.result("RX.hostileConfig", ok, "configErrors", errs, "reloadMs", loadMs);
	}

	void gui(TestSingleplayerContext sp) {
		write(cfg(), doc(window("w-rg", tab("g-rx", Phase2Tests.filterJson("literal", "cpx#RG-old"), ""))));
		restart();
		Map<String, Object> m = new LinkedHashMap<>();
		TabScreen ts = p4.openTab("g-rx");
		TabScreen.FilterRow row = onClient(() -> ts.addFilter("include"));
		ctx.runOnClient(c -> ts.toggleMode(row));
		m.put("toggledToRegex", onClient(() -> FilterSpec.REGEX.equals(row.mode()) && row.box() != null));
		// invalid regex: save blocked, inline error names the problem
		EditBox box1 = onClient(row::box);
		ctx.runOnClient(c -> box1.setValue("cpx#RG-("));
		boolean blocked = !onClient(ts::save);
		String err = onClient(row::error);
		m.put("invalidBlocked", blocked);
		m.put("invalidError", err);
		// switching mode keeps the text exactly (Q14.1(4))
		EditBox box2 = onClient(row::box);
		ctx.runOnClient(c -> box2.setValue("cpx#RG-\\d+"));
		ctx.runOnClient(c -> ts.toggleMode(row));
		boolean keptLiteral = onClient(() -> FilterSpec.LITERAL.equals(row.mode()) && row.box() != null && "cpx#RG-\\d+".equals(row.box().getValue()));
		ctx.runOnClient(c -> ts.toggleMode(row));
		boolean keptRegex = onClient(() -> FilterSpec.REGEX.equals(row.mode()) && row.box() != null && "cpx#RG-\\d+".equals(row.box().getValue()));
		m.put("textKeptAcrossModeSwitch", keptLiteral && keptRegex);
		boolean saved = onClient(ts::save);
		m.put("validSaved", saved);
		ctx.setScreen(() -> null);
		JsonObject f = Phase2Tests.json(cfg());
		JsonObject stored = Phase4Tests.tab(f, "g-rx").getAsJsonArray("include").get(1).getAsJsonObject();
		boolean storedOk = "regex".equals(stored.get("mode").getAsString()) && "cpx#RG-\\d+".equals(stored.get("pattern").getAsString());
		m.put("storedAsRegex", storedOk);
		t.sys(sp, "number cpx#RG-42");
		t.sys(sp, "no number cpx#RG-x");
		t.waitT(8);
		boolean routed = tokInTab("g-rx", "cpx#RG-42") == 1 && tokInTab("g-rx", "cpx#RG-x") == 0;
		m.put("routedBySavedRegex", routed);
		// too complex: blocked with an explanation
		TabScreen ts2 = p4.openTab("g-rx");
		TabScreen.FilterRow row2 = onClient(() -> ts2.addFilter("exclude"));
		ctx.runOnClient(c -> ts2.toggleMode(row2));
		EditBox box3 = onClient(row2::box);
		ctx.runOnClient(c -> box3.setValue("(a{1000}){1000}"));
		boolean complexBlocked = !onClient(ts2::save);
		String complexErr = onClient(row2::error);
		m.put("tooComplexBlocked", complexBlocked);
		m.put("tooComplexError", complexErr);
		ctx.setScreen(() -> null);
		boolean ok = Boolean.TRUE.equals(m.get("toggledToRegex")) && blocked && err != null && err.startsWith("Invalid regex: missing closing )")
				&& keptLiteral && keptRegex && saved && storedOk && routed && complexBlocked && complexErr != null && complexErr.contains("too complex");
		ev.result("RX.gui", ok, "observed", m);
	}
}
