package io.github.itznotneptune.cpxspike.s1;

import io.github.itznotneptune.cpxspike.SpikeLog;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;

import io.github.itznotneptune.cpxspike.mixin.ChatComponentAccessorSpike;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike capture logic for M1-M4 (architecture §4.1, P1, P3). Probe-only: it records evidence and applies a debug
 * hide predicate ("[HIDE]" in the original text) so M2/M3 skipping can be observed.
 */
public final class Capture {
	public static final class Frame {
		final int id;
		final Component original;
		final String originalText;
		final String origin;
		final int sizeAtEntry;
		final List<GuiMessage> head;
		final Set<GuiMessage> all;
		boolean decided;
		boolean hidden;
		boolean m2Ret;
		boolean delivered;
		int m3Calls;

		Frame(int id, Component original, String origin, int sizeAtEntry, List<GuiMessage> head, Set<GuiMessage> all) {
			this.id = id; this.original = original; this.originalText = original.getString(); this.origin = origin;
			this.sizeAtEntry = sizeAtEntry; this.head = head; this.all = all;
		}
	}

	public static volatile boolean hideEnabled = true;
	public static volatile boolean nestEnabled = true;
	public static volatile boolean foreignAddEnabled = false;
	public static final AtomicInteger displayQueueCallsNoFrame = new AtomicInteger();
	public static final AtomicInteger displayQueueCallsInFrame = new AtomicInteger();
	public static final List<GuiMessage> HIDDEN = Collections.synchronizedList(new ArrayList<>());
	public static final Map<String, Integer> CAPTURES_BY_TOKEN = Collections.synchronizedMap(new LinkedHashMap<>());

	private static final ArrayDeque<Frame> STACK = new ArrayDeque<>();
	private static final AtomicInteger IDS = new AtomicInteger();
	private static final Pattern TOKEN = Pattern.compile("cpx#[A-Za-z0-9_-]+");

	private Capture() {}

	static List<GuiMessage> all(ChatComponent chat) {
		return ((ChatComponentAccessorSpike) chat).cpx$getAllMessages();
	}

	public static Frame current() { return STACK.peek(); }

	public static void enter(ChatComponent chat, Component original, GuiMessageTag tag) {
		List<GuiMessage> list = all(chat);
		String origin;
		try {
			origin = tag != null && tag.equals(SpikeAdapter.restoredTag()) ? "RESTORED" : "LIVE";
		} catch (RuntimeException e) {
			origin = "UNKNOWN";
		}
		int lines = Minecraft.getInstance().gui.getChat().getLinesPerPage();
		int k = SpikeAdapter.condenseWindow(list.size(), lines);
		List<GuiMessage> head = new ArrayList<>(list.subList(0, Math.min(k, list.size())));
		Set<GuiMessage> allIds = Collections.newSetFromMap(new IdentityHashMap<>());
		allIds.addAll(list);
		STACK.push(new Frame(IDS.incrementAndGet(), original, origin, list.size(), head, allIds));
	}

	public static void exit() {
		Frame f = STACK.pop();
		if (f.decided && !f.delivered) {
			f.delivered = true;
			SpikeLog.ev("deliver_at_exit", "frame", f.id, "token", token(f.originalText));
		}
		if (!f.decided) {
			SpikeLog.ev("frame_no_decision", "frame", f.id, "origin", f.origin, "loading", SpikeAdapter.isLoading(),
					"token", token(f.originalText));
		}
	}

	public static boolean m2(ChatComponent chat, GuiMessage g) {
		Frame f = STACK.peek();
		if (f == null) {
			SpikeLog.ev("m2_noframe");
			return true;
		}
		if (!f.decided) decide(chat, f, g);
		return f.m2Ret;
	}

	public static boolean m3(ChatComponent chat, GuiMessage g) {
		Frame f = STACK.peek();
		if (f == null) {
			SpikeLog.ev("m3_noframe");
			return true;
		}
		if (!f.decided) decide(chat, f, g);
		f.m3Calls++;
		boolean ret = !f.hidden;
		if (!f.delivered) {
			f.delivered = true;
			String tok = token(f.originalText);
			if (tok != null) CAPTURES_BY_TOKEN.merge(tok, 1, Integer::sum);
			if (f.hidden) HIDDEN.add(g);
			SpikeLog.ev("deliver", "frame", f.id, "token", tok, "hidden", f.hidden, "m2_equals_m3", ret == f.m2Ret,
					"m3Calls", f.m3Calls, "depth", STACK.size());
		}
		return ret;
	}

