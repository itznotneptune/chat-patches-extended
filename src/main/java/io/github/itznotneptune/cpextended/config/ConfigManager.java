// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.itznotneptune.cpextended.CpExtended;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The single source of truth for configuration (P14, option B). Owns the loaded view, the raw tree, the content
 * baseline of the file version last loaded or written, the pending (unsaved) edits and the save state:
 * <ul>
 * <li><b>NORMAL</b>: saves allowed, each after an external-edit check;</li>
 * <li><b>NO_SAVE</b>: the file at startup was unparseable or had an unsupported {@code schemaVersion}. The original is
 * untouched (an unparseable one is also copied to {@code .corrupt-<timestamp>}); Extended runs read-only on the
 * understood fields, the last-good {@code .bak}, or defaults. Every write is blocked until the file is repaired in place
 * or the player explicitly chooses Restore from backup / Replace with current settings;</li>
 * <li><b>DIVERGENCE</b>: an on-disk change couldn't be applied (unparseable, unsupported, removed, or refused because
 * it would orphan hidden messages). Memory and file are both kept; every save is blocked until Apply file or an
 * explicitly confirmed Overwrite file.</li>
 * </ul>
 * No path may bypass the orphan rules: orphan restoration isn't implemented, so orphan-producing changes are refused.
 * Only configuration is ever written: no message history, hidden references or unread state.
 */
public final class ConfigManager {
	public enum State { NORMAL, NO_SAVE, DIVERGENCE }

	public enum SaveOutcome { SAVED, NOTHING_TO_SAVE, BLOCKED_NO_SAVE, BLOCKED_DIVERGENCE, BLOCKED_UNREADABLE, NOT_SAVED, APPLIED_EXTERNAL_EDIT }

	/** Result of an explicit action (Apply file, Overwrite, Restore from backup, Replace with current settings). */
	public record ActionResult(boolean ok, String message) {}

	public record EditOutcome(ConfigEdit.Result edit, @Nullable SaveOutcome save) {
		public boolean applied() { return edit.outcome() == ConfigEdit.Outcome.APPLIED; }
	}

	private static final int GEOMETRY_DEBOUNCE_TICKS = 20;
	private static ConfigManager instance;

	private final ConfigStorage storage;
	private ExtendedConfig config;
	private ConfigStorage.Fingerprint baseline = ConfigStorage.Fingerprint.MISSING;
	private boolean baselineKnown;
	private State state = State.NORMAL;
	private String stateDetail = "";
	private @Nullable ConfigStorage.Fingerprint divergedFingerprint;
	private final List<ConfigEdit> pending = new ArrayList<>();
	private boolean dirty;
	private int debounce = -1;
	private @Nullable Path corruptCopy;
	private boolean runningOnBackup;
	private SaveOutcome lastSave;

	private ConfigManager(ConfigStorage storage) {
		this.storage = storage;
		this.config = defaults();
	}

	public static synchronized ConfigManager get() {
		if (instance == null) instance = new ConfigManager(new ConfigStorage(FabricLoader.getInstance().getConfigDir().resolve("cpextended.json")));
		return instance;
	}

	// ---------------------------------------------------------------- read-only accessors

	public ExtendedConfig config() { return config; }
	public State state() { return state; }
	public String stateDetail() { return stateDetail; }
	public boolean dirty() { return dirty; }
	public ConfigStorage storage() { return storage; }
	public ConfigStorage.Fingerprint baseline() { return baseline; }
	public @Nullable Path corruptCopy() { return corruptCopy; }
	public boolean runningOnBackup() { return runningOnBackup; }
	public @Nullable SaveOutcome lastSave() { return lastSave; }
	public int pendingEdits() { return pending.size(); }

	// ---------------------------------------------------------------- startup

	/** Loads the file at startup (establishing the baseline) and installs its routing. */
	public synchronized void startup() {
		pending.clear();
		dirty = false;
		debounce = -1;
		corruptCopy = null;
		runningOnBackup = false;
		divergedFingerprint = null;
		ConfigStorage.Read r = storage.read();
		if (r.unreadable()) {
			// Can't rule anything out: run on defaults, write nothing; every save attempt re-checks the file.
			use(defaults());
			baselineKnown = false;
			enter(State.NO_SAVE, "configuration file unreadable: " + r.error());
		} else if (r.missing()) {
			use(defaults());
			baseline = ConfigStorage.Fingerprint.MISSING;
			baselineKnown = true;
			state = State.NORMAL;
			stateDetail = "";
			dirty = true; // nothing to lose: the first save creates the file
			attemptSave("first start");
		} else {
			loadExisting(r.bytes());
		}
		Diagnostics.event("config_loaded", "state", state, "windows", config.windows().size(), "issues", config.issues().size());
	}

