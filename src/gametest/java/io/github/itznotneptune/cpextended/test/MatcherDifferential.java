// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.filter.LiteralMatcher;
import io.github.itznotneptune.cpextended.filter.MatchText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** R-1 checks: the optimized {@link LiteralMatcher} against the 1a {@link ReferenceLiteralMatcher}. Pure JVM code. */
final class MatcherDifferential {
	/** Building blocks: ASCII, Latin-1 specials, case oddities, combining marks, formatting codes, supplementary, lone surrogates. */
	static final String[] TOKENS = {
			"a", "A", "b", "B", "z", "Z", "s", "S", "k", "K", "i", "I", "0", "7", " ", "  ", ".", "*", "\\", "^", "$", "(", ")", "[", "]", "|", "?", "+",
			"ß", "ÿ", "µ", "À", "à", "Þ", "þ", "×", "÷", "ª", "º", "É", "é",
			"Σ", "σ", "ς", "Ω", "ω", "ΐ", "ẞ", "K", "Å", "Ǆ", "ǅ", "ǆ", "İ", "ı",
			"Ⴀ", "ⴀ", "ა", "Ა", "Ꭰ", "ꭰ", "中", "ﬀ",
			"́", "̈", "‍", "️", "§a", "§l", "§r", "§",
			"😀", "𐐀", "𐐨", "𐒰", "𐓘", "𞤀", "𞤢", "𝐀",
			"\uD801", "\uDC00", "\uDE00", "\uD83D"};
	static final String[] LATIN1_TOKENS = {"a", "A", "b", "B", "s", "S", " ", ".", "*", "ß", "ÿ", "µ", "À", "à", "Þ", "þ", "×", "÷", "É", "é", "§a"};

	private MatcherDifferential() {}

	static String randomText(Random r, String[] tokens, int maxUnits) {
		StringBuilder sb = new StringBuilder();
		int target = r.nextInt(maxUnits + 1);
		while (sb.length() < target) sb.append(tokens[r.nextInt(tokens.length)]);
		return sb.toString();
	}

	/** Flips case code point by code point at random (upper, lower, title, unchanged). */
	static String caseScramble(Random r, String s) {
		StringBuilder sb = new StringBuilder(s.length());
		s.codePoints().forEach(cp -> {
			int v = switch (r.nextInt(4)) {
				case 0 -> Character.toUpperCase(cp);
				case 1 -> Character.toLowerCase(cp);
				case 2 -> Character.toTitleCase(cp);
				default -> cp;
			};
			sb.appendCodePoint(v);
		});
		// toUpperCase etc. never change the UTF-16 length for these inputs except where code points change plane; keep exact units
		return sb.toString();
	}

