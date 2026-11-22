// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Regex filters (D6.1; Q15 resolved 2026-11-22: RE2/J, bounded). Every regex goes through {@link #compile} before it
 * can run, which guarantees:
 * <ul>
 *   <li><b>No backtracking.</b> RE2/J's matching time is linear in the message length for a given compiled program.</li>
 *   <li><b>A bounded program.</b> RE2/J's cost is proportional to (program size x message length), and nested counted
 *   repeats such as {@code (a{1000}){1000}} expand to millions of instructions; compiling them can exhaust the heap
 *   before a size check could run. So the pattern is first parsed with RE2/J's own parser (cheap, repeats stay
 *   unexpanded), an upper bound of the compiled size is computed from that tree, and anything above
 *   {@link #PRECHECK_LIMIT} is rejected <i>before</i> compiling (the bound never undershoots; fuzz-checked against
 *   RE2/J). Whatever does compile is accepted only if its real program size is at most {@link #MAX_PROGRAM}.</li>
 * </ul>
 * With the cap, the worst case is about 1,000 instructions x 18 ns per instruction and character, i.e. about 5 ms for
 * a 256-character message, and typical patterns compile to a few dozen instructions.
 * <p>
 * The parse tree is read through RE2/J internals (package-private {@code Parser} and {@code Regexp}). RE2/J is
 * bundled at a fixed version, so these are fixed too; if they ever can't be reached, every regex filter is rejected
 * ({@code regex_unavailable}, tab suspended) and nothing else is affected.
 */
public final class RegexSupport {
	/** Maximum compiled program size, in RE2/J instructions. Real chat patterns use well under a few hundred. */
	public static final int MAX_PROGRAM = 1000;
	/**
	 * Pre-compile gate on the estimated bound. The bound can be up to about twice the real size for long repeats, so the
	 * gate is generous; compiling anything under it takes well under a megabyte. {@link #MAX_PROGRAM} decides.
	 */
	static final int PRECHECK_LIMIT = 4 * MAX_PROGRAM;

	public static final String INVALID = "regex_invalid";
	public static final String TOO_COMPLEX = "regex_too_complex";
	public static final String UNAVAILABLE = "regex_unavailable";

	private RegexSupport() {}

	/** A compiled regex, or why it was rejected ({@code detail}: RE2/J's description of a syntax error). */
	public record Result(@Nullable Pattern pattern, @Nullable String error, @Nullable String detail) {
		static Result ok(Pattern p) { return new Result(p, null, null); }
		static Result fail(String error, @Nullable String detail) { return new Result(null, error, detail); }
	}

	public static Result compile(String regex, boolean caseSensitive) {
		Internals in = Internals.get();
		if (in == null) return Result.fail(UNAVAILABLE, Internals.failure);
		// Same expression and flags as Pattern.compile(regex, flags) hands to its parser.
		String expr = caseSensitive ? regex : "(?i)" + regex;
		Object tree;
		try {
			tree = in.parse(expr);
		} catch (PatternSyntaxException e) {
			return Result.fail(INVALID, e.getDescription());
		} catch (ReflectiveOperationException | RuntimeException e) {
			return Result.fail(UNAVAILABLE, e.toString());
		}
		long bound;
		try {
			bound = in.bound(tree);
		} catch (ReflectiveOperationException e) {
			return Result.fail(UNAVAILABLE, e.toString());
		}
		if (bound > PRECHECK_LIMIT) return Result.fail(TOO_COMPLEX, null);
		try {
			Pattern p = Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
			if (p.programSize() > MAX_PROGRAM) return Result.fail(TOO_COMPLEX, null);
			return Result.ok(p);
		} catch (PatternSyntaxException e) {
			return Result.fail(INVALID, e.getDescription());
		}
	}

	/** The estimated upper bound for a pattern, for verification only; -1 when it can't be parsed. */
	public static long estimate(String regex, boolean caseSensitive) {
		Internals in = Internals.get();
		if (in == null) return -1;
		try {
			return in.bound(in.parse(caseSensitive ? regex : "(?i)" + regex));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return -1;
		}
	}

	/** Reflective access to RE2/J's parser and parse tree, resolved once. */
	private static final class Internals {
		private static volatile @Nullable Internals instance;
		private static volatile boolean resolved;
		static volatile @Nullable String failure;

		private final Method parse;
		private final int perlFlags;
		private final Field op, subs, runes, min, max;

		private Internals() throws ReflectiveOperationException {
			Class<?> parser = Class.forName("com.google.re2j.Parser");
			Class<?> re2 = Class.forName("com.google.re2j.RE2");
			Class<?> regexp = Class.forName("com.google.re2j.Regexp");
			parse = parser.getDeclaredMethod("parse", String.class, int.class);
			parse.setAccessible(true);
			Field perl = re2.getDeclaredField("PERL");
			perl.setAccessible(true);
			perlFlags = perl.getInt(null);
			op = field(regexp, "op");
			subs = field(regexp, "subs");
			runes = field(regexp, "runes");
			min = field(regexp, "min");
			max = field(regexp, "max");
		}

		private static Field field(Class<?> c, String name) throws NoSuchFieldException {
			Field f = c.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		}

		static @Nullable Internals get() {
			if (!resolved) {
				synchronized (Internals.class) {
					if (!resolved) {
						try {
							instance = new Internals();
						} catch (ReflectiveOperationException | RuntimeException e) {
							failure = e.toString();
						}
						resolved = true;
					}
				}
			}
			return instance;
		}

		Object parse(String expr) throws ReflectiveOperationException {
			try {
				return parse.invoke(null, expr, perlFlags);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof PatternSyntaxException pse) throw pse;
				throw e;
			}
		}

		/** Upper bound of the compiled program, saturating above the cap so huge nestings can't overflow. */
		long bound(Object tree) throws ReflectiveOperationException {
			return Math.addExact(size(tree), 2); // the program's own fail and match instructions
		}

		private long size(Object re) throws ReflectiveOperationException {
			String kind = ((Enum<?>) op.get(re)).name();
			switch (kind) {
				case "LITERAL": {
					int[] r = (int[]) runes.get(re);
					return Math.max(1, r == null ? 0 : r.length);
				}
				case "CAPTURE":
					return cap(sub(re, 0) + 2);
				case "STAR": // x* is a split around x; for a nullable x RE2/J compiles (x+)? instead: two extra instructions
					return cap(sub(re, 0) + 2);
				case "PLUS": case "QUEST": // the operand plus one split instruction
					return cap(sub(re, 0) + 1);
				case "REPEAT": {
					// RE2 expands x{n,m} into n copies of x followed by (m - n) nested optional copies (one split each), and
					// x{n,} into n copies followed by x*. Every copy is therefore at most (size(x) + 1).
					int lo = min.getInt(re), hi = max.getInt(re);
					long copies = hi < 0 ? (long) lo + 1 : hi;
					return cap(Math.max(1, copies) * (sub(re, 0) + 1) + 1);
				}
				case "CONCAT": case "ALTERNATE": { // the operands, plus one split per alternative
					Object[] s = (Object[]) subs.get(re);
					long total = s == null ? 1 : s.length;
					if (s != null) for (Object x : s) total = cap(total + size(x));
					return total;
				}
				default: // char classes, any-char, anchors, word boundaries, empty and no-match: one instruction each
					return 1;
			}
		}

		private long sub(Object re, int i) throws ReflectiveOperationException {
			Object[] s = (Object[]) subs.get(re);
			return s == null || s.length <= i ? 1 : size(s[i]);
		}

		/** Anything above the cap is rejected anyway; saturate so products of nested repeats stay small. */
		private static long cap(long v) {
			return Math.min(v, (long) MAX_PROGRAM * 1000);
		}
	}
}