	private static void decide(ChatComponent chat, Frame f, GuiMessage g) {
		List<GuiMessage> list = all(chat);
		if (foreignAddEnabled && f.originalText.contains("cpx-dupe")) {
			list.add(new GuiMessage(0, Component.literal("cpx foreign in-call add"), null, null)); // simulates another mod
		}
		boolean sizeDelta = list.size() < f.sizeAtEntry;
		Set<GuiMessage> now = Collections.newSetFromMap(new IdentityHashMap<>());
		now.addAll(list);
		boolean snapshotMissing = false;
		for (GuiMessage h : f.head) if (!now.contains(h)) { snapshotMissing = true; break; }
		int truthRemoved = 0;
		for (GuiMessage a : f.all) if (!now.contains(a)) truthRemoved++;
		boolean condensed = sizeDelta || snapshotMissing;
		boolean dupeSlot = g.content().getSiblings().size() >= 3 && !g.content().getSiblings().get(2).getString().isEmpty();

		boolean wantsHide = hideEnabled && "LIVE".equals(f.origin) && f.originalText.contains("[HIDE]");
		f.hidden = wantsHide && !condensed && !SpikeAdapter.degraded;
		f.m2Ret = !f.hidden;
		f.decided = true;

		String tok = token(f.originalText);
		SpikeLog.ev("capture", "frame", f.id, "token", tok, "origin", f.origin,
				"originalText", f.originalText, "processedText", g.content().getString(),
				"originalHasCpStructure", cpStructure(f.original), "processedHasCpStructure", cpStructure(g.content()),
				"loading", SpikeAdapter.isLoading(), "restoring", SpikeAdapter.isRestoring(), "depth", STACK.size());
		SpikeLog.ev("condense", "frame", f.id, "token", tok, "k", f.head.size(), "sizeAtEntry", f.sizeAtEntry,
				"sizeNow", list.size(), "sizeDelta", sizeDelta, "snapshotMissing", snapshotMissing,
				"truthRemoved", truthRemoved, "detected", condensed, "dupeSlotDiagnostic", dupeSlot,
				"hidden", f.hidden, "wantsHide", wantsHide);
		if (tok != null && tok.startsWith("cpx#S")) {
			SpikeLog.ev("structure", "token", tok, "origin", f.origin, "tree", struct(g.content()));
		}
		if (nestEnabled && f.originalText.contains("[NEST]") && STACK.size() == 1) {
			Minecraft.getInstance().gui.getChat().addMessage(Component.literal("nested inner cpx#nested-inner"));
		}
	}

	public static String token(String s) {
		Matcher m = TOKEN.matcher(s);
		return m.find() ? m.group() : null;
	}

	static boolean cpStructure(Component c) {
		var sibs = c.getSiblings();
		if (sibs.size() != 3) return false;
		if (!(c.getContents() instanceof PlainTextContents p) || !p.text().isEmpty()) return false;
		String ins = sibs.get(0).getStyle().getInsertion();
		return ins != null && !ins.isEmpty() && ins.chars().allMatch(Character::isDigit);
	}

	public static Map<String, Object> struct(Component c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rootContents", c.getContents().getClass().getSimpleName());
		m.put("rootText", c.getContents() instanceof PlainTextContents p ? p.text() : null);
		var sibs = c.getSiblings();
		m.put("siblings", sibs.size());
		if (!sibs.isEmpty()) {
			String ins = sibs.get(0).getStyle().getInsertion();
			m.put("s0Digits", ins != null && !ins.isEmpty() && ins.chars().allMatch(Character::isDigit));
		}
		if (sibs.size() > 1) m.put("s1Text", sibs.get(1).getString());
		if (sibs.size() > 2) m.put("s2Text", sibs.get(2).getString());
		return m;
	}

	// ---- clear synchronization (V-06) ----
	private static int sizeBeforeClear;
	private static boolean vanillaClearingBefore;

	public static void beforeClear(ChatComponent chat, boolean history) {
		sizeBeforeClear = all(chat).size();
		try { vanillaClearingBefore = SpikeAdapter.vanillaClearing(); } catch (RuntimeException e) { vanillaClearingBefore = false; }
	}

	public static void afterClear(ChatComponent chat, boolean history) {
		boolean predicted = vanillaClearingBefore || !history;
		boolean actualEmpty = all(chat).isEmpty();
		Boolean ok = sizeBeforeClear == 0 ? null : (predicted == actualEmpty);
		SpikeLog.ev("clear", "history", history, "vanillaClearing", vanillaClearingBefore, "sizeBefore", sizeBeforeClear,
				"predictedCleared", predicted, "actualEmpty", actualEmpty, "ok", ok);
		if (predicted) HIDDEN.clear();
	}
}