	private void loadExisting(byte[] bytes) {
		baseline = ConfigStorage.Fingerprint.of(bytes);
		baselineKnown = true;
		ConfigParser.Result p = ConfigParser.parse(bytes);
		switch (p.status()) {
			case OK -> {
				use(p.config());
				state = State.NORMAL;
				stateDetail = "";
				for (String issue : p.config().issues()) CpExtended.LOGGER.warn("cpextended.json: {}", issue);
			}
			case UNSUPPORTED_SCHEMA -> {
				use(p.config());
				enter(State.NO_SAVE, "cpextended.json has " + p.error() + "; running read-only on the fields this version understands");
			}
			case UNPARSEABLE -> {
				try {
					corruptCopy = storage.preserveCorrupt(bytes);
				} catch (IOException e) {
					CpExtended.LOGGER.warn("Could not write a .corrupt copy of cpextended.json", e);
				}
				ExtendedConfig fallback = defaults();
				ConfigStorage.Read bak = storage.readBackup();
				if (bak.bytes() != null) {
					ConfigParser.Result bp = ConfigParser.parse(bak.bytes());
					if (bp.status() == ConfigParser.Status.OK) {
						fallback = bp.config();
						runningOnBackup = true;
					}
				}
				use(fallback);
				enter(State.NO_SAVE, "cpextended.json can't be parsed (" + p.error() + "); original left in place"
						+ (corruptCopy != null ? ", copy at " + corruptCopy.getFileName() : "")
						+ (runningOnBackup ? "; running read-only on " + storage.backup().getFileName() + " (Restore from backup writes it back)"
								: "; running read-only on defaults"));
			}
		}
	}

	// ---------------------------------------------------------------- edits

	/** Applies an edit in memory (allowed in every state) and saves it when saves are permitted. */
	public synchronized EditOutcome edit(ConfigEdit e) {
		JsonObject copy = config.raw().deepCopy();
		ExtendedConfig copyView = ConfigParser.interpret(copy).config();
		ConfigEdit.Result r = e.apply(copy, copyView);
		if (r.outcome() != ConfigEdit.Outcome.APPLIED) return new EditOutcome(r, null);
		ExtendedConfig next = ConfigParser.interpret(copy).config();
		List<String> orphans = OrphanGuard.orphaningTabs(config.routing(), next.routing());
		if (!orphans.isEmpty()) {
			return new EditOutcome(ConfigEdit.Result.rejected("would orphan hidden messages in " + orphans
					+ "; orphan restoration isn't available, so the change is refused (keep, disable or move the tab instead)"), null);
		}
		use(next);
		pending.add(e);
		dirty = true;
		if (e.debounced()) {
			debounce = GEOMETRY_DEBOUNCE_TICKS;
			return new EditOutcome(r, null);
		}
		return new EditOutcome(r, attemptSave("edit"));
	}

	/**
	 * Applies several edits atomically (the config screen's Save): all apply to one copy, the orphan rule is checked on
	 * the result, then a single save. If any edit doesn't apply, nothing changes.
	 */
	public synchronized EditOutcome editAll(List<ConfigEdit> edits) {
		JsonObject copy = config.raw().deepCopy();
		for (ConfigEdit e : edits) {
			ConfigEdit.Result r = e.apply(copy, ConfigParser.interpret(copy).config());
			if (r.outcome() != ConfigEdit.Outcome.APPLIED) return new EditOutcome(r, null);
		}
		ExtendedConfig next = ConfigParser.interpret(copy).config();
		List<String> orphans = OrphanGuard.orphaningTabs(config.routing(), next.routing());
		if (!orphans.isEmpty()) {
			return new EditOutcome(ConfigEdit.Result.rejected("would orphan hidden messages in " + orphans
					+ "; orphan restoration isn't available, so the change is refused (keep, disable or move the tab instead)"), null);
		}
		use(next);
		pending.addAll(edits);
		dirty = true;
		return new EditOutcome(ConfigEdit.Result.OK, attemptSave("edit"));
	}

	/** A window or tab id not used in the configuration, with the given prefix. */
	public String unusedId(String prefix) {
		for (int i = 1; ; i++) {
			String id = prefix + "-" + i;
			if (config.window(id) == null && config.windowOfTab(id) == null) return id;
		}
	}

	/** Client tick: runs a debounced save when due. */
	public synchronized void tick() {
		if (debounce > 0 && --debounce == 0) {
			debounce = -1;
			attemptSave("debounced");
		}
	}

