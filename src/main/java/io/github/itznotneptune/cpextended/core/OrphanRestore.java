// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import io.github.itznotneptune.cpextended.CpExtended;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.mixin.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Orphan restoration (Q2, Q22, Q27, P5, P6, C-1; commit design Q36). A configuration change orphans a hidden message
 * when every tab holding it is deleted; such messages go back to the main chat in capture order, or the whole change is
 * refused. This class owns the session side; {@code ConfigManager} owns the configuration and file side and calls
 * {@link #plan} and {@link #commit}.
 *
 * <p><b>Plan</b> (no mutation): safe point (client thread, no chat screen, no capture frame, Chat Patches neither
 * loading nor restoring), else refused, never queued; the actual main-chat cap through the adapter (unreadable or
 * degraded: refused, C-1); the mirror aligned with the main chat; a projection that merges the orphans by capture
 * sequence among the visible entries (unrecorded entries anchored to their older recorded neighbour) and trims it
 * oldest-first to the cap. If even one orphan would be trimmed the change is refused (Q27, P6): no log copy is ever
 * treated as a recoverable copy. Every structure the commit needs is built here as a fresh object.
 *
 * <p><b>Commit</b> (one synchronous section on the client thread): swap the prepared references in (the main chat's
 * list through the Q36 {@code @Mutable} setter, the mirror's lists and tab map, each orphan's record fields, then the
 * caller's configuration), then run the caller's atomic file replacement. If that fails, every previous reference is
 * reassigned (no file write); memory and disk agree either way. Orphans never pass through {@code addMessage}, so Chat
 * Patches neither reprocesses nor re-logs them.
 *
 * <p><b>Post-commit</b> (derived state): entries the projection trimmed leave their tabs (liveness), then
 * {@code rescaleChat()} rebuilds the lines. A rebuild failure doesn't undo the commit; it marks the lines dirty and
 * retries with the chat screen closed (up to three attempts), then warns once and retries at the next chat close.
 *
 * <p>Not claimed: disk and memory are not one atomic transaction (architecture §4.9 failure taxonomy). JVM errors are
 * not caught.
 */
public final class OrphanRestore {
	public enum Refusal { BUSY, CAP_UNKNOWN, WOULD_PRUNE_ORPHAN, MAIN_CHAT_INCONSISTENT }

	/** Result of planning: no orphans (ordinary change), refused, or ready to confirm and commit. */
	public sealed interface Outcome permits NoOrphans, Refused, Ready {}

	public record NoOrphans(Set<String> deletedTabs) implements Outcome {}

	public record Refused(Refusal kind, String message) implements Outcome {}

	public record Ready(Plan plan) implements Outcome {}

	/** A complete projection, valid only for the synchronous section it was computed in (or for disclosure). */
	public static final class Plan {
		final Set<String> deletedTabs;
		final Set<String> tabsWithOrphans;
		final List<MessageRecord> orphans;
		final List<GuiMessage> newAll;
		final ArrayDeque<MessageRecord> newRecords;
		final ArrayDeque<MessageRecord> newHidden;
		final Map<String, TabState> newTabs;
		final List<MessageRecord> prunedVisible;
		final List<List<String>> orphanOldTabIds;
		final int cap;

		Plan(Set<String> deletedTabs, Set<String> tabsWithOrphans, List<MessageRecord> orphans, List<GuiMessage> newAll, ArrayDeque<MessageRecord> newRecords,
				ArrayDeque<MessageRecord> newHidden, Map<String, TabState> newTabs, List<MessageRecord> prunedVisible, int cap) {
			this.deletedTabs = deletedTabs;
			this.tabsWithOrphans = tabsWithOrphans;
			this.orphans = orphans;
			this.newAll = newAll;
			this.newRecords = newRecords;
			this.newHidden = newHidden;
			this.newTabs = newTabs;
			this.prunedVisible = prunedVisible;
			List<List<String>> old = new ArrayList<>(orphans.size());
			for (MessageRecord r : orphans) old.add(r.tabIds());
			this.orphanOldTabIds = old;
			this.cap = cap;
		}

		/** Hidden messages that go back to the main chat. */
		public int restoredCount() { return orphans.size(); }
		/** Ordinary visible messages the main-chat cap will drop (disclosed, Q22). */
		public int prunedCount() { return prunedVisible.size(); }
		public Set<String> tabsWithOrphans() { return tabsWithOrphans; }
		public Set<String> deletedTabs() { return deletedTabs; }
		public int cap() { return cap; }
	}

	/** The caller's configuration swap (plain assignments, both ways). */
	public interface ConfigSwap {
		void swapIn();
		void swapBack();
	}

	/** The caller's atomic file step; returns null on success, else why it failed (the file is then untouched). */
	public interface FileStep {
		@Nullable String replace();
	}

	public record CommitResult(boolean committed, String message, boolean linesDirty) {}

	public static final String KEEP_DISABLE_MOVE = "You can keep, disable or move the tab instead.";

	private static boolean linesDirty;
	private static int rebuildAttempts;
	private static int nextRebuildTick;
	private static boolean chatWasOpen;
	private static int devRescaleFailures;
	private static @Nullable Runnable devCommitProbe;
	private static @Nullable Runnable devBeforeSwapProbe;
	private static @Nullable Runnable devAfterReplaceProbe;
	private static boolean devForceBusy;

	private OrphanRestore() {}

	// ---------------------------------------------------------------- planning

	/**
	 * Plans a change from a configuration with tab ids {@code currentTabIds} to one with {@code nextTabIds}. Pure with
	 * respect to live state, except that a misaligned mirror is reconciled first (a safe point is required anyway).
	 */
	public static Outcome plan(Set<String> currentTabIds, Set<String> nextTabIds) {
		SessionMirror mirror = SessionMirror.get();
		Set<String> deleted = new LinkedHashSet<>(currentTabIds);
		deleted.removeAll(nextTabIds);
		List<MessageRecord> orphans = orphansOf(mirror, currentTabIds, nextTabIds);
		if (orphans.isEmpty()) return new NoOrphans(deleted);
		Set<String> withOrphans = new LinkedHashSet<>();
		for (MessageRecord r : orphans) for (String id : r.tabIds()) if (currentTabIds.contains(id)) withOrphans.add(id);

		Minecraft mc = Minecraft.getInstance();
		ChatPatchesAdapter adapter = ChatPatchesAdapter.get();
		boolean safe = mc.isSameThread() && !(mc.screen instanceof ChatScreen) && Capture.depth() == 0 && adapter.isSafePoint()
				&& !(devForceBusy && DevHooks.isDev());
		if (!safe) {
			return new Refused(Refusal.BUSY, "Chat is busy (open, loading or restoring its history), so the hidden messages in " + withOrphans
					+ " can't be moved back safely right now. Nothing was changed; try the change again in a moment.");
		}
		OptionalInt capRead = adapter.isDegraded() ? OptionalInt.empty() : adapter.chatMaxMessages();
		if (capRead.isEmpty()) {
			return new Refused(Refusal.CAP_UNKNOWN, "Chat Patches' chat history limit can't be read reliably, so moving the hidden messages in "
					+ withOrphans + " back to the main chat can't be checked for loss. Nothing was changed. " + KEEP_DISABLE_MOVE);
		}
		int cap = capRead.getAsInt();
		ChatComponent chat = mc.gui.getChat();
		List<GuiMessage> all = Capture.allMessages(chat);
		if (!aligned(mirror.records, all)) {
			Reconciler.run(chat);
			all = Capture.allMessages(chat);
			if (!aligned(mirror.records, all)) {
				return new Refused(Refusal.MAIN_CHAT_INCONSISTENT, "The main chat changed in a way Extended can't account for, so the hidden messages in "
						+ withOrphans + " can't be placed safely. Nothing was changed; try again in a moment.");
			}
		}

		// Projection: visible records (oldest first) with the orphans merged by capture sequence.
		List<MessageRecord> merged = new ArrayList<>(mirror.records.size() + orphans.size());
		int oi = 0;
		long anchor = Long.MIN_VALUE;
		for (MessageRecord v : mirror.records) {
			long key = v.origin() == Origin.UNOBSERVED ? anchor : v.seq();
			while (oi < orphans.size() && orphans.get(oi).seq() < key) merged.add(orphans.get(oi++));
			merged.add(v);
			anchor = key;
		}
		while (oi < orphans.size()) merged.add(orphans.get(oi++));

		int trim = Math.max(0, merged.size() - cap);
		Set<MessageRecord> orphanSet = Collections.newSetFromMap(new IdentityHashMap<>());
		orphanSet.addAll(orphans);
		int orphansPruned = 0;
		List<MessageRecord> prunedVisible = new ArrayList<>();
		for (int i = 0; i < trim; i++) {
			if (orphanSet.contains(merged.get(i))) orphansPruned++;
			else prunedVisible.add(merged.get(i));
		}
		if (orphansPruned > 0) {
			return new Refused(Refusal.WOULD_PRUNE_ORPHAN, orphansPruned + " of the " + orphans.size() + " hidden messages in " + withOrphans
					+ " are older than everything the main chat keeps (limit " + cap + "), so moving them back would lose them at once. Nothing was changed. "
					+ KEEP_DISABLE_MOVE);
		}

		// Fresh structures for the commit.
		List<GuiMessage> newAll = new ArrayList<>(merged.size() - trim);
		for (int i = merged.size() - 1; i >= trim; i--) newAll.add(merged.get(i).message());
		ArrayDeque<MessageRecord> newRecords = new ArrayDeque<>(Math.max(16, merged.size() - trim));
		for (int i = trim; i < merged.size(); i++) newRecords.addLast(merged.get(i));
		ArrayDeque<MessageRecord> newHidden = new ArrayDeque<>(Math.max(16, mirror.hidden.size()));
		for (MessageRecord h : mirror.hidden) if (!orphanSet.contains(h)) newHidden.addLast(h);
		Map<String, TabState> newTabs = new LinkedHashMap<>(mirror.tabs);
		newTabs.keySet().removeAll(deleted);
		return new Ready(new Plan(deleted, withOrphans, List.copyOf(orphans), newAll, newRecords, newHidden, newTabs, prunedVisible, cap));
	}

	/** Hidden records held by some tab in {@code current} and by none in {@code next}, in capture order. */
	static List<MessageRecord> orphansOf(SessionMirror mirror, Set<String> current, Set<String> next) {
		List<MessageRecord> out = new ArrayList<>();
		for (MessageRecord r : mirror.hidden) {
			boolean held = false, kept = false;
			for (String id : r.tabIds()) {
				if (current.contains(id)) held = true;
				if (next.contains(id)) kept = true;
			}
			if (held && !kept) out.add(r);
		}
		return out;
	}

	/** Tabs among {@code current} whose deletion (moving to {@code next}) would orphan hidden messages. */
	public static Set<String> tabsThatWouldOrphan(Set<String> current, Set<String> next) {
		Set<String> out = new LinkedHashSet<>();
		for (MessageRecord r : orphansOf(SessionMirror.get(), current, next)) for (String id : r.tabIds()) if (current.contains(id)) out.add(id);
		return out;
	}

	private static boolean aligned(ArrayDeque<MessageRecord> records, List<GuiMessage> all) {
		int n = all.size();
		if (records.size() != n) return false;
		var it = records.iterator();
		for (int i = n - 1; i >= 0; i--) if (it.next().message() != all.get(i)) return false;
		return true;
	}

	// ---------------------------------------------------------------- commit

	/**
	 * Commits {@code plan} (computed in this same synchronous section). Returns committed=false with the reason if the
	 * file step failed; every live reference is then exactly as before.
	 */
	public static CommitResult commit(Plan plan, ConfigSwap config, FileStep file) {
		SessionMirror mirror = SessionMirror.get();
		ChatComponent chat = Minecraft.getInstance().gui.getChat();
		ChatComponentAccessor access = (ChatComponentAccessor) chat;
		List<GuiMessage> oldAll = access.cpextended$getAllMessages();
		ArrayDeque<MessageRecord> oldRecords = mirror.records;
		ArrayDeque<MessageRecord> oldHidden = mirror.hidden;
		Map<String, TabState> oldTabs = mirror.tabs;
		String failure;
		if (devBeforeSwapProbe != null && DevHooks.isDev()) devBeforeSwapProbe.run();
		try {
			// 6a: reference swaps, no allocation
			access.cpextended$setAllMessages(plan.newAll);
			mirror.records = plan.newRecords;
			mirror.hidden = plan.newHidden;
			mirror.tabs = plan.newTabs;
			for (MessageRecord r : plan.orphans) r.assign(false, List.of());
			config.swapIn();
			if (devCommitProbe != null && DevHooks.isDev()) devCommitProbe.run();
			// 6b: atomic replace
			failure = file.replace();
		} catch (RuntimeException e) {
			failure = "unexpected error during the commit: " + e;
		}
		if (failure != null) {
			// 6c: previous references back, no file write
			access.cpextended$setAllMessages(oldAll);
			mirror.records = oldRecords;
			mirror.hidden = oldHidden;
			mirror.tabs = oldTabs;
			for (int i = 0; i < plan.orphans.size(); i++) plan.orphans.get(i).assign(true, plan.orphanOldTabIds.get(i));
			config.swapBack();
			Diagnostics.event("orphan_restore", "committed", false, "reason", failure);
			return new CommitResult(false, failure, false);
		}
		if (devAfterReplaceProbe != null && DevHooks.isDev()) devAfterReplaceProbe.run();
		// 7: derived state
		for (MessageRecord r : plan.prunedVisible) mirror.detach(r);
		boolean dirty = !rebuildLines(chat);
		if (dirty) {
			linesDirty = true;
			rebuildAttempts = 0;
			nextRebuildTick = Minecraft.getInstance().gui.getGuiTicks() + 1;
		}
		Diagnostics.event("orphan_restore", "committed", true, "restored", plan.restoredCount(), "pruned", plan.prunedCount(), "linesDirty", dirty);
		CpExtended.LOGGER.info("Orphan restoration: {} hidden message(s) from {} moved back to the main chat; {} older visible message(s) pruned by the history limit",
				plan.restoredCount(), plan.tabsWithOrphans, plan.prunedCount());
		return new CommitResult(true, plan.restoredCount() + " hidden message(s) moved back to the main chat", dirty);
	}

	private static boolean rebuildLines(ChatComponent chat) {
		try {
			if (devRescaleFailures > 0 && DevHooks.isDev()) {
				devRescaleFailures--;
				throw new IllegalStateException("dev: simulated rescale failure");
			}
			chat.rescaleChat();
			return true;
		} catch (RuntimeException e) {
			Warnings.report("orphan_restore:rescale:" + e.getClass().getName(), Warnings.Toast.NONE, "Chat lines couldn't be rebuilt after orphan restoration; retrying", e);
			return false;
		}
	}

	/** Client tick: retries a failed line rebuild while no chat screen is open (Chat Patches search filters lines there). */
	public static void tick(Minecraft mc) {
		boolean chatOpen = mc.screen instanceof ChatScreen;
		boolean chatJustClosed = chatWasOpen && !chatOpen;
		chatWasOpen = chatOpen;
		if (!linesDirty || chatOpen) return;
		int now = mc.gui.getGuiTicks();
		boolean scheduled = rebuildAttempts < 3 && now >= nextRebuildTick;
		if (!scheduled && !(rebuildAttempts >= 3 && chatJustClosed)) return;
		if (rebuildLines(mc.gui.getChat())) {
			linesDirty = false;
			rebuildAttempts = 0;
			Diagnostics.event("orphan_restore_rebuild", "ok", true);
			return;
		}
		rebuildAttempts++;
		nextRebuildTick = now + (rebuildAttempts == 1 ? 5 : 20);
		if (rebuildAttempts == 3) {
			Warnings.report("orphan_restore:lines_dirty", Warnings.Toast.LINES_DIRTY,
					"Chat lines still couldn't be rebuilt after orphan restoration; the next chat close retries", null);
		}
	}

	public static boolean linesDirty() { return linesDirty; }

	// ---------------------------------------------------------------- development only

	/** Development builds only: make the next {@code n} line rebuilds throw (V-20o). */
	public static void devSetRescaleFailures(int n) { DevHooks.requireDev(); devRescaleFailures = n; }
	/** Development builds only: runs between the reference swaps and the file step (V-20l(4)(6), V-20p). */
	public static void devSetCommitProbe(@Nullable Runnable r) { DevHooks.requireDev(); devCommitProbe = r; }
	/** Development builds only: runs right before the first reference swap (process-kill points, V-20l(6)). */
	public static void devSetBeforeSwapProbe(@Nullable Runnable r) { DevHooks.requireDev(); devBeforeSwapProbe = r; }
	/** Development builds only: runs right after a successful file replacement, before the derived-state step. */
	public static void devSetAfterReplaceProbe(@Nullable Runnable r) { DevHooks.requireDev(); devAfterReplaceProbe = r; }
	/** Development builds only: report the safe point as failed (V-20h, where a real condition can't be produced). */
	public static void devSetForceBusy(boolean v) { DevHooks.requireDev(); devForceBusy = v; }
	/** Development builds only: clear the dirty-lines state between scenarios. */
	public static void devResetLines() { DevHooks.requireDev(); linesDirty = false; rebuildAttempts = 0; }
}