	static Map<String, Object> differential(long seed, int cases) {
		Random r = new Random(seed);
		int checked = 0, positives = 0, lowSurrogateStart = 0;
		List<String> mismatches = new ArrayList<>();
		for (int i = 0; i < cases; i++) {
			String[] pool = r.nextInt(4) == 0 ? LATIN1_TOKENS : TOKENS;
			String text = randomText(r, pool, 300);
			String pattern;
			int kind = r.nextInt(4);
			if (kind <= 1 && text.length() > 0) {
				int a = r.nextInt(text.length()), b = a + 1 + r.nextInt(Math.min(60, text.length() - a));
				pattern = text.substring(a, b); // may split a surrogate pair on purpose
				if (kind == 1) pattern = caseScramble(r, pattern);
			} else {
				pattern = randomText(r, pool, 8);
			}
			if (pattern.isEmpty()) pattern = pool[r.nextInt(pool.length)];
			if (Character.isLowSurrogate(pattern.charAt(0))) lowSurrogateStart++;
			for (boolean cs : new boolean[]{false, true}) {
				boolean ref = new ReferenceLiteralMatcher(pattern, cs).matches(text);
				LiteralMatcher opt = new LiteralMatcher(pattern, cs);
				boolean got = opt.matches(text);
				boolean gotShared = opt.matches(MatchText.reusable(text));
				checked++;
				if (ref) positives++;
				if (ref != got || ref != gotShared) {
					if (mismatches.size() < 20) mismatches.add(escape(pattern) + " | " + escape(text) + " | cs=" + cs + " ref=" + ref + " opt=" + got + " shared=" + gotShared);
				}
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("seed", seed);
		m.put("comparisons", checked);
		m.put("referencePositives", positives);
		m.put("patternsStartingWithLowSurrogate", lowSurrogateStart);
		m.put("mismatches", mismatches);
		return m;
	}

	/** Every BMP unit against its case variants and neighbours, as single units and embedded in text. */
	static Map<String, Object> singleUnitSweep() {
		int checked = 0;
		List<String> mismatches = new ArrayList<>();
		for (int c = 0; c <= 0xFFFF; c++) {
			int[] variants = {c, Character.toUpperCase(c), Character.toLowerCase(c), Character.toTitleCase(c),
					Character.toLowerCase(Character.toUpperCase(c)), Character.toUpperCase(Character.toLowerCase(c)), (c + 1) & 0xFFFF, c ^ 0x20};
			String p = String.valueOf((char) c);
			for (int d : variants) {
				if (d > 0xFFFF) continue;
				String t1 = String.valueOf((char) d), t2 = "x" + (char) d + "y";
				for (String t : new String[]{t1, t2}) {
					boolean ref = new ReferenceLiteralMatcher(p, false).matches(t);
					boolean got = new LiteralMatcher(p, false).matches(t);
					checked++;
					if (ref != got && mismatches.size() < 20) mismatches.add(Integer.toHexString(c) + " vs " + Integer.toHexString(d) + " ref=" + ref);
				}
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("comparisons", checked);
		m.put("mismatches", mismatches);
		return m;
	}

	/** Cased supplementary blocks: every code point against its upper/lower forms, as whole pairs and inside text. */
	static Map<String, Object> supplementarySweep() {
		int[][] blocks = {{0x10400, 0x1044F}, {0x104B0, 0x104FF}, {0x10570, 0x105BF}, {0x10C80, 0x10CFF}, {0x118A0, 0x118FF},
				{0x16E40, 0x16E9F}, {0x1E900, 0x1E95F}, {0x1F600, 0x1F64F}, {0x1D400, 0x1D44F}};
		int checked = 0;
		List<String> mismatches = new ArrayList<>();
		for (int[] b : blocks) {
			for (int cp = b[0]; cp <= b[1]; cp++) {
				String p = new String(Character.toChars(cp));
				for (int v : new int[]{cp, Character.toUpperCase(cp), Character.toLowerCase(cp), cp + 1}) {
					String vs = new String(Character.toChars(v));
					for (String t : new String[]{vs, "a" + vs + "b", "\uD801" + vs, vs + "\uDC00", p.substring(0, 1) + vs.substring(1)}) {
						for (String pat : new String[]{p, p + "x", "a" + p, p.substring(1)}) {
							boolean ref = new ReferenceLiteralMatcher(pat, false).matches(t);
							boolean got = new LiteralMatcher(pat, false).matches(t);
							checked++;
							if (ref != got && mismatches.size() < 20) mismatches.add(escape(pat) + " | " + escape(t) + " ref=" + ref);
						}
					}
				}
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("comparisons", checked);
		m.put("mismatches", mismatches);
		return m;
	}

	/** Patterns at the 1,023 / 1,024-unit limit (P8), BMP and surrogate-heavy, matching and not. */
	static Map<String, Object> boundaryLengths(long seed) {
		Random r = new Random(seed);
		int checked = 0;
		List<String> mismatches = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			String text = randomText(r, i % 2 == 0 ? TOKENS : LATIN1_TOKENS, 4096);
			while (text.length() < 1100) text += randomText(r, TOKENS, 500);
			for (int len : new int[]{1023, 1024}) {
				int a = r.nextInt(text.length() - len);
				String pattern = text.substring(a, a + len);
				if (r.nextBoolean()) pattern = caseScramble(r, pattern);
				if (pattern.length() > 1024) pattern = pattern.substring(0, 1024);
				if (r.nextInt(3) == 0) pattern = pattern.substring(0, pattern.length() - 1) + "\u0000";
				for (boolean cs : new boolean[]{false, true}) {
					boolean ref = new ReferenceLiteralMatcher(pattern, cs).matches(text);
					boolean got = new LiteralMatcher(pattern, cs).matches(text);
					checked++;
					if (ref != got && mismatches.size() < 10) mismatches.add("len=" + pattern.length() + " cs=" + cs + " ref=" + ref);
				}
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("comparisons", checked);
		m.put("mismatches", mismatches);
		return m;
	}

	interface Scan { boolean run(String text); }

	/** Worst and mean time for 20 tabs on one message: reference (fresh per tab) vs optimized (shared MatchText). */
	static Map<String, Object> timing(String pattern, String text, boolean cs, int iterations) {
		List<ReferenceLiteralMatcher> ref = new ArrayList<>();
		List<LiteralMatcher> opt = new ArrayList<>();
		for (int t = 0; t < 20; t++) {
			ref.add(new ReferenceLiteralMatcher(pattern, cs));
			opt.add(new LiteralMatcher(pattern, cs));
		}
		boolean refResult = false, optResult = false;
		long refWorst = 0, refTotal = 0, optWorst = 0, optTotal = 0;
		for (int i = 0; i < iterations + 2; i++) { // two warm-up rounds
			long t0 = System.nanoTime();
			for (ReferenceLiteralMatcher m : ref) refResult = m.matches(text);
			long t1 = System.nanoTime();
			MatchText mt = MatchText.reusable(text);
			for (LiteralMatcher m : opt) optResult = m.matches(mt);
			long t2 = System.nanoTime();
			if (i < 2) continue;
			refWorst = Math.max(refWorst, t1 - t0);
			refTotal += t1 - t0;
			optWorst = Math.max(optWorst, t2 - t1);
			optTotal += t2 - t1;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("textUnits", text.length());
		m.put("patternUnits", pattern.length());
		m.put("caseSensitive", cs);
		m.put("iterations", iterations);
		m.put("sameResult", refResult == optResult);
		m.put("referenceWorstMs", ms(refWorst));
		m.put("referenceMeanMs", ms(refTotal / iterations));
		m.put("optimizedWorstMs", ms(optWorst));
		m.put("optimizedMeanMs", ms(optTotal / iterations));
		return m;
	}

	static double ms(long nanos) {
		return Math.round(nanos / 1000.0) / 1000.0;
	}

	static String escape(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04X", (int) c));
			else sb.append(c);
		}
		return sb.length() > 120 ? sb.substring(0, 120) + "…" : sb.toString();
	}
}