	// ---------------------------------------------------------------- saving

	/** Attempts a save now (manual or automatic). Always checks the file's content first. */
	public synchronized SaveOutcome attemptSave(String trigger) {
		SaveOutcome o = doSave(trigger, true, true);
		lastSave = o;
		Diagnostics.event("config_save", "trigger", trigger, "outcome", o, "state", state);
		return o;
	}

	private SaveOutcome doSave(String trigger, boolean mayRetryAfterAdopt, boolean write) {
		ConfigStorage.Read now = storage.read();
		if (now.unreadable()) {
			Warnings.report("config:unreadable:" + now.error().getClass().getName(), Warnings.Toast.CONFIG_NOT_SAVED,
					"cpextended.json can't be read to check for external edits; not saved (retried at the next save)", now.error());
			return SaveOutcome.BLOCKED_UNREADABLE;
		}
		boolean adopted = false;
		if (!baselineKnown || !now.fingerprint().equals(baseline)) {
			SaveOutcome handled = onExternalChange(now);
			if (handled != null) return handled;
			adopted = true;
		}
		if (state == State.NO_SAVE) return SaveOutcome.BLOCKED_NO_SAVE;
		if (state == State.DIVERGENCE) return SaveOutcome.BLOCKED_DIVERGENCE;
		if (!write) return adopted ? SaveOutcome.APPLIED_EXTERNAL_EDIT : SaveOutcome.NOTHING_TO_SAVE;
		if (!dirty) return SaveOutcome.NOTHING_TO_SAVE;
		ConfigStorage.SaveResult r = storage.save(ConfigWriter.toBytes(config.raw()), baseline);
		switch (r.status()) {
			case SAVED_ATOMIC, SAVED_FALLBACK -> {
				baseline = r.written();
				pending.clear();
				dirty = false;
				debounce = -1;
				if (r.status() == ConfigStorage.SaveStatus.SAVED_FALLBACK) {
					CpExtended.LOGGER.info("cpextended.json saved without atomic replace; previous version kept as {}", storage.backup().getFileName());
				}
				return SaveOutcome.SAVED;
			}
			case CHANGED_ON_DISK -> {
				// Changed between our check and the final one: handle it like any external edit, then retry once.
				SaveOutcome handled = onExternalChange(storage.read());
				if ((handled == null || handled == SaveOutcome.APPLIED_EXTERNAL_EDIT) && mayRetryAfterAdopt) return doSave(trigger, false, true);
				return handled != null ? handled : SaveOutcome.BLOCKED_DIVERGENCE;
			}
			case UNREADABLE -> {
				return SaveOutcome.BLOCKED_UNREADABLE;
			}
			default -> {
				Warnings.report("config:not_saved:" + r.detail(), Warnings.Toast.CONFIG_NOT_SAVED,
						"cpextended.json not saved after one retry (" + r.detail() + "); changes stay in memory and are retried at the next save", null);
				return SaveOutcome.NOT_SAVED;
			}
		}
	}

