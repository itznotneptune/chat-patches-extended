// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ConfigParser;
import io.github.itznotneptune.cpextended.config.ConfigStorage;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.OrphanRestore;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.gui.DeleteWindowScreen;
import io.github.itznotneptune.cpextended.gui.TabScreen;
import io.github.itznotneptune.cpextended.gui.WindowScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import obro1961.chatpatches.ChatPatches;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RC.2 verification: the P14 last-good backup amendment (docs/rc2-p14-backup.md) and the S2, S3, M1-M4 GUI fixes.
 * Every file generation is identified by content (SHA-256) and labelled, so each result records exactly which versions
 * of {@code cpextended.json} and {@code cpextended.json.bak} survived.
 */
final class Rc2Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase7aTests q;
	private final Phase4Tests p4;
	private final Map<String, String> labels = new LinkedHashMap<>();

	Rc2Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.q = new Phase7aTests(t);
		this.p4 = new Phase4Tests(t);
	}

	// =================================================================== helpers

	ConfigManager mgr() { return ConfigManager.get(); }

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	static Path cfg() { return Phase2Tests.cfg(); }
	static Path bak() { return Phase2Tests.bak(); }
	static byte[] bytes(Path p) { return Phase2Tests.bytes(p); }

	void restart() { ctx.runOnClient(c -> mgr().devSimulateRestart()); }

	ConfigManager.EditOutcome edit(ConfigEdit e) { return onClient(() -> mgr().edit(e)); }

	static String sha(byte[] b) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Names a file generation by its content. */
	byte[] name(String label, byte[] b) {
		labels.put(sha(b), label);
		return b;
	}

	String label(byte[] b) {
		if (b == null) return "absent";
		String l = labels.get(sha(b));
		if (l != null) return l;
		return "unlabelled:" + sha(b).substring(0, 10) + (ConfigParser.parse(b).status() == ConfigParser.Status.OK ? "" : ":invalid");
	}

	/** Which generations are on disk now, plus leftover staging files. */
	Map<String, Object> files() {
		return row("active", label(bytes(cfg())), "bak", label(bytes(bak())), "tempFiles", temps().size());
	}

	List<Path> temps() {
		try {
			return q.tempFiles();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	void reset() {
		ctx.runOnClient(c -> {
			ConfigStorage st = mgr().storage();
			st.devSetBackupFailures(false, false, false);
			st.devSetFailActiveReplace(false);
			st.devSetNoAtomicMove(false);
			st.devSetTransientFailures(0);
			st.devSetRenameFailures(0, false, false);
			st.devSetFailTempWrite(false);
			st.devResetAtomicProbe();
			OrphanRestore.devSetForceBusy(false);
		});
	}

	/** A fresh active configuration with no backup: {@code text} written, then a startup. */
	void fresh(String text) {
		Phase2Tests.cleanConfigFiles();
		Phase2Tests.write(cfg(), text);
		restart();
	}

	/** Dump of all session state except the configuration file (B18). */
	static boolean sameExceptConfig(Phase7aTests.Dump a, Phase7aTests.Dump b) {
		return Phase7aTests.identical(a.all(), b.all()) && a.allListObject() == b.allListObject() && a.tabs().equals(b.tabs()) && a.hidden().equals(b.hidden())
				&& a.hiddenFlags().equals(b.hiddenFlags()) && a.unread().equals(b.unread()) && a.records().equals(b.records()) && a.chatLog() == b.chatLog();
	}

	boolean inMainChat(String token) { return q.mainTokens().contains(token); }

	// ---------------------------------------------------------------- GUI helpers

	/** A widget's tooltip text; the holder field is found by type, so this also works in production (intermediary names). */
	static String tooltip(AbstractWidget w) {
		try {
			for (Class<?> c = w.getClass(); c != null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (f.getType() == WidgetTooltipHolder.class) {
						f.setAccessible(true);
						Tooltip tip = ((WidgetTooltipHolder) f.get(w)).get();
						if (tip == null) return null;
						StringBuilder sb = new StringBuilder();
						for (FormattedCharSequence s : tip.toCharSequence(Minecraft.getInstance())) {
							if (!sb.isEmpty()) sb.append(' ');
							s.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
						}
						return sb.toString();
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return null;
	}

	String tooltipOf(String text) {
		return onClient(() -> {
			AbstractWidget w = widgetNow(text);
			return w == null ? null : tooltip(w);
		});
	}

	static AbstractWidget widgetNow(String text) {
		Screen s = Minecraft.getInstance().screen;
		if (s == null) return null;
		for (var ch : s.children()) if (ch instanceof AbstractWidget w && w.getMessage().getString().contains(text)) return w;
		return null;
	}

	/** Text shown by a confirmation or alert screen (title and message, as narrated). */
	String dialogText() {
		return onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			return s == null ? null : s.getNarrationMessage().getString();
		});
	}

	String statusText() {
		return onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			if (s instanceof ConfigScreen c) return c.status().getString();
			if (s instanceof WindowScreen w) return w.status().getString();
			if (s instanceof TabScreen ts) return ts.status().getString();
			if (s instanceof DeleteWindowScreen d) return d.status().getString();
			return null;
		});
	}

	/** Every button whose label doesn't fit (vanilla draws labels with a 2-pixel margin each side). */
	List<String> clippedLabels() {
		return onClient(() -> {
			Minecraft mc = Minecraft.getInstance();
			List<String> out = new ArrayList<>();
			if (mc.screen == null) return out;
			for (var ch : mc.screen.children()) {
				if (ch instanceof AbstractWidget w && !(w instanceof EditBox) && w.visible) {
					int need = mc.font.width(w.getMessage());
					if (need > w.getWidth() - 4) out.add(w.getMessage().getString() + " (" + need + " > " + (w.getWidth() - 4) + ")");
				}
			}
			return out;
		});
	}

	/** Pairs of visible widgets whose rectangles intersect. */
	List<String> overlaps() {
		return onClient(() -> {
			List<AbstractWidget> ws = new ArrayList<>();
			Screen s = Minecraft.getInstance().screen;
			if (s == null) return List.of();
			for (var ch : s.children()) if (ch instanceof AbstractWidget w && w.visible) ws.add(w);
			List<String> out = new ArrayList<>();
			for (int i = 0; i < ws.size(); i++) for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i), b = ws.get(j);
				if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight())
					out.add(a.getMessage().getString() + " / " + b.getMessage().getString());
			}
			return out;
		});
	}

	/** A screenshot without warning toasts (left over from simulated restarts) or a hover tooltip over the screen. */
	void shot(String name) {
		if (!name.contains("tooltip")) new Phase3Tests(t).cursor(1, 1); // away from every control
		ctx.runOnClient(c -> c.getToastManager().clear());
		t.waitT(2);
		ctx.takeScreenshot(name);
	}

	void setScale(int scale) {
		ctx.runOnClient(c -> { c.options.guiScale().set(scale); c.resizeDisplay(); });
		t.waitT(3);
	}

	int[] screenSize() { return onClient(() -> new int[]{Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight()}); }

	// =================================================================== mode rc2_backup

	static final String CONFIG = Phase7aTests.CONFIG;

	void backup() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			q.setup(sp);
			b1Creation();
			b3Repeated(sp);
			b7Preserve();
			b8External();
			b9BackupWriteFailures();
			b10ActiveFailure();
			b16Fallback();
			b6Candidates();
			b17NoUnsafePromotion();
			b5CorruptRecoveryGui();
			b12Q36(sp);
			b13Q36Failure(sp);
			b14RestoreOrphaning(sp);
			b15RestoreRefused(sp);
		} finally {
			reset();
		}
		ev.data("generations", "labels", labels);
	}

	/** B1, B2: creation, and the first save with no previous valid configuration. */
	void b1Creation() {
		Phase2Tests.cleanConfigFiles();
		restart();
		byte[] created = name("created", bytes(cfg()));
		boolean valid = created != null && ConfigParser.parse(created).status() == ConfigParser.Status.OK;
		Map<String, Object> afterCreate = files();
		ConfigStorage.BackupOutcome createBackup = onClient(() -> mgr().lastBackup());
		boolean b1 = valid && "absent".equals(afterCreate.get("bak")) && createBackup == ConfigStorage.BackupOutcome.UNCHANGED;
		ev.result("RC2.B1.initialCreation", b1, "files", afterCreate, "backupOutcome", createBackup, "state", onClient(() -> mgr().state()));

		// B2: an unusable file at startup, no backup; Replace file writes a first valid version; the backup is still absent
		Phase2Tests.write(cfg(), "{ broken");
		name("broken", bytes(cfg()));
		restart();
		ConfigManager.State st = onClient(() -> mgr().state());
		ConfigStorage.BackupState bs = onClient(() -> mgr().storage().inspectBackup().state());
		ConfigManager.ActionResult replaced = onClient(() -> mgr().replaceWithCurrentSettings(true));
		byte[] first = name("firstValid", bytes(cfg()));
		Map<String, Object> afterReplace = files();
		ConfigStorage.BackupOutcome replaceBackup = onClient(() -> mgr().lastBackup());
		// first ordinary save after it: the first valid version becomes the backup
		ConfigManager.EditOutcome e = edit(new ConfigEdit.SetHidingEnabled(false));
		name("afterFirstEdit", bytes(cfg()));
		Map<String, Object> afterEdit = files();
		boolean b2 = st == ConfigManager.State.NO_SAVE && bs == ConfigStorage.BackupState.MISSING && replaced.ok() && "absent".equals(afterReplace.get("bak"))
				&& e.applied() && e.save() == ConfigManager.SaveOutcome.SAVED && "firstValid".equals(afterEdit.get("bak")) && "afterFirstEdit".equals(afterEdit.get("active"))
				&& first != null;
		ev.result("RC2.B2.firstSaveWithoutPreviousValid", b2, "startupState", st, "backupState", bs, "replace", replaced, "filesAfterReplace", afterReplace,
				"replaceBackupOutcome", replaceBackup, "filesAfterFirstEdit", afterEdit, "corruptCopies", Phase2Tests.corruptCopies().size(),
				"note", "the broken file is never promoted; the first valid version becomes the backup at the next ordinary save");
	}

	/** B3, B4, B18: repeated atomic saves rotate exactly one generation; no session state changes. */
	void b3Repeated(TestSingleplayerContext sp) {
		fresh(CONFIG);
		remember("C0", bytes(cfg()));
		q.visible(sp, "visible b18 cpx#V-b18");
		q.hidden("hidden b18 cpx#X-b18", "qx");
		Phase7aTests.Dump d0 = q.dump();
		List<Map<String, Object>> steps = new ArrayList<>();
		boolean ok = true;
		String prev = "C0";
		for (int i = 1; i <= 3; i++) {
			ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("qv", "rename " + i));
			String cur = "C" + i;
			remember(cur, bytes(cfg()));
			Map<String, Object> f = files();
			ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
			boolean stepOk = e.save() == ConfigManager.SaveOutcome.SAVED && cur.equals(f.get("active")) && prev.equals(f.get("bak"))
					&& bo == ConfigStorage.BackupOutcome.PROMOTED && Integer.valueOf(0).equals(f.get("tempFiles"));
			steps.add(row("save", i, "files", f, "backupOutcome", bo, "ok", stepOk));
			ok &= stepOk;
			prev = cur;
		}
		// a save that writes identical bytes keeps the older backup
		ConfigManager.EditOutcome same = edit(new ConfigEdit.RenameTab("qv", "rename 3"));
		Map<String, Object> afterSame = files();
		boolean sameOk = "C3".equals(afterSame.get("active")) && "C2".equals(afterSame.get("bak"));
		boolean bakIsExactPrevious = Arrays.equals(bytes(bak()), labelsBytes("C2"));
		Phase7aTests.Dump d1 = q.dump();
		boolean untouched = sameExceptConfig(d0, d1);
		ev.result("RC2.B3.repeatedAtomicSaves", ok && sameOk, "steps", steps, "identicalRewrite", row("edit", same.edit().outcome(), "save", same.save(), "files", afterSame));
		ev.result("RC2.B4.backupIsPreviousCommitted", ok && bakIsExactPrevious, "bakBytesEqualC2", bakIsExactPrevious,
				"note", "the backup is the previous active file byte for byte (not re-serialized from memory)");
		ev.result("RC2.B18.noSessionMutation", untouched, "diff", d0.diff(d1),
				"note", "main chat (identity), tab histories, hidden list and holders, unread, mirror and Chat Patches' log count compared across 4 saves with backup rotation");
		t.clearHiddenFixtures();
	}

	private final Map<String, byte[]> byLabel = new LinkedHashMap<>();

	byte[] labelsBytes(String label) { return byLabel.get(label); }

	/** B7: unknown keys, an untouched invalid filter and formatting survive promotion and restore, byte for byte. */
	void b7Preserve() {
		String hand = "{\n  \"schemaVersion\": 1,\n  \"futureTop\": {\"keep\": [1, 2, 3]},\n  \"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "  \"windows\": [\n    {\"id\": \"h-w\", \"futureWindowKey\": \"x\", \"tabs\": [\n"
				+ "      {\"id\": \"h-a\", \"name\": \"Hand\",   \"futureTabKey\": true, \"include\": [{\"mode\": \"regex\", \"pattern\": \"([unclosed\"}, {\"mode\": \"literal\", \"pattern\": \"cpx#HAND\"}]},\n"
				+ "      {\"id\": \"h-b\", \"name\": \"Other\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#OTHER\"}]}\n"
				+ "    ]}\n  ]\n}\n";
		fresh(hand);
		byte[] h = remember("H", bytes(cfg()));
		Map<String, List<String>> errorsBefore = onClient(() -> io.github.itznotneptune.cpextended.core.Router.get().config().configErrorSummary());
		ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("h-b", "Other renamed"));
		remember("H+1", bytes(cfg()));
		boolean bakIsHand = Arrays.equals(bytes(bak()), h);
		JsonObject activeJson = Phase2Tests.json(cfg());
		boolean activeKeeps = activeJson.has("futureTop") && Phase2Tests.tabOf(activeJson, "h-w", "h-a").has("futureTabKey")
				&& "regex".equals(Phase2Tests.filterOf(activeJson, "h-a", "include", 0).get("mode").getAsString());
		// corruption, then Restore from backup: the hand-written bytes come back exactly
		Phase2Tests.write(cfg(), "{\"schemaVersion\": 1, \"windows\": [");
		remember("broken2", bytes(cfg()));
		restart();
		boolean onBackup = onClient(() -> mgr().runningOnBackup());
		ConfigManager.ActionResult r = onClient(() -> mgr().restoreFromBackup(true));
		boolean restoredExact = Arrays.equals(bytes(cfg()), h);
		restart();
		Map<String, List<String>> errorsAfter = onClient(() -> io.github.itznotneptune.cpextended.core.Router.get().config().configErrorSummary());
		boolean ok = e.save() == ConfigManager.SaveOutcome.SAVED && bakIsHand && activeKeeps && onBackup && r.ok() && restoredExact && errorsBefore.equals(errorsAfter)
				&& onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		ev.result("RC2.B7.rawPreservation", ok, "bakIsHandWrittenBytes", bakIsHand, "activeKeepsUnknownKeysAndInvalidFilter", activeKeeps, "runningOnBackup", onBackup,
				"restore", r, "restoredBytesExact", restoredExact, "filterErrorsBefore", errorsBefore, "filterErrorsAfter", errorsAfter, "files", files());
	}

	byte[] remember(String label, byte[] b) {
		byLabel.put(label, b);
		return name(label, b);
	}

	/** B8: an applied hand edit becomes the backup; an unreconciled one (divergence) never does. */
	void b8External() {
		fresh(CONFIG);
		remember("E0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "e1"));
		remember("E1", bytes(cfg()));
		// (a) a valid hand edit, applied by the next save, then promoted
		JsonObject j = Phase2Tests.json(cfg());
		Phase2Tests.tabOf(j, "q-b", "qm").addProperty("name", "edited by hand");
		Phase2Tests.writeJson(cfg(), j);
		remember("Hand", bytes(cfg()));
		ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("qv", "e2"));
		remember("E2", bytes(cfg()));
		Map<String, Object> a = files();
		boolean handKept = "edited by hand".equals(Phase2Tests.tabOf(Phase2Tests.json(cfg()), "q-b", "qm").get("name").getAsString());
		boolean aOk = e.save() == ConfigManager.SaveOutcome.SAVED && "Hand".equals(a.get("bak")) && "E2".equals(a.get("active")) && handKept;
		// (b) an unparseable hand edit: divergence lock, nothing written, backup untouched; Overwrite never promotes it
		Phase2Tests.write(cfg(), "{ \"schemaVersion\": 1, broken by hand");
		remember("HandBroken", bytes(cfg()));
		ConfigManager.EditOutcome blocked = edit(new ConfigEdit.RenameTab("qv", "e3"));
		Map<String, Object> locked = files();
		ConfigManager.ActionResult over = onClient(() -> mgr().overwriteFile(true));
		remember("Overwritten", bytes(cfg()));
		Map<String, Object> afterOver = files();
		ConfigStorage.BackupOutcome overBackup = onClient(() -> mgr().lastBackup());
		boolean bOk = blocked.save() == ConfigManager.SaveOutcome.BLOCKED_DIVERGENCE && "HandBroken".equals(locked.get("active")) && "Hand".equals(locked.get("bak"))
				&& over.ok() && "Overwritten".equals(afterOver.get("active")) && "Hand".equals(afterOver.get("bak"));
		ev.result("RC2.B8.handEditsAndDivergence", aOk && bOk, "appliedHandEdit", row("files", a, "handEditKept", handKept),
				"divergence", row("save", blocked.save(), "filesWhileLocked", locked, "overwrite", over, "filesAfterOverwrite", afterOver, "backupOutcome", overBackup),
				"note", "a hand edit Extended applied is reconciled and may become the backup; an unapplied (diverged) one never does");
	}

	/** B9: staging or finalizing the backup fails: the save succeeds, the older backup stays, no leftovers. */
	void b9BackupWriteFailures() {
		fresh(CONFIG);
		remember("F0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "f1"));
		remember("F1", bytes(cfg()));
		List<Map<String, Object>> cases = new ArrayList<>();
		boolean ok = true;
		String[] kinds = {"stage", "finalize"};
		String active = "F1", backup = "F0";
		for (int i = 0; i < kinds.length; i++) {
			String kind = kinds[i];
			ctx.runOnClient(c -> mgr().storage().devSetBackupFailures(kind.equals("stage"), kind.equals("finalize"), false));
			ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("qv", "f-" + kind));
			String next = "F-" + kind;
			remember(next, bytes(cfg()));
			ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
			Map<String, Object> f = files();
			ctx.runOnClient(c -> mgr().storage().devSetBackupFailures(false, false, false));
			boolean caseOk = e.save() == ConfigManager.SaveOutcome.SAVED && next.equals(f.get("active")) && backup.equals(f.get("bak"))
					&& bo == ConfigStorage.BackupOutcome.KEPT_BACKUP_WRITE_FAILED && Integer.valueOf(0).equals(f.get("tempFiles"));
			cases.add(row("failure", kind, "save", e.save(), "backupOutcome", bo, "files", f, "previousActive", active, "ok", caseOk));
			ok &= caseOk;
			active = next;
		}
		// the next save without failures promotes again
		edit(new ConfigEdit.RenameTab("qv", "f-after"));
		remember("F-after", bytes(cfg()));
		Map<String, Object> after = files();
		boolean recovers = "F-finalize".equals(after.get("bak"));
		ev.result("RC2.B9.backupWriteFailure", ok && recovers, "cases", cases, "nextSave", after,
				"note", "backup staging and the post-commit backup rename never fail or roll back the save; the backup stays one generation older (F0)");
	}

	/** B10: the active replacement fails: both files unchanged, staged backup removed, changes stay pending. */
	void b10ActiveFailure() {
		fresh(CONFIG);
		remember("G0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "g1"));
		remember("G1", bytes(cfg()));
		ctx.runOnClient(c -> mgr().storage().devSetFailActiveReplace(true));
		ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("qv", "g2"));
		Map<String, Object> f = files();
		boolean dirty = onClient(() -> mgr().dirty());
		ctx.runOnClient(c -> mgr().storage().devSetFailActiveReplace(false));
		ConfigManager.SaveOutcome retried = onClient(() -> mgr().attemptSave("test"));
		remember("G2", bytes(cfg()));
		Map<String, Object> after = files();
		boolean ok = e.save() == ConfigManager.SaveOutcome.NOT_SAVED && "G1".equals(f.get("active")) && "G0".equals(f.get("bak")) && Integer.valueOf(0).equals(f.get("tempFiles"))
				&& dirty && retried == ConfigManager.SaveOutcome.SAVED && "G2".equals(after.get("active")) && "G1".equals(after.get("bak"));
		ev.result("RC2.B10.activeReplaceFailure", ok, "save", e.save(), "filesAfterFailure", f, "dirty", dirty, "retry", retried, "filesAfterRetry", after,
				"note", "the rename of the new version failed twice (one retry): both files unchanged; the staged backup was deleted");
	}

	/** B16: atomic replacement unsupported: the backup is written first and protects the direct write. */
	void b16Fallback() {
		fresh(CONFIG);
		remember("K0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "k1"));
		remember("K1", bytes(cfg()));
		ctx.runOnClient(c -> mgr().storage().devSetNoAtomicMove(true));
		ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("qv", "k2"));
		remember("K2", bytes(cfg()));
		Map<String, Object> f = files();
		ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
		// the backup write fails: the direct write must not happen
		ctx.runOnClient(c -> mgr().storage().devSetBackupFailures(false, false, true));
		ConfigManager.EditOutcome e2 = edit(new ConfigEdit.RenameTab("qv", "k3"));
		Map<String, Object> f2 = files();
		boolean dirty = onClient(() -> mgr().dirty());
		ctx.runOnClient(c -> mgr().storage().devSetBackupFailures(false, false, false));
		ConfigManager.SaveOutcome retried = onClient(() -> mgr().attemptSave("test"));
		remember("K3", bytes(cfg()));
		Map<String, Object> f3 = files();
		ctx.runOnClient(c -> mgr().storage().devSetNoAtomicMove(false));
		boolean ok = e.save() == ConfigManager.SaveOutcome.SAVED && "K2".equals(f.get("active")) && "K1".equals(f.get("bak")) && bo == ConfigStorage.BackupOutcome.PROMOTED
				&& e2.save() == ConfigManager.SaveOutcome.NOT_SAVED && "K2".equals(f2.get("active")) && "K1".equals(f2.get("bak")) && dirty
				&& retried == ConfigManager.SaveOutcome.SAVED && "K3".equals(f3.get("active")) && "K2".equals(f3.get("bak"));
		ev.result("RC2.B16.nonAtomicFallback", ok, "fallbackSave", row("save", e.save(), "files", f, "backupOutcome", bo),
				"backupWriteFails", row("save", e2.save(), "files", f2, "dirty", dirty), "retry", row("save", retried, "files", f3),
				"note", "atomic move simulated as unsupported (development hook); a failed backup write aborts the direct write");
	}

	/** B6: missing, damaged, newer-schema and unreadable backups are refused with a reason; nothing changes. */
	void b6Candidates() {
		List<Map<String, Object>> cases = new ArrayList<>();
		boolean ok = true;
		String newer = "{\"schemaVersion\": 99, \"windows\": []}";
		String[][] variants = {{"missing", null}, {"unparseable", "{\"schemaVersion\": 1, \"windows\": [ {"}, {"newerSchema", newer}, {"unreadable", CONFIG}};
		for (String[] v : variants) {
			Phase2Tests.cleanConfigFiles();
			if (v[1] != null) Phase2Tests.write(bak(), v[1]);
			if (v[0].equals("unreadable")) setPermissions(bak(), "---------");
			Phase2Tests.write(cfg(), "{ damaged");
			byte[] active = bytes(cfg());
			restart();
			ConfigManager.State st = onClient(() -> mgr().state());
			boolean onBackup = onClient(() -> mgr().runningOnBackup());
			ConfigStorage.BackupState bs = onClient(() -> mgr().storage().inspectBackup().state());
			ConfigManager.ActionResult r = onClient(() -> mgr().restoreFromBackup(true));
			// the GUI offers the button inactive and says why
			p4.open(() -> new ConfigScreen(null));
			Boolean active2 = onClient(() -> { AbstractWidget w = widgetNow("Restore from backup"); return w == null ? null : w.active; });
			String tip = tooltipOf("Restore from backup");
			ctx.setScreen(() -> null);
			if (v[0].equals("unreadable")) setPermissions(bak(), "rw-r--r--");
			boolean unchanged = Arrays.equals(active, bytes(cfg())) && (v[1] == null ? bytes(bak()) == null : Arrays.equals(v[1].getBytes(StandardCharsets.UTF_8), bytes(bak())));
			ConfigStorage.BackupState expected = switch (v[0]) {
				case "missing" -> ConfigStorage.BackupState.MISSING;
				case "unparseable" -> ConfigStorage.BackupState.UNPARSEABLE;
				case "newerSchema" -> ConfigStorage.BackupState.UNSUPPORTED_SCHEMA;
				default -> ConfigStorage.BackupState.UNREADABLE;
			};
			boolean caseOk = st == ConfigManager.State.NO_SAVE && !onBackup && bs == expected && !r.ok() && Boolean.FALSE.equals(active2) && tip != null
					&& tip.replace(" ", "").equals(r.message().replace(" ", "")) && unchanged && onClient(() -> mgr().state()) == ConfigManager.State.NO_SAVE;
			cases.add(row("backup", v[0], "startupState", st, "runningOnBackup", onBackup, "inspected", bs, "restore", r, "buttonActive", active2, "tooltip", tip,
					"filesUnchanged", unchanged, "ok", caseOk));
			ok &= caseOk;
		}
		ev.result("RC2.B6.invalidBackupCandidates", ok, "cases", cases,
				"note", "unreadable simulated with file permissions 000 (macOS); every refusal leaves both files and the no-save state unchanged");
	}

	static void setPermissions(Path p, String perms) {
		try {
			Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** B17: corrupt or newer-schema active files are never promoted, even by an explicit replacement. */
	void b17NoUnsafePromotion() {
		List<Map<String, Object>> cases = new ArrayList<>();
		boolean ok = true;
		for (String kind : new String[]{"corrupt", "newerSchema"}) {
			fresh(CONFIG);
			remember("P0-" + kind, bytes(cfg()));
			edit(new ConfigEdit.RenameTab("qv", "p1 " + kind));
			remember("P1-" + kind, bytes(cfg())); // backup is P0
			String bad = kind.equals("corrupt") ? "{ corrupt" : "{\"schemaVersion\": 99, \"futureTop\": 1, \"windows\": []}";
			Phase2Tests.write(cfg(), bad);
			remember("Bad-" + kind, bytes(cfg()));
			restart();
			ConfigManager.ActionResult r = onClient(() -> mgr().replaceWithCurrentSettings(true));
			remember("Replaced-" + kind, bytes(cfg()));
			Map<String, Object> f = files();
			ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
			boolean caseOk = r.ok() && ("Replaced-" + kind).equals(f.get("active")) && ("P0-" + kind).equals(f.get("bak"));
			cases.add(row("activeFile", kind, "replace", r, "files", f, "backupOutcome", bo, "ok", caseOk));
			ok &= caseOk;
		}
		ev.result("RC2.B17.noUnsafePromotion", ok, "cases", cases, "seeAlso", "RC2.B8 (divergence overwrite), RC2.B2 (broken file, no backup)");
	}

	/** B5: corruption, then recovery through the GUI from a backup the game made itself. */
	void b5CorruptRecoveryGui() {
		fresh(CONFIG);
		remember("R0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "r1"));
		remember("R1", bytes(cfg())); // backup is R0
		Phase2Tests.write(cfg(), "{\"schemaVersion\": 1, \"windows\": [ {\"id\": \"trunc");
		remember("Torn", bytes(cfg()));
		restart();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", onClient(() -> mgr().state()));
		m.put("runningOnBackup", onClient(() -> mgr().runningOnBackup()));
		p4.open(() -> new ConfigScreen(null));
		m.put("restoreActive", onClient(() -> { AbstractWidget w = widgetNow("Restore from backup"); return w != null && w.active; }));
		m.put("restoreTooltip", tooltipOf("Restore from backup"));
		shot("rc2-recovery-screen");
		p4.press("Restore from backup");
		m.put("confirmText", dialogText());
		shot("rc2-restore-confirm");
		p4.press("Yes");
		m.put("status", statusText());
		m.put("stateAfter", onClient(() -> mgr().state()));
		m.put("filesAfterRestore", files());
		ctx.setScreen(() -> null);
		edit(new ConfigEdit.RenameTab("qv", "r2"));
		remember("R2", bytes(cfg()));
		m.put("filesAfterNextSave", files());
		String corrupt = Phase2Tests.corruptCopies().isEmpty() ? "" : Phase2Tests.corruptCopies().get(Phase2Tests.corruptCopies().size() - 1).getFileName().toString();
		boolean ok = m.get("state") == ConfigManager.State.NO_SAVE && Boolean.TRUE.equals(m.get("runningOnBackup")) && Boolean.TRUE.equals(m.get("restoreActive"))
				&& String.valueOf(m.get("restoreTooltip")).contains("before the last change") && String.valueOf(m.get("confirmText")).contains(corrupt) && !corrupt.isEmpty()
				&& m.get("stateAfter") == ConfigManager.State.NORMAL && "R0".equals(((Map<?, ?>) m.get("filesAfterRestore")).get("active"))
				&& "R0".equals(((Map<?, ?>) m.get("filesAfterRestore")).get("bak")) && "R2".equals(((Map<?, ?>) m.get("filesAfterNextSave")).get("active"))
				&& "R0".equals(((Map<?, ?>) m.get("filesAfterNextSave")).get("bak"));
		ev.result("RC2.B5.corruptionRecoveryGui", ok, "observed", m, "corruptCopy", corrupt,
				"note", "real clicks; the restored version (R0) is the active file and the backup; the next save keeps R0 as backup (it equals the replaced version)");
	}

	/** B12: a confirmed orphan-producing commit (Q36) rotates the backup after the commit. */
	void b12Q36(TestSingleplayerContext sp) {
		q.reset(sp);
		Phase2Tests.cleanConfigFiles();
		q.setup(sp);
		remember("Q0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "q1"));
		remember("Q1", bytes(cfg()));
		q.hidden("hidden b12 cpx#X-b12", "qx");
		q.visible(sp, "visible b12 cpx#V-b12");
		ConfigManager.OrphanCheck c = q.check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome o = q.confirm(c, new ConfigEdit.DeleteTab("qx"));
		remember("Q2", bytes(cfg()));
		Map<String, Object> f = files();
		ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
		boolean ok = o.applied() && "Q2".equals(f.get("active")) && "Q1".equals(f.get("bak")) && bo == ConfigStorage.BackupOutcome.PROMOTED && !q.tabInFile("qx")
				&& inMainChat("cpx#X-b12") && Integer.valueOf(0).equals(f.get("tempFiles"));
		ev.result("RC2.B12.q36CommitRotatesBackup", ok, "disclosed", row("restored", c.restored(), "pruned", c.pruned(), "tabs", c.tabNames()), "applied", o.applied(),
				"files", f, "backupOutcome", bo, "orphanInMainChat", inMainChat("cpx#X-b12"));
	}

	/** B13: Q36 commit failure rolls back with both files unchanged; post-commit backup failures never undo a commit. */
	void b13Q36Failure(TestSingleplayerContext sp) {
		q.reset(sp);
		Phase2Tests.cleanConfigFiles();
		q.setup(sp);
		remember("S0", bytes(cfg()));
		edit(new ConfigEdit.RenameTab("qv", "s1"));
		remember("S1", bytes(cfg()));
		q.hidden("hidden b13 cpx#X-b13", "qx");
		q.visible(sp, "visible b13 cpx#V-b13");
		Phase7aTests.Dump before = q.dump();
		ConfigManager.OrphanCheck c = q.check(new ConfigEdit.DeleteTab("qx"));
		ctx.runOnClient(x -> mgr().storage().devSetRenameFailures(0, true, false));
		ConfigManager.EditOutcome failed = q.confirm(c, new ConfigEdit.DeleteTab("qx"));
		ctx.runOnClient(x -> mgr().storage().devSetRenameFailures(0, false, false));
		Phase7aTests.Dump after = q.dump();
		Map<String, Object> f = files();
		boolean rollback = !failed.applied() && before.same(after) && "S1".equals(f.get("active")) && "S0".equals(f.get("bak")) && Integer.valueOf(0).equals(f.get("tempFiles"));
		ev.result("RC2.B13.q36FailureRollback", rollback, "refusal", failed.edit().reason(), "stateDiff", before.diff(after), "files", f,
				"note", "the commit's atomic rename fails permanently: references swapped back, both files byte-identical, staged backup deleted");
		// commit succeeds but staging, or the post-commit backup rename, fails: commit stands, older backup kept
		List<Map<String, Object>> post = new ArrayList<>();
		boolean postOk = true;
		for (String kind : new String[]{"stage", "finalize"}) {
			q.reset(sp);
			Phase2Tests.cleanConfigFiles();
			q.setup(sp);
			remember("T0-" + kind, bytes(cfg()));
			edit(new ConfigEdit.RenameTab("qv", "t1 " + kind));
			remember("T1-" + kind, bytes(cfg()));
			q.hidden("hidden t " + kind + " cpx#X-t" + kind, "qx");
			ConfigManager.OrphanCheck c2 = q.check(new ConfigEdit.DeleteTab("qx"));
			ctx.runOnClient(x -> mgr().storage().devSetBackupFailures(kind.equals("stage"), kind.equals("finalize"), false));
			ConfigManager.EditOutcome o = q.confirm(c2, new ConfigEdit.DeleteTab("qx"));
			ctx.runOnClient(x -> mgr().storage().devSetBackupFailures(false, false, false));
			remember("T2-" + kind, bytes(cfg()));
			Map<String, Object> g = files();
			ConfigStorage.BackupOutcome bo = onClient(() -> mgr().lastBackup());
			boolean k = o.applied() && ("T2-" + kind).equals(g.get("active")) && ("T0-" + kind).equals(g.get("bak")) && bo == ConfigStorage.BackupOutcome.KEPT_BACKUP_WRITE_FAILED
					&& inMainChat("cpx#X-t" + kind) && !q.tabInFile("qx") && Integer.valueOf(0).equals(g.get("tempFiles"));
			post.add(row("failure", kind, "applied", o.applied(), "files", g, "backupOutcome", bo, "ok", k));
			postOk &= k;
		}
		ev.result("RC2.B13b.q36BackupFailureNeverUndoesCommit", postOk, "cases", post);
	}

	/**
	 * Builds the realistic restore-orphans situation: the backup lacks tab qx, the active file is from a newer version
	 * (read-only on its understood fields, which include qx), and qx holds a hidden message this session.
	 */
	void restoreSituation(TestSingleplayerContext sp, String label) {
		q.reset(sp);
		Phase2Tests.cleanConfigFiles();
		JsonObject j = com.google.gson.JsonParser.parseString(CONFIG).getAsJsonObject();
		for (var w : j.getAsJsonArray("windows")) {
			JsonArray tabs = w.getAsJsonObject().getAsJsonArray("tabs");
			for (int i = tabs.size() - 1; i >= 0; i--) if ("qx".equals(tabs.get(i).getAsJsonObject().get("id").getAsString())) tabs.remove(i);
		}
		Phase2Tests.writeJson(cfg(), j);
		restart();
		remember("W0-" + label, bytes(cfg()));
		// qx added in game: the version without it becomes the backup
		onClient(() -> mgr().editAll(List.of(new ConfigEdit.AddTab("q-a", "qx", "Smoke"), new ConfigEdit.SetTabFlag("qx", "hideFromMainChat", true),
				new ConfigEdit.AddFilter("qx", "include", new FilterSpec(FilterSpec.LITERAL, "cpx#X", false)))));
		remember("W1-" + label, bytes(cfg()));
		JsonObject newer = Phase2Tests.json(cfg());
		newer.addProperty("schemaVersion", 99);
		Phase2Tests.writeJson(cfg(), newer);
		remember("Newer-" + label, bytes(cfg()));
		restart();
		ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 60; c.gui.getChat().clearMessages(false); });
		t.waitT(3);
	}

	/** B14: Restore from backup that would orphan hidden messages: disclosure, "No" changes nothing, "Yes" restores. */
	void b14RestoreOrphaning(TestSingleplayerContext sp) {
		restoreSituation(sp, "b14");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", onClient(() -> mgr().state()));
		m.put("files", files());
		q.hidden("hidden b14 cpx#X-b14", "qx");
		q.visible(sp, "visible b14 cpx#V-b14");
		Phase7aTests.Dump d0 = q.dump();
		byte[] bak0 = bytes(bak());
		// unconfirmed: refused
		ConfigManager.ActionResult plain = onClient(() -> mgr().restoreFromBackup(true));
		boolean plainRefused = !plain.ok() && sameAll(d0, q.dump()) && Arrays.equals(bak0, bytes(bak()));
		// GUI: disclosure, then No
		p4.open(() -> new ConfigScreen(null));
		p4.press("Restore from backup");
		p4.press("Yes");
		String disclosure = dialogText();
		boolean disclosed = onClient(() -> Minecraft.getInstance().screen instanceof ConfirmScreen);
		shot("rc2-restore-orphan-disclosure");
		p4.press("No");
		boolean noChangesNothing = sameAll(d0, q.dump()) && onClient(() -> mgr().state()) == ConfigManager.State.NO_SAVE;
		ctx.setScreen(() -> null);
		// confirmed
		ConfigManager.OrphanCheck c = onClient(() -> mgr().checkRestoreFromBackup());
		ConfigManager.ActionResult r = onClient(() -> mgr().restoreFromBackupRestoringOrphans(c));
		Map<String, Object> f = files();
		boolean ok = m.get("state") == ConfigManager.State.NO_SAVE && plainRefused && disclosed && disclosure != null && disclosure.contains("\"Smoke\"") && !disclosure.contains("qx")
				&& noChangesNothing && r.ok() && "W0-b14".equals(f.get("active")) && "W0-b14".equals(f.get("bak")) && inMainChat("cpx#X-b14")
				&& onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		ev.result("RC2.B14.restoreThatOrphans", ok, "before", m, "unconfirmedRestore", plain, "disclosure", disclosure, "noChangesNothing", noChangesNothing,
				"disclosed", row("restored", c.restored(), "pruned", c.pruned(), "tabs", c.tabNames()), "confirmedRestore", r, "filesAfter", f, "orphanInMainChat", inMainChat("cpx#X-b14"),
				"note", "active file from a newer version (read-only on understood fields, incl. tab \"Smoke\"); the backup (made by the game) lacks that tab");
	}

	boolean sameAll(Phase7aTests.Dump a, Phase7aTests.Dump b) { return a.same(b); }

	/** B15: the capacity projection fails (an orphan would be pruned at once): the restore is refused, nothing changes. */
	void b15RestoreRefused(TestSingleplayerContext sp) {
		restoreSituation(sp, "b15");
		q.hidden("hidden b15 cpx#X-b15", "qx");
		ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 3);
		for (int i = 0; i < 4; i++) q.visible(sp, "visible b15 " + i + " cpx#V-b15-" + i);
		Phase7aTests.Dump d0 = q.dump();
		byte[] a0 = bytes(cfg()), b0 = bytes(bak());
		ConfigManager.OrphanCheck c = onClient(() -> mgr().checkRestoreFromBackup());
		ConfigManager.ActionResult r = onClient(() -> mgr().restoreFromBackupRestoringOrphans(c));
		p4.open(() -> new ConfigScreen(null));
		p4.press("Restore from backup");
		p4.press("Yes");
		String guiStatus = statusText();
		ctx.setScreen(() -> null);
		boolean ok = c.refused() && !r.ok() && d0.same(q.dump()) && Arrays.equals(a0, bytes(cfg())) && Arrays.equals(b0, bytes(bak()))
				&& onClient(() -> mgr().state()) == ConfigManager.State.NO_SAVE && guiStatus != null && guiStatus.contains("\"Smoke\"");
		ev.result("RC2.B15.restoreRefusedByProjection", ok, "refusal", c.refusal(), "restore", r, "guiStatus", guiStatus, "files", files(),
				"note", "Chat Patches' limit 3, the hidden message older than the 3 kept visible ones: moving it back would lose it at once (Q27)");
		ctx.runOnClient(x -> ChatPatches.config.chatMaxMessages = 60);
	}

	// =================================================================== crash interruption (B11)

	static final Path KILL_RECORD = Path.of("cpx-evidence", "rc2-kill.json");

	/** Expected generations after a halt at each point: active, backup ("A" previous active, "B0" previous backup, "N" new, "torn"). */
	static String[] expected(String point) {
		return switch (point) {
			case "activeTempMid", "afterTemp", "backupStageMid", "afterBackupStaged", "q36_afterBackupStaged" -> new String[]{"A", "B0"};
			case "afterActiveReplace", "q36_afterActiveReplace" -> new String[]{"N", "B0"};
			case "afterBackupFinalize", "q36_afterBackupFinalize" -> new String[]{"N", "A"};
			case "fallbackBackupMid" -> new String[]{"A", "torn"};
			case "afterFallbackBackup" -> new String[]{"A", "A"};
			case "fallbackWriteMid" -> new String[]{"torn", "A"};
			default -> throw new IllegalArgumentException(point);
		};
	}

	void kill(String point) throws Exception {
		boolean q36 = point.startsWith("q36_");
		String probe = q36 ? point.substring(4) : point;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			Phase2Tests.cleanConfigFiles();
			q.setup(sp);
			edit(new ConfigEdit.RenameTab("qv", "kill b0"));
			byte[] b0 = bytes(cfg());
			edit(new ConfigEdit.RenameTab("qv", "kill a"));
			byte[] a = bytes(cfg());
			boolean setupOk = Arrays.equals(bytes(bak()), b0);
			Files.writeString(KILL_RECORD, "{\"point\": \"" + point + "\", \"a\": \"" + sha(a) + "\", \"b0\": \"" + sha(b0) + "\", \"setupOk\": " + setupOk + "}");
			Runnable halt = () -> Runtime.getRuntime().halt(3);
			if (point.startsWith("fallback") || point.equals("afterFallbackBackup")) ctx.runOnClient(c -> mgr().storage().devSetNoAtomicMove(true));
			ctx.runOnClient(c -> mgr().storage().devSetProbe(probe, halt));
			if (q36) {
				q.hidden("hidden kill cpx#X-kill", "qx");
				ConfigManager.OrphanCheck c = q.check(new ConfigEdit.DeleteTab("qx"));
				q.confirm(c, new ConfigEdit.DeleteTab("qx"));
			} else {
				edit(new ConfigEdit.RenameTab("qv", "kill n"));
			}
			ev.result("RC2.B11.kill." + point, Evidence.Outcome.FAIL, "reason", "the JVM was expected to halt at " + probe);
		}
	}

	/** The launch after a halt: which generations survived, the startup state, and the recovery path. */
	void killCheck() throws Exception {
		JsonObject rec = com.google.gson.JsonParser.parseString(Files.readString(KILL_RECORD)).getAsJsonObject();
		String point = rec.get("point").getAsString();
		String[] exp = expected(point);
		boolean q36 = point.startsWith("q36_");
		byte[] act = bytes(cfg()), bk = bytes(bak());
		String activeIs = classify(act, rec, q36), bakIs = classify(bk, rec, q36);
		ConfigManager.State st = onClient(() -> mgr().state());
		boolean onBackup = onClient(() -> mgr().runningOnBackup());
		int tempsNow = temps().size();
		Map<String, Object> recovery = new LinkedHashMap<>();
		boolean recoveryOk;
		if (exp[0].equals("torn")) {
			ConfigManager.ActionResult r = onClient(() -> mgr().restoreFromBackup(true));
			recovery.put("restoreFromBackup", r);
			recovery.put("activeAfterRestore", classify(bytes(cfg()), rec, q36));
			recoveryOk = st == ConfigManager.State.NO_SAVE && onBackup && r.ok() && "A".equals(recovery.get("activeAfterRestore"));
		} else if (exp[1].equals("torn")) {
			ConfigStorage.BackupState bs = onClient(() -> mgr().storage().inspectBackup().state());
			edit(new ConfigEdit.RenameTab("qv", "after torn backup"));
			recovery.put("backupStateAtStart", bs);
			recovery.put("backupAfterNextSave", classify(bytes(bak()), rec, q36));
			recoveryOk = st == ConfigManager.State.NORMAL && bs == ConfigStorage.BackupState.UNPARSEABLE && "A".equals(recovery.get("backupAfterNextSave"));
		} else {
			recoveryOk = st == ConfigManager.State.NORMAL;
		}
		boolean ok = rec.get("setupOk").getAsBoolean() && exp[0].equals(activeIs) && exp[1].equals(bakIs) && tempsNow == 0 && recoveryOk;
		ev.result("RC2.B11.kill." + point, ok, "activeIs", activeIs, "backupIs", bakIs, "expected", List.of(exp[0], exp[1]), "startupState", st, "runningOnBackup", onBackup,
				"tempFilesAfterStartup", tempsNow, "recovery", recovery,
				"note", "Runtime.halt at that point of the save; this launch reads what was left on disk (A = previous active, B0 = previous backup, N = new version)");
		Files.deleteIfExists(KILL_RECORD);
	}

	static String classify(byte[] b, JsonObject rec, boolean q36) {
		if (b == null) return "absent";
		String s = sha(b);
		if (s.equals(rec.get("a").getAsString())) return "A";
		if (s.equals(rec.get("b0").getAsString())) return "B0";
		ConfigParser.Result p = ConfigParser.parse(b);
		if (p.status() != ConfigParser.Status.OK) return "torn";
		var tab = p.config().windowOfTab("qv");
		boolean isNew = q36 ? p.config().windowOfTab("qx") == null && tab != null : tabName(p, "qv").equals("kill n");
		return isNew ? "N" : "other";
	}

	static String tabName(ConfigParser.Result p, String id) {
		for (var w : p.config().windows()) for (var tb : w.tabs()) if (id.equals(tb.id()) && tb.config() != null) return tb.config().name();
		return "";
	}

	// =================================================================== mode rc2_gui

	static String guiConfig() {
		return "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n\"windows\": [\n"
				+ " {\"id\": \"window-1\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.3, \"height\": 0.25, \"tabs\": ["
				+ "{\"id\": \"gx1\", \"name\": \"Smoke\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SA\"}]},"
				+ "{\"id\": \"gx2\", \"name\": \"Smoke\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SB\"}]},"
				+ "{\"id\": \"gx3\", \"name\": \"\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SC\"}]}]},\n"
				+ " {\"id\": \"window-2\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.3, \"width\": 0.3, \"height\": 0.25, \"tabs\": ["
				+ "{\"id\": \"gy1\", \"name\": \"Trade\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#TR\"}]}]},\n"
				+ " {\"id\": \"custom-w\", \"anchor\": \"TOP_LEFT\", \"x\": 0.5, \"y\": 0.5, \"width\": 0.2, \"height\": 0.2, \"tabs\": ["
				+ "{\"id\": \"gz1\", \"name\": \"Other\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#OT\"}]}]}\n"
				+ "]\n}\n";
	}

	void gui() throws Exception {
		ctx.getInput().resizeWindow(1280, 960);
		t.waitT(5);
		int oldScale = onClient(() -> Minecraft.getInstance().options.guiScale().get());
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 60; c.gui.getChat().clearMessages(false); });
			setScale(2);
			namesInDialogs(sp);
			caseToggle(sp);
			derivedLabels();
			layoutAtScales();
			bannerLayout();
			emptyState();
		} finally {
			reset();
			ctx.runOnClient(c -> { c.options.guiScale().set(oldScale); c.resizeDisplay(); });
			ctx.getInput().resizeWindow(854, 480);
		}
	}

	void restartGui() {
		Phase2Tests.cleanConfigFiles();
		Phase2Tests.write(cfg(), guiConfig());
		restart();
		t.clearHiddenFixtures();
		t.waitT(2);
	}

	/** S2: every safety dialog and refusal names tabs; ids only to tell duplicates apart or for unnamed entries. */
	void namesInDialogs(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		// (1) tab deletion: confirmation, then the disclosure (duplicate name, so the id is added)
		restartGui();
		q.hidden("hidden gx1 cpx#SA-1", "gx1");
		p4.open(() -> new WindowScreen(new ConfigScreen(null), "window-1"));
		p4.press("Delete");
		String confirmTab = dialogText();
		p4.press("Yes");
		String discTab = dialogText();
		shot("rc2-disclosure-duplicate-name");
		p4.press("No");
		boolean one = confirmTab != null && confirmTab.contains("Smoke") && discTab != null && discTab.contains("\"Smoke\" (gx1)") && !discTab.contains("[gx1]");
		m.put("1.tabDeletion", row("confirm", confirmTab, "disclosure", discTab, "ok", one));
		ok &= one;
		// (2) a uniquely named tab: name only, no id
		t.clearHiddenFixtures();
		q.hidden("hidden gy1 cpx#TR-1", "gy1");
		p4.open(() -> new WindowScreen(new ConfigScreen(null), "window-2"));
		p4.press("Delete");
		p4.press("Yes");
		String discUnique = dialogText();
		shot("rc2-disclosure-name");
		p4.press("No");
		boolean two = discUnique != null && discUnique.contains("\"Trade\"") && !discUnique.contains("gy1");
		m.put("2.uniqueName", row("disclosure", discUnique, "ok", two));
		ok &= two;
		// (3) refusal (chat busy) through the GUI, and (4) the unconfirmed path
		ctx.runOnClient(c -> OrphanRestore.devSetForceBusy(true));
		p4.press("Delete");
		p4.press("Yes");
		String refusal = statusText();
		ctx.runOnClient(c -> OrphanRestore.devSetForceBusy(false));
		ConfigManager.EditOutcome unconfirmed = edit(new ConfigEdit.DeleteTab("gy1"));
		String reason = String.valueOf(unconfirmed.edit().reason());
		boolean three = refusal != null && refusal.contains("\"Trade\"") && !refusal.contains("gy1") && reason.contains("\"Trade\"") && !reason.contains("gy1")
				&& onClient(() -> mgr().config().windowOfTab("gy1") != null);
		m.put("3.refusals", row("busyRefusal", refusal, "unconfirmedReason", reason, "ok", three));
		ok &= three;
		ctx.setScreen(() -> null);
		// (5) pruning disclosure names the tab and keeps the count
		t.clearHiddenFixtures();
		ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 5; c.gui.getChat().clearMessages(false); });
		for (int i = 0; i < 5; i++) q.visible(sp, "visible prune " + i + " cpx#VP-" + i);
		q.hidden("hidden prune cpx#TR-p", "gy1");
		p4.open(() -> new WindowScreen(new ConfigScreen(null), "window-2"));
		p4.press("Delete");
		p4.press("Yes");
		String discPrune = dialogText();
		shot("rc2-disclosure-pruned");
		p4.press("No");
		ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 60);
		boolean five = discPrune != null && discPrune.contains("\"Trade\"") && discPrune.contains("1 older visible message(s) will be pruned") && !discPrune.contains("gy1");
		m.put("5.pruningDisclosure", row("disclosure", discPrune, "ok", five));
		ok &= five;
		ctx.setScreen(() -> null);
		// (6) window deletion: the per-tab choice screen, then one disclosure naming all three (duplicates and the unnamed tab with ids)
		t.clearHiddenFixtures();
		q.hidden("hidden w1 cpx#SA-w", "gx1");
		q.hidden("hidden w2 cpx#SB-w", "gx2");
		q.hidden("hidden w3 cpx#SC-w", "gx3");
		WindowScreen ws = onClient(() -> new WindowScreen(new ConfigScreen(null), "window-1"));
		p4.open(ws);
		p4.press("Delete window");
		String choiceTitle = onClient(() -> Minecraft.getInstance().screen == null ? null : Minecraft.getInstance().screen.getTitle().getString());
		p4.press("Delete window");
		String discWindow = dialogText();
		shot("rc2-disclosure-window");
		p4.press("No");
		boolean six = choiceTitle != null && choiceTitle.contains("Window 1") && !choiceTitle.contains("window-1") && discWindow != null
				&& discWindow.contains("\"Smoke\" (gx1)") && discWindow.contains("\"Smoke\" (gx2)") && discWindow.contains("unnamed tab (gx3)")
				&& onClient(() -> mgr().config().window("window-1") != null);
		m.put("6.windowDeletion", row("choiceTitle", choiceTitle, "disclosure", discWindow, "ok", six));
		ok &= six;
		ctx.setScreen(() -> null);
		// (7) divergence from a hand edit removing a tab with hidden messages: the banner names it
		t.clearHiddenFixtures();
		q.hidden("hidden div cpx#TR-d", "gy1");
		JsonObject j = Phase2Tests.json(cfg());
		for (var w : j.getAsJsonArray("windows")) if (w.getAsJsonObject().get("id").getAsString().equals("window-2")) w.getAsJsonObject().add("tabs", new JsonArray());
		Phase2Tests.writeJson(cfg(), j);
		p4.open(() -> new ConfigScreen(null));
		String detail = onClient(() -> mgr().stateDetail());
		shot("rc2-divergence-banner");
		p4.press("Overwrite file");
		p4.press("Yes");
		ctx.setScreen(() -> null);
		boolean seven = detail.contains("\"Trade\"") && !detail.contains("gy1");
		m.put("7.divergenceBanner", row("detail", detail, "ok", seven));
		ok &= seven;
		t.clearHiddenFixtures();
		ev.result("RC2.S2.namesInSafetyDialogs", ok, "observed", m,
				"seeAlso", "RC2.B14 (Restore from backup disclosure names \"Smoke\"), RC2.B15 (refusal names it)",
				"note", "counts and consequences unchanged; \"No\" kept every tab (checked by the window and tab still being present)");
	}

	/** S3: the case button's tooltip states the current mode and what a click does; mouse and keyboard; semantics unchanged. */
	void caseToggle(TestSingleplayerContext sp) {
		restartGui();
		Map<String, Object> m = new LinkedHashMap<>();
		p4.open(() -> new TabScreen(new WindowScreen(new ConfigScreen(null), "window-2"), "gy1"));
		m.put("title", onClient(() -> Minecraft.getInstance().screen.getTitle().getString()));
		m.put("insensitiveTooltip", tooltipOf("aa"));
		AbstractWidget caseButton = onClient(() -> widgetNow("aa"));
		new Phase3Tests(t).cursor(caseButton.getX() + 12, caseButton.getY() + 10);
		t.waitT(5);
		shot("rc2-case-tooltip");
		p4.press("aa");
		m.put("afterClickLabel", onClient(() -> widgetNow("Aa") != null));
		m.put("sensitiveTooltip", tooltipOf("Aa"));
		p4.press("Save");
		boolean savedSensitive = Phase2Tests.filterOf(Phase2Tests.json(cfg()), "gy1", "include", 0).get("caseSensitive").getAsBoolean();
		m.put("savedCaseSensitive", savedSensitive);
		ctx.setScreen(() -> null);
		t.sys(sp, "upper CPX#TR-upper");
		t.sys(sp, "exact cpx#TR-exact");
		t.waitT(3);
		boolean upperRouted = inTab("gy1", "CPX#TR-upper"), exactRouted = inTab("gy1", "cpx#TR-exact");
		// keyboard: focus the button with Tab, toggle it with Enter
		p4.open(() -> new TabScreen(new WindowScreen(new ConfigScreen(null), "window-2"), "gy1"));
		boolean focused = false;
		for (int i = 0; i < 20 && !focused; i++) {
			ctx.getInput().pressKey(InputConstants.KEY_TAB);
			t.waitT(1);
			focused = onClient(() -> {
				Screen s = Minecraft.getInstance().screen;
				return s != null && s.getFocused() instanceof AbstractWidget w && w.getMessage().getString().equals("Aa");
			});
		}
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		t.waitT(2);
		boolean keyboardToggled = onClient(() -> widgetNow("aa") != null && widgetNow("Aa") == null);
		p4.press("Save");
		boolean savedInsensitive = !Phase2Tests.filterOf(Phase2Tests.json(cfg()), "gy1", "include", 0).has("caseSensitive")
				|| !Phase2Tests.filterOf(Phase2Tests.json(cfg()), "gy1", "include", 0).get("caseSensitive").getAsBoolean();
		ctx.setScreen(() -> null);
		t.sys(sp, "upper again CPX#TR-upper2");
		t.waitT(3);
		boolean upperRoutedNow = inTab("gy1", "CPX#TR-upper2");
		m.put("routing", row("caseSensitive:upper", upperRouted, "caseSensitive:exact", exactRouted, "caseInsensitive:upper", upperRoutedNow));
		m.put("keyboard", row("focusedByTab", focused, "toggledByEnter", keyboardToggled, "savedInsensitive", savedInsensitive));
		String ti = String.valueOf(m.get("insensitiveTooltip")), ts = String.valueOf(m.get("sensitiveTooltip"));
		boolean ok = ti.contains("Case-insensitive") && ti.contains("Click") && ts.contains("Case-sensitive") && ts.contains("Click") && Boolean.TRUE.equals(m.get("afterClickLabel"))
				&& savedSensitive && !upperRouted && exactRouted && focused && keyboardToggled && savedInsensitive && upperRoutedNow
				&& String.valueOf(m.get("title")).contains("\"Trade\"");
		ev.result("RC2.S3.caseSensitivityHelp", ok, "observed", m, "note", "the label stays aa/Aa; the flag written and the matching are exactly as before (caseSensitive true/false)");
	}

	boolean inTab(String tab, String token) {
		return onClient(() -> {
			TabState s = SessionMirror.get().tab(tab);
			if (s == null) return false;
			for (MessageRecord r : s.entries()) if (r.message().content().getString().contains(token)) return true;
			return false;
		});
	}

	/** M2: windows by derived labels, tab names visible, ids in tooltips; titles of the window and tab screens. */
	void derivedLabels() {
		restartGui();
		p4.open(() -> new ConfigScreen(null));
		Map<String, Object> m = new LinkedHashMap<>();
		List<String> buttons = onClient(() -> {
			List<String> out = new ArrayList<>();
			for (var ch : Minecraft.getInstance().screen.children()) if (ch instanceof AbstractWidget w) out.add(w.getMessage().getString());
			return out;
		});
		m.put("configScreenButtons", buttons);
		m.put("window1Tooltip", tooltipOf("Window 1:"));
		shot("rc2-window-list");
		p4.press("Window 1:");
		m.put("windowScreenTitle", onClient(() -> Minecraft.getInstance().screen.getTitle().getString()));
		m.put("moveTarget", onClient(() -> {
			for (var ch : Minecraft.getInstance().screen.children()) if (ch instanceof AbstractWidget w && w.getMessage().getString().startsWith("-> ")) return w.getMessage().getString();
			return null;
		}));
		shot("rc2-window-screen");
		ctx.setScreen(() -> null);
		boolean ok = buttons.contains("Window 1: Smoke, Smoke, (unnamed)") && buttons.contains("Window 2: Trade") && buttons.contains("Window \"custom-w\": Other")
				&& buttons.stream().noneMatch(b -> b.contains("window-1") || b.contains("tab(s)")) && String.valueOf(m.get("window1Tooltip")).contains("Internal id: window-1")
				&& "Window 1: Smoke, Smoke, (unnamed)".equals(m.get("windowScreenTitle")) && "-> Window 2".equals(m.get("moveTarget"));
		ev.result("RC2.M2.derivedWindowLabels", ok, "observed", m);
	}

	/** M1 (and the S3 button) at GUI scales 1-4 on 1280x960, and the narrowest practical layout. */
	void layoutAtScales() {
		restartGui();
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		int[][] sizes = {{1280, 960, 1}, {1280, 960, 2}, {1280, 960, 3}, {1280, 960, 4}, {854, 480, 2}, {640, 480, 2}};
		for (int[] sz : sizes) {
			ctx.getInput().resizeWindow(sz[0], sz[1]);
			t.waitT(3);
			setScale(sz[2]);
			int[] gs = screenSize();
			p4.open(() -> new TabScreen(new WindowScreen(new ConfigScreen(null), "window-2"), "gy1"));
			List<String> tabClipped = clippedLabels();
			List<String> tabOverlap = overlaps();
			boolean restoreFits = tabClipped.stream().noneMatch(s -> s.startsWith("Restored history"));
			boolean hideFits = tabClipped.stream().noneMatch(s -> s.startsWith("Hide from main chat"));
			boolean caseFits = tabClipped.stream().noneMatch(s -> s.startsWith("aa") || s.startsWith("Aa"));
			Integer restoreRight = onClient(() -> { AbstractWidget w = widgetNow("Restored history"); return w == null ? null : w.getX() + w.getWidth(); });
			boolean onScreen = restoreRight != null && restoreRight <= gs[0];
			shot("rc2-tab-editor-" + sz[0] + "x" + sz[1] + "-s" + sz[2]);
			p4.open(() -> new ConfigScreen(null));
			List<String> cfgClipped = clippedLabels();
			List<String> cfgOverlap = overlaps();
			p4.open(() -> new WindowScreen(new ConfigScreen(null), "window-1"));
			List<String> winClipped = clippedLabels();
			List<String> winOverlap = overlaps();
			ctx.setScreen(() -> null);
			boolean rowOk = restoreFits && hideFits && caseFits && onScreen && tabOverlap.isEmpty() && cfgOverlap.isEmpty() && winOverlap.isEmpty();
			rows.add(row("window", sz[0] + "x" + sz[1], "guiScaleSetting", sz[2], "guiSize", gs[0] + "x" + gs[1], "restoredHistoryFits", restoreFits, "hideFits", hideFits,
					"caseButtonFits", caseFits, "restoredHistoryOnScreen", onScreen, "tabEditorClipped", tabClipped, "tabEditorOverlaps", tabOverlap,
					"configScreenClipped", cfgClipped, "configScreenOverlaps", cfgOverlap, "windowScreenClipped", winClipped, "windowScreenOverlaps", winOverlap, "ok", rowOk));
			ok &= rowOk;
		}
		ctx.getInput().resizeWindow(1280, 960);
		t.waitT(3);
		setScale(2);
		ev.result("RC2.M1.labelsAtScales", ok, "rows", rows,
				"note", "pass requires the Restored history, Hide and case labels to fit and no overlapping widgets; other clipped labels (long window lists) are recorded, not asserted");
	}

	/** M3: the save-state banner wraps below the recovery buttons, never overlaps controls; Details shows all of it. */
	void bannerLayout() {
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		int[][] sizes = {{1280, 960, 2}, {1280, 960, 4}, {854, 480, 2}, {640, 480, 2}};
		for (String kind : new String[]{"noSave", "divergence"}) {
			for (int[] sz : sizes) {
				restartGui();
				if (kind.equals("noSave")) {
					edit(new ConfigEdit.RenameTab("gz1", "Other 2")); // a backup exists
					Phase2Tests.write(cfg(), "{\"schemaVersion\": 1, \"windows\": [ {\"id\": \"window-1\", \"tabs\": [ {\"id\": \"gx1\", \"name\": \"Smo");
					restart();
				} else {
					Phase2Tests.write(cfg(), "{ edited by hand and broken");
					ctx.runOnClient(c -> mgr().checkForExternalChanges());
				}
				ctx.getInput().resizeWindow(sz[0], sz[1]);
				t.waitT(3);
				setScale(sz[2]);
				int[] gs = screenSize();
				p4.open(() -> new ConfigScreen(null));
				String detail = onClient(() -> mgr().stateDetail());
				int[] layout = onClient(() -> {
					var font = Minecraft.getInstance().font;
					int w = Math.min(gs[0] - 20, 308);
					int lines = font.split(net.minecraft.network.chat.Component.literal(detail), w).size();
					return new int[]{lines, Math.min(3, lines)};
				});
				int bannerTop = 56, bannerBottom = 56 + layout[1] * 10;
				List<String> intersect = onClient(() -> {
					List<String> out = new ArrayList<>();
					for (var ch : Minecraft.getInstance().screen.children())
						if (ch instanceof AbstractWidget w && w.visible && w.getY() < bannerBottom && bannerTop < w.getY() + w.getHeight()) out.add(w.getMessage().getString());
					return out;
				});
				boolean details = widgetNow2("Details");
				boolean detailsExpected = layout[0] > 3;
				List<String> ov = overlaps();
				shot("rc2-banner-" + kind + "-" + sz[0] + "x" + sz[1] + "-s" + sz[2]);
				boolean actions = kind.equals("noSave") ? widgetNow2("Restore from backup") && widgetNow2("Replace file") : widgetNow2("Apply file") && widgetNow2("Overwrite file");
				String alert = null;
				if (details) {
					p4.press("Details");
					alert = dialogText();
					boolean isAlert = onClient(() -> Minecraft.getInstance().screen instanceof AlertScreen);
					if (!isAlert) alert = null;
					ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
					t.waitT(2);
				}
				ctx.setScreen(() -> null);
				boolean rowOk = intersect.isEmpty() && ov.isEmpty() && details == detailsExpected && (!details || (alert != null && alert.contains(detail.substring(Math.max(0, detail.length() - 30)))))
						&& actions;
				rows.add(row("state", kind, "window", sz[0] + "x" + sz[1], "guiScaleSetting", sz[2], "guiSize", gs[0] + "x" + gs[1], "detailLength", detail.length(),
						"wrappedLines", layout[0], "linesShown", layout[1], "widgetsOverBanner", intersect, "overlaps", ov, "detailsButton", details,
						"detailsShowsFullText", alert != null, "recoveryActionsVisible", actions, "ok", rowOk));
				ok &= rowOk;
			}
		}
		ctx.getInput().resizeWindow(1280, 960);
		t.waitT(3);
		setScale(2);
		restartGui();
		ev.result("RC2.M3.recoveryBanner", ok, "rows", rows, "note", "banner rectangle computed as the screen draws it (56 + 10 per line, at most 3 lines)");
	}

	boolean widgetNow2(String text) { return onClient(() -> widgetNow(text) != null); }

	/** M4: the empty settings screen explains windows and tabs and points to Add window. */
	void emptyState() {
		Phase2Tests.cleanConfigFiles();
		restart();
		List<String> sizes = new ArrayList<>();
		boolean ok = true;
		for (int[] sz : new int[][]{{1280, 960, 2}, {640, 480, 2}}) {
			ctx.getInput().resizeWindow(sz[0], sz[1]);
			t.waitT(3);
			setScale(sz[2]);
			p4.open(() -> new ConfigScreen(null));
			int[] gs = screenSize();
			int[] help = onClient(() -> {
				var font = Minecraft.getInstance().font;
				var lines = font.split(net.minecraft.network.chat.Component.translatable("cpextended.gui.empty"), Math.min(gs[0] - 40, 300));
				return new int[]{lines.size(), 58 + 6, 58 + 6 + lines.size() * 10};
			});
			List<String> intersect = onClient(() -> {
				List<String> out = new ArrayList<>();
				for (var ch : Minecraft.getInstance().screen.children())
					if (ch instanceof AbstractWidget w && w.visible && w.getY() < help[2] && help[1] < w.getY() + w.getHeight()) out.add(w.getMessage().getString());
				return out;
			});
			boolean addWindow = widgetNow2("Add window");
			String text = net.minecraft.locale.Language.getInstance().getOrDefault("cpextended.gui.empty");
			shot("rc2-empty-state-" + sz[0] + "x" + sz[1]);
			ctx.setScreen(() -> null);
			boolean rowOk = intersect.isEmpty() && addWindow && text.contains("Add window") && text.contains("tab");
			sizes.add(gs[0] + "x" + gs[1] + ": lines=" + help[0] + " overlaps=" + intersect + " addWindow=" + addWindow);
			ok &= rowOk;
		}
		// the next action works: Add window from the empty state
		p4.open(() -> new ConfigScreen(null));
		p4.press("Add window");
		boolean added = onClient(() -> mgr().config().windows().size() == 1);
		ctx.setScreen(() -> null);
		ctx.getInput().resizeWindow(1280, 960);
		t.waitT(3);
		ev.result("RC2.M4.emptyState", ok && added, "layouts", sizes, "addWindowWorks", added,
				"text", net.minecraft.locale.Language.getInstance().getOrDefault("cpextended.gui.empty"));
	}
}