	/**
	 * The file's content differs from the baseline. Returns null to let the save continue (the change was adopted and
	 * pending edits re-applied), or why it can't.
	 */
	private @Nullable SaveOutcome onExternalChange(ConfigStorage.Read now) {
		if (state == State.DIVERGENCE) return SaveOutcome.BLOCKED_DIVERGENCE; // only Apply file / Overwrite leave the lock
		if (!baselineKnown && now.missing()) {
			// Unreadable at startup, missing now: nothing to lose.
			baselineKnown = true;
			baseline = ConfigStorage.Fingerprint.MISSING;
			leaveNoSave();
			return null;
		}
		if (now.missing()) {
			divergedFingerprint = ConfigStorage.Fingerprint.MISSING;
			enter(State.DIVERGENCE, "cpextended.json was removed; Apply file adopts defaults, Overwrite file recreates it");
			return SaveOutcome.BLOCKED_DIVERGENCE;
		}
		ConfigStorage.Fingerprint fp = now.fingerprint();
		ConfigParser.Result p = ConfigParser.parse(now.bytes());
		if (p.status() != ConfigParser.Status.OK) {
			if (state == State.NO_SAVE) {
				baseline = fp; // still not repaired; stay read-only
				baselineKnown = true;
				return SaveOutcome.BLOCKED_NO_SAVE;
			}
			divergedFingerprint = fp;
			enter(State.DIVERGENCE, "cpextended.json was edited and " + (p.status() == ConfigParser.Status.UNPARSEABLE
					? "can't be parsed (" + p.error() + ")" : "has " + p.error()) + "; your settings and the file are both kept");
			return SaveOutcome.BLOCKED_DIVERGENCE;
		}
		List<String> orphans = OrphanGuard.orphaningTabs(config.routing(), p.config().routing());
		if (!orphans.isEmpty()) {
			divergedFingerprint = fp;
			enter(State.DIVERGENCE, "cpextended.json was edited to remove tabs holding hidden messages " + orphans
					+ "; that would orphan them and orphan restoration isn't available, so the edit isn't applied");
			return SaveOutcome.BLOCKED_DIVERGENCE;
		}
		// Valid and non-orphaning: adopt the file, then re-apply the edits made since the last save on top of it.
		ExtendedConfig adopted = p.config();
		List<ConfigEdit> kept = new ArrayList<>();
		JsonObject tree = adopted.raw();
		for (ConfigEdit e : pending) {
			ExtendedConfig view = ConfigParser.interpret(tree).config();
			ConfigEdit.Result r = e.apply(tree, view);
			if (r.outcome() == ConfigEdit.Outcome.APPLIED) kept.add(e);
		}
		ExtendedConfig merged = ConfigParser.interpret(tree).config();
		if (!OrphanGuard.orphaningTabs(config.routing(), merged.routing()).isEmpty()) {
			merged = adopted; // never let a re-applied edit orphan anything
			kept.clear();
		}
		boolean wasNoSave = state == State.NO_SAVE;
		use(merged);
		baseline = fp;
		baselineKnown = true;
		pending.clear();
		pending.addAll(kept);
		dirty = !kept.isEmpty();
		if (wasNoSave) leaveNoSave();
		CpExtended.LOGGER.info("cpextended.json was edited outside the game; the edit was applied ({} pending change(s) re-applied)", kept.size());
		return dirty ? null : SaveOutcome.APPLIED_EXTERNAL_EDIT;
	}

	/** Checks the file for external edits without saving (config screen opening, Phase 4; also usable by tests). */
	public synchronized SaveOutcome checkForExternalChanges() {
		return doSave("check", false, false);
	}

	// ---------------------------------------------------------------- explicit actions

	/** Divergence lock: reload the file and apply it in memory atomically. Refused (nothing changes) if not possible. */
	public synchronized ActionResult applyFile() {
		if (state != State.DIVERGENCE) return new ActionResult(false, "not in a divergence lock");
		ConfigStorage.Read now = storage.read();
		if (now.unreadable()) return new ActionResult(false, "file can't be read: " + now.error());
		ExtendedConfig next;
		if (now.missing()) next = defaults();
		else {
			ConfigParser.Result p = ConfigParser.parse(now.bytes());
			if (p.status() != ConfigParser.Status.OK) return new ActionResult(false, "file still " + (p.error() == null ? "invalid" : p.error()));
			next = p.config();
		}
		List<String> orphans = OrphanGuard.orphaningTabs(config.routing(), next.routing());
		if (!orphans.isEmpty()) {
			return new ActionResult(false, "applying the file would orphan hidden messages in " + orphans + "; refused (orphan restoration isn't available)");
		}
		use(next);
		baseline = now.fingerprint();
		baselineKnown = true;
		pending.clear();
		dirty = now.missing(); // adopting defaults for a removed file: the next save recreates it
		divergedFingerprint = null;
		state = State.NORMAL;
		stateDetail = "";
		CpExtended.LOGGER.info("Divergence lock cleared: cpextended.json applied");
		return new ActionResult(true, "file applied");
	}

	/** Divergence lock: write the current settings over the file. Needs explicit confirmation; in-memory tabs are kept. */
	public synchronized ActionResult overwriteFile(boolean confirmed) {
		if (state != State.DIVERGENCE) return new ActionResult(false, "not in a divergence lock");
		if (!confirmed) return new ActionResult(false, "confirmation required");
		ConfigStorage.Fingerprint expected = divergedFingerprint == null ? baseline : divergedFingerprint;
		ConfigStorage.SaveResult r = storage.save(ConfigWriter.toBytes(config.raw()), expected);
		if (r.written() == null) {
			if (r.status() == ConfigStorage.SaveStatus.CHANGED_ON_DISK) {
				divergedFingerprint = storage.read().fingerprint();
				return new ActionResult(false, "the file changed again since the lock began; review it before overwriting");
			}
			return new ActionResult(false, "not saved: " + r.detail());
		}
		baseline = r.written();
		baselineKnown = true;
		pending.clear();
		dirty = false;
		divergedFingerprint = null;
		state = State.NORMAL;
		stateDetail = "";
		CpExtended.LOGGER.info("Divergence lock cleared: cpextended.json overwritten with current settings");
		return new ActionResult(true, "file overwritten");
	}

	/** No-save: write the last-good {@code .bak} back as the primary file (explicit approval) and use it. */
	public synchronized ActionResult restoreFromBackup(boolean confirmed) {
		if (state != State.NO_SAVE) return new ActionResult(false, "not in the no-save state");
		if (!confirmed) return new ActionResult(false, "confirmation required");
		ConfigStorage.Read bak = storage.readBackup();
		if (bak.bytes() == null) return new ActionResult(false, "no readable " + storage.backup().getFileName());
		ConfigParser.Result p = ConfigParser.parse(bak.bytes());
		if (p.status() != ConfigParser.Status.OK) return new ActionResult(false, storage.backup().getFileName() + " isn't valid either");
		List<String> orphans = OrphanGuard.orphaningTabs(config.routing(), p.config().routing());
		if (!orphans.isEmpty()) return new ActionResult(false, "the backup would orphan hidden messages in " + orphans + "; refused");
		ActionResult w = replacePrimary(bak.bytes());
		if (!w.ok()) return w;
		use(p.config());
		CpExtended.LOGGER.info("cpextended.json restored from {}", storage.backup().getFileName());
		return new ActionResult(true, "restored from backup");
	}

	/** No-save: write the settings currently in use (fallback or understood fields) as the primary file. */
	public synchronized ActionResult replaceWithCurrentSettings(boolean confirmed) {
		if (state != State.NO_SAVE) return new ActionResult(false, "not in the no-save state");
		if (!confirmed) return new ActionResult(false, "confirmation required");
		JsonObject tree = config.raw().deepCopy();
		tree.addProperty("schemaVersion", ExtendedConfig.SCHEMA_VERSION); // explicit replacement writes this version's schema
		ActionResult w = replacePrimary(ConfigWriter.toBytes(tree));
		if (!w.ok()) return w;
		use(ConfigParser.interpret(tree).config());
		CpExtended.LOGGER.info("cpextended.json replaced with the current settings");
		return new ActionResult(true, "replaced with current settings");
	}

	private ActionResult replacePrimary(byte[] bytes) {
		ConfigStorage.SaveResult r = storage.save(bytes, baseline);
		if (r.written() == null) {
			if (r.status() == ConfigStorage.SaveStatus.CHANGED_ON_DISK) {
				// The player edited the file meanwhile: treat it as a possible repair instead of overwriting it.
				onExternalChange(storage.read());
				return new ActionResult(false, "the file changed since it was read; " + (state == State.NORMAL ? "the repaired file is now in use" : "review it first"));
			}
			return new ActionResult(false, "not saved: " + r.detail());
		}
		baseline = r.written();
		baselineKnown = true;
		pending.clear();
		dirty = false;
		leaveNoSave();
		return new ActionResult(true, "written");
	}

	// ---------------------------------------------------------------- internals

	private void use(ExtendedConfig next) {
		config = next;
		RoutingConfig routing = next.routing();
		Router.get().install(routing);
	}

	private void enter(State s, String detail) {
		state = s;
		stateDetail = detail;
		Warnings.report("config:" + s + ":" + baseline.sha256() + ":" + detail.hashCode(),
				s == State.NO_SAVE ? Warnings.Toast.CONFIG_NO_SAVE : Warnings.Toast.CONFIG_DIVERGENCE, "Saving disabled: " + detail, null);
	}

	private void leaveNoSave() {
		if (state == State.NO_SAVE) CpExtended.LOGGER.info("cpextended.json is valid again; saving re-enabled");
		state = State.NORMAL;
		stateDetail = "";
		runningOnBackup = false;
	}

	static ExtendedConfig defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", ExtendedConfig.SCHEMA_VERSION);
		JsonObject general = new JsonObject();
		general.addProperty("enabled", true);
		general.addProperty("hidingEnabled", true);
		root.add("general", general);
		JsonObject appearance = new JsonObject();
		for (String k : new String[]{"backgroundOpacity", "textOpacity", "scale", "lineSpacing"}) appearance.add(k, JsonNull.INSTANCE);
		root.add("appearance", appearance);
		root.add("windows", new com.google.gson.JsonArray());
		return ConfigParser.interpret(root).config();
	}

	// ---------------------------------------------------------------- development only

	/** Development builds only: re-runs the startup load against the files on disk, as a relaunch would. */
	public synchronized void devSimulateRestart() {
		DevHooks.requireDev();
		state = State.NORMAL;
		stateDetail = "";
		startup();
	}
}
