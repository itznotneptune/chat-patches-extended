package io.github.itznotneptune.cpxspike.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpxspike.SpikeLog;
import io.github.itznotneptune.cpxspike.mixin.ChatComponentAccessorSpike;
import io.github.itznotneptune.cpxspike.mixin.S2AccessorsSpike;
import io.github.itznotneptune.cpxspike.s1.Capture;
import io.github.itznotneptune.cpxspike.s1.SpikeAdapter;
import io.github.itznotneptune.cpxspike.s2.InputSpike;
import io.github.itznotneptune.cpxspike.s2.SpikeWindow;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.ChatVisiblity;
import obro1961.chatpatches.Boundary;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.accessor.ChatComponentAccess;
import obro1961.chatpatches.accessor.ChatScreenAccess;
import obro1961.chatpatches.util.ChatUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Phase 0 spike scenarios (architecture §13.3/13.4). Each run executes one mode from cpx-mode.txt. */
public final class SpikeGameTest implements FabricClientGameTest {
	private ClientGameTestContext ctx;
	private String mode;

	@Override
	public void runTest(ClientGameTestContext context) {
		this.ctx = context;
		this.mode = Mode.read();
		SpikeLog.clear();
		SpikeLog.ev("mode", "mode", mode, "time", Instant.now().toString());
		context.runOnClient(c -> resetConfig());
		try {
			switch (mode) {
				case "s1a" -> s1a();
				case "s1b" -> s1b();
				case "s1c" -> s1c();
				case "s1conflict_bool", "s1conflict_int" -> s1conflict();
				case "s2" -> s2();
				case "s2probe" -> s2probe();
				default -> SpikeLog.ev("error", "msg", "unknown mode " + mode);
			}
		} catch (Throwable t) {
			SpikeLog.ev("error", "msg", t.toString());
			SpikeLog.LOG.error("spike scenario failed", t);
		} finally {
			SpikeLog.dump(Path.of("cpx-evidence", mode + ".jsonl"));
		}
	}

	// ------------------------------------------------------------------ helpers
	static void resetConfig() {
		var c = ChatPatches.config;
		c.chatlog = true;
		c.vanillaClearing = false;
		c.counter = true;
		c.compactChat = false;
		c.compactDistance = 0;
		Capture.hideEnabled = true;
		Capture.foreignAddEnabled = false;
		SpikeAdapter.FAULTS.clear();
	}

	static void res(String test, boolean pass, Object... details) {
		Object[] kv = new Object[details.length + 4];
		kv[0] = "test"; kv[1] = test; kv[2] = "pass"; kv[3] = pass;
		System.arraycopy(details, 0, kv, 4, details.length);
		SpikeLog.ev("result", kv);
	}

	void sys(TestSingleplayerContext sp, String text) {
		sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
	}

	void waitT(int n) { ctx.waitTicks(n); }

	List<GuiMessage> allMessages() {
		return ctx.computeOnClient(c -> ((ChatComponentAccessorSpike) c.gui.getChat()).cpx$getAllMessages());
	}

	static List<Map<String, Object>> events(String type, String token) {
		return SpikeLog.of(type).stream().filter(e -> token == null || token.equals(e.get("token"))).toList();
	}

	static Map<String, Object> last(List<Map<String, Object>> l) { return l.isEmpty() ? null : l.get(l.size() - 1); }

	// ------------------------------------------------------------------ S1a: live capture, hiding, condensation
	void s1a() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			// V-59 (2): searchField read before init must report "not available", not a failure
			ctx.runOnClient(c -> {
				ChatScreen fresh = new ChatScreen("", false);
				EditBox f = SpikeAdapter.searchFieldOf(fresh);
				res("V-59.2", f == null, "searchFieldBeforeInit", String.valueOf(f), "reflection", SpikeAdapter.reflectionStatus);
			});
			res("V-59.1", "ok".equals(SpikeAdapter.reflectionStatus), "reflection", SpikeAdapter.reflectionStatus);

			// structures (V-27f live half) + V-01
			ctx.runOnClient(c -> c.player.connection.sendChat("hello world cpx#S-player1"));
			sp.getServer().runCommand("say announce cpx#S-say1");
			sys(sp, "plugin-like system cpx#S-sys1");
			for (int i = 0; i < 3; i++) sys(sp, "dupe line cpx#S-dupe");
			waitT(30);
			List<Map<String, Object>> live = SpikeLog.of("capture").stream().filter(e -> "LIVE".equals(e.get("origin"))).toList();
			long bad = live.stream().filter(e -> Boolean.TRUE.equals(e.get("originalHasCpStructure")) || !Boolean.TRUE.equals(e.get("processedHasCpStructure"))).count();
			res("V-01", !live.isEmpty() && bad == 0, "liveCaptures", live.size(), "violations", bad);

			// hiding basic
			sys(sp, "[HIDE] hidden one cpx#H1");
			waitT(10);
			var dH = last(events("deliver", "cpx#H1"));
			boolean notInMain = allMessages().stream().noneMatch(g -> g.content().getString().contains("cpx#H1"));
			res("hide-basic", dH != null && Boolean.TRUE.equals(dH.get("hidden")) && notInMain && !Capture.HIDDEN.isEmpty(),
					"deliver", dH, "absentFromAllMessages", notInMain);

			// Q3 / V-04: visible copy exists, then identical message that would be hidden
			Capture.hideEnabled = false;
			sys(sp, "[HIDE] cpx-dupe alpha cpx#Q3");
			waitT(8);
			Capture.hideEnabled = true;
			sys(sp, "[HIDE] cpx-dupe alpha cpx#Q3");
			waitT(8);
			var q3 = last(events("condense", "cpx#Q3"));
			res("V-04", q3 != null && Boolean.TRUE.equals(q3.get("detected")) && Boolean.FALSE.equals(q3.get("hidden"))
					&& ((Number) q3.get("truthRemoved")).intValue() >= 1, "condense", q3);

			// V-04c cases
			v04c(sp, "c5", () -> { ChatPatches.config.compactChat = true; ChatPatches.config.compactDistance = 5; }, 3, true);
			v04c(sp, "c0", () -> { ChatPatches.config.compactChat = true; ChatPatches.config.compactDistance = 0; }, 3, true);
			v04c(sp, "c500", () -> { ChatPatches.config.compactChat = true; ChatPatches.config.compactDistance = 500; }, 10, true);
			v04c(sp, "cneg", () -> { ChatPatches.config.compactChat = true; ChatPatches.config.compactDistance = -5; }, 1, false);
			v04c(sp, "counteroff", () -> { ChatPatches.config.counter = false; }, 0, false);
			v04c(sp, "foreignadd", () -> { Capture.foreignAddEnabled = true; }, 0, true);
			v04c(sp, "fault", () -> SpikeAdapter.FAULTS.add(SpikeAdapter.Member.CONDENSE), 0, true);
			ctx.runOnClient(c -> resetConfig());

			// V-47 nested frame
			sys(sp, "[NEST] outer cpx#N1");
			waitT(10);
			var outer = events("deliver", "cpx#N1");
			var inner = events("deliver", "cpx#nested-inner");
			boolean eq = SpikeLog.of("deliver").stream().allMatch(e -> Boolean.TRUE.equals(e.get("m2_equals_m3")));
			res("V-47", outer.size() == 1 && inner.size() == 1 && eq
							&& SpikeLog.of("m2_noframe").isEmpty() && SpikeLog.of("m3_noframe").isEmpty(),
					"outerDeliveries", outer.size(), "innerDeliveries", inner.size(), "allM2EqualsM3", eq,
					"innerDepth", inner.isEmpty() ? null : inner.get(0).get("depth"));

			// bulk history for V-02 (large chatlog on next launch)
			sp.getServer().runOnServer(s -> {
				for (int i = 0; i < 3000; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("bulk entry " + i + " cpx#B" + i), false);
			});
			waitT(120);
			int logged = ctx.computeOnClient(c -> ChatLog.messageCount());
			SpikeLog.ev("s1a_logged", "chatLogMessageCount", logged, "allMessages", allMessages().size());
		}
		waitT(40); // disconnect -> Chat Patches serializes chatlog.json
	}

	void v04c(TestSingleplayerContext sp, String name, Runnable setup, int fillers, boolean expectRemoval) {
		ctx.runOnClient(c -> setup.run());
		String x = "cpx-dupe case " + name + " cpx#C-" + name;
		sys(sp, x);
		waitT(4);
		for (int i = 0; i < fillers; i++) { sys(sp, "filler " + name + " " + i + " cpx#F-" + name + i); waitT(2); }
		sys(sp, x);
		waitT(6);
		var e = last(events("condense", "cpx#C-" + name));
		boolean truth = e != null && ((Number) e.get("truthRemoved")).intValue() > 0;
		boolean detected = e != null && Boolean.TRUE.equals(e.get("detected"));
		res("V-04c." + name, e != null && truth == detected && truth == expectRemoval, "condense", e,
				"fallbackEvents", SpikeLog.of("adapter_fallback").size());
		ctx.runOnClient(c -> resetConfig());
	}

	// ------------------------------------------------------------------ S1b: restore, structure round trip, list swap
	void s1b() throws Exception {
		Map<String, Map<String, Object>> liveStructs = readStructures(Path.of("cpx-evidence", "s1a.jsonl"), "LIVE");
		Integer s1aLogged = readS1aLogged(Path.of("cpx-evidence", "s1a.jsonl"));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			// live traffic immediately on join, racing Chat Patches' asynchronous log load
			sp.getServer().runOnServer(s -> {
				for (int i = 0; i < 20; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("live early cpx#L" + i), false);
			});
			waitT(160);

			// V-02
			Map<String, Integer> caps = new HashMap<>();
			for (var e : SpikeLog.of("capture")) if ("LIVE".equals(e.get("origin")) && e.get("token") != null) caps.merge((String) e.get("token"), 1, Integer::sum);
			long wrong = 0;
			for (int i = 0; i < 20; i++) if (!Objects.equals(caps.get("cpx#L" + i), 1)) wrong++;
			long queuedFrames = SpikeLog.of("frame_no_decision").stream().filter(e -> Boolean.TRUE.equals(e.get("loading"))).count();
			res("V-02", wrong == 0, "liveTokensNotExactlyOnce", wrong, "framesCancelledWhileLoading", queuedFrames);

			// V-03
			var restored = SpikeLog.of("capture").stream().filter(e -> "RESTORED".equals(e.get("origin"))).toList();
			long liveTaggedRestored = restored.stream().filter(e -> String.valueOf(e.get("token")).startsWith("cpx#L")).count();
			long bulkAsLive = SpikeLog.of("capture").stream().filter(e -> "LIVE".equals(e.get("origin")) && String.valueOf(e.get("token")).startsWith("cpx#B")).count();
			res("V-03", !restored.isEmpty() && liveTaggedRestored == 0 && bulkAsLive == 0,
					"restoredCaptures", restored.size(), "s1aChatLogCount", s1aLogged, "liveTaggedRestored", liveTaggedRestored, "bulkCapturedAsLive", bulkAsLive);

			// V-27f
			Map<String, Map<String, Object>> restoredStructs = new LinkedHashMap<>();
			for (var e : SpikeLog.of("structure")) if ("RESTORED".equals(e.get("origin"))) restoredStructs.put((String) e.get("token"), castMap(e.get("tree")));
			List<String> diffs = new ArrayList<>();
			for (var en : liveStructs.entrySet()) {
				var r = restoredStructs.get(en.getKey());
				if (!Objects.equals(normalize(en.getValue()), normalize(r))) diffs.add(en.getKey() + " live=" + en.getValue() + " restored=" + r);
			}
			res("V-27f", !liveStructs.isEmpty() && diffs.isEmpty(), "compared", liveStructs.keySet(), "diffs", diffs);

			// V-20m: Q36 reference swap with Chat Patches
			v20m(sp);

			// V-05: Chat Patches search triggers rescale outside addMessage
			int before = Capture.displayQueueCallsNoFrame.get();
			int delivBefore = SpikeLog.of("deliver").size();
			ctx.setScreen(() -> new ChatScreen("", false));
			waitT(5);
			ctx.runOnClient(c -> {
				EditBox f = SpikeAdapter.searchFieldOf((ChatScreen) c.screen);
				f.setValue("bulk");
			});
			waitT(5);
			ctx.runOnClient(c -> SpikeAdapter.searchFieldOf((ChatScreen) c.screen).setValue(""));
			waitT(5);
			ctx.setScreen(() -> null);
			waitT(5);
			int after = Capture.displayQueueCallsNoFrame.get();
			res("V-05", after > before && SpikeLog.of("m2_noframe").isEmpty() && SpikeLog.of("deliver").size() == delivBefore,
					"displayQueueCallsWithoutFrame", after - before, "newDeliveries", SpikeLog.of("deliver").size() - delivBefore);
		}
		waitT(40);
		// V-20m(e,f): boundary on next world; chat log has no swapped-in message
		try (TestSingleplayerContext sp2 = ctx.worldBuilder().create()) {
			waitT(80);
			boolean boundaryFirst = ctx.computeOnClient(c -> {
				var l = ((ChatComponentAccessorSpike) c.gui.getChat()).cpx$getAllMessages();
				return !l.isEmpty() && Boundary.isBoundaryLine(l.get(0).content());
			});
			res("V-20m.boundary", boundaryFirst, "note", "boundary line at top after world switch with swapped list");
		}
		waitT(40);
		String log = Files.exists(Path.of("logs", "chatlog.json")) ? Files.readString(Path.of("logs", "chatlog.json")) : "";
		int swapped = count(log, "swapped-in cpx#SWAP"); // exact text (cpx#SWAPDUPE live messages are legitimately logged)
		res("V-20m.noRelog", !log.isEmpty() && swapped == 0, "swapTokenOccurrencesInChatlog", swapped, "chatlogBytes", log.length());
	}

	void v20m(TestSingleplayerContext sp) {
		Map<String, Object> r = ctx.computeOnClient(c -> {
			Map<String, Object> m = new LinkedHashMap<>();
			var chat = c.gui.getChat();
			var acc = (ChatComponentAccessorSpike) chat;
			List<GuiMessage> orig = acc.cpx$getAllMessages();
			int logBefore = ChatLog.messageCount();
			List<GuiMessage> copy = new ArrayList<>(orig);
			GuiMessage test = new GuiMessage(c.gui.getGuiTicks(), Component.literal("swapped-in cpx#SWAP"), null, null);
			copy.add(Math.min(5, copy.size()), test);
			acc.cpx$setAllMessages(copy);
			chat.rescaleChat();
			m.put("swapIdentity", acc.cpx$getAllMessages() == copy);
			m.put("linesRebuilt", acc.cpx$getTrimmedMessages().stream().anyMatch(l -> true));
			// swap-back identity
			acc.cpx$setAllMessages(orig);
			m.put("swapBackIdentity", acc.cpx$getAllMessages() == orig);
			acc.cpx$setAllMessages(copy);
			chat.rescaleChat();
			m.put("logUnchangedBySwap", ChatLog.messageCount() == logBefore);
			// Chat Patches delete (ContextMenu's code path) on a real message in the swapped list
			int idx = copy.indexOf(test) + 1;
			String victim = copy.get(idx).content().getString();
			int logPre = ChatLog.messageCount();
			ChatUtil.deleteMessage(idx, false);
			m.put("deleteRemovedFromSwappedList", acc.cpx$getAllMessages() == copy && copy.stream().noneMatch(g -> g.content().getString().equals(victim)));
			m.put("deleteRemovedFromChatLog", ChatLog.messageCount() == logPre - 1);
			return m;
		});
		// condensation on the swapped list
		String dupe = "post-swap dupe cpx#SWAPDUPE";
		sys(sp, dupe);
		waitT(5);
		sys(sp, dupe);
		waitT(8);
		var ce = last(events("condense", "cpx#SWAPDUPE"));
		boolean condensedOnNewList = ce != null && ((Number) ce.get("truthRemoved")).intValue() >= 1;
		// Chat Patches search sees the swapped-in entry
		ctx.setScreen(() -> new ChatScreen("", false));
		waitT(5);
		boolean searchFinds = ctx.computeOnClient(c -> {
			ChatScreen s = (ChatScreen) c.screen;
			SpikeAdapter.searchFieldOf(s).setValue("cpx#SWAP");
			boolean found = ((ChatScreenAccess) s).getSearchResults().stream().anyMatch(g -> g.content().getString().contains("cpx#SWAP"));
			SpikeAdapter.searchFieldOf(s).setValue("");
			return found;
		});
		ctx.setScreen(() -> null);
		waitT(3);
		boolean pass = Boolean.TRUE.equals(r.get("swapIdentity")) && Boolean.TRUE.equals(r.get("swapBackIdentity"))
				&& Boolean.TRUE.equals(r.get("logUnchangedBySwap")) && Boolean.TRUE.equals(r.get("deleteRemovedFromSwappedList"))
				&& Boolean.TRUE.equals(r.get("deleteRemovedFromChatLog")) && condensedOnNewList && searchFinds;
		res("V-20m", pass, "details", r, "condensedOnSwappedList", condensedOnNewList, "searchFindsSwappedEntry", searchFinds);
	}

	// ------------------------------------------------------------------ S1c: clear sync, fault injection
	void s1c() {
		for (boolean vanillaClearing : new boolean[]{false, true}) {
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				waitT(40);
				ctx.runOnClient(c -> ChatPatches.config.vanillaClearing = vanillaClearing);
				for (int i = 0; i < 5; i++) sys(sp, "before f3d " + i + " cpx#K" + i);
				waitT(10);
				int n = SpikeLog.of("clear").size();
				ctx.getInput().holdKey(InputConstants.KEY_F3);
				ctx.getInput().pressKey(InputConstants.KEY_D);
				ctx.getInput().releaseKey(InputConstants.KEY_F3);
				waitT(10);
				var f3d = SpikeLog.of("clear").stream().skip(n).toList();
				res("V-06.f3d.vanillaClearing=" + vanillaClearing, !f3d.isEmpty() && f3d.stream().allMatch(e -> !Boolean.FALSE.equals(e.get("ok"))), "events", f3d);
				for (int i = 0; i < 5; i++) sys(sp, "before disconnect " + i + " cpx#J" + i);
				waitT(10);
				SpikeLog.ev("marker", "before_disconnect", vanillaClearing);
				if (!vanillaClearing) {
					// V-36 (partial): Class S fault -> degraded mode, no hiding
					ctx.runOnClient(c -> { SpikeAdapter.FAULTS.add(SpikeAdapter.Member.RESTORED_TAG); SpikeAdapter.selfCheck(); });
					sys(sp, "[HIDE] during degraded cpx#D1");
					waitT(8);
					var d = last(events("deliver", "cpx#D1"));
					res("V-36.classS", SpikeAdapter.degraded && d != null && Boolean.FALSE.equals(d.get("hidden")), "deliver", d);
					ctx.runOnClient(c -> { SpikeAdapter.FAULTS.clear(); SpikeAdapter.degraded = false; });
				}
			}
			waitT(30);
			var after = SpikeLog.of("clear").stream().filter(e -> Boolean.TRUE.equals(e.get("history"))).toList();
			res("V-06.disconnect.vanillaClearing=" + vanillaClearing, !after.isEmpty() && after.stream().allMatch(e -> !Boolean.FALSE.equals(e.get("ok"))), "events", after);
			SpikeLog.ev("marker", "after_disconnect", vanillaClearing);
		}
	}

	// ------------------------------------------------------------------ V-59 conflict variants
	void s1conflict() {
		ctx.runOnClient(c -> {
			List<String> fields = new ArrayList<>();
			for (var f : ChatScreen.class.getDeclaredFields()) {
				if (!f.getName().toLowerCase().contains("searchbar")) continue;
				String val;
				try { f.setAccessible(true); val = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? String.valueOf(f.get(null)) : "(instance)"; }
				catch (Exception e) { val = "unreadable"; }
				fields.add(f.getName() + ":" + f.getType().getSimpleName() + "=" + val);
			}
			SpikeLog.ev("chatscreen_searchbar_fields", "fields", fields);
		});
		ctx.runOnClient(c -> {
			Boolean shown = SpikeAdapter.searchBarShown();
			res("V-59.4." + mode, true, "reflectionStatus", SpikeAdapter.reflectionStatus, "searchBarShownRead", String.valueOf(shown),
					"searchIntegrationOff", SpikeAdapter.searchIntegrationOff,
					"note", "Chat Patches default showSearchBar=true; a 'false' read with status ok means the wrong field was bound");
		});
	}

	// ------------------------------------------------------------------ S2: rendering, layering, input
	void s2() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			for (int i = 0; i < 8; i++) sys(sp, "main chat line " + i + " lorem ipsum dolor sit amet cpx#M" + i);
			waitT(20);

			// V-55 / V-39: rendering-path ownership and gating
			SpikeWindow.x = 200; SpikeWindow.y = 20;
			SpikeWindow.trackFrames = true;
			int a0 = SpikeWindow.frameAnomalies.get();
			for (int i = 0; i < 40; i++) { ctx.setScreen(() -> new ChatScreen("", false)); waitT(1 + (i % 2)); ctx.setScreen(() -> null); waitT(1 + (i % 3)); }
			ctx.runOnClient(c -> c.options.hideGui = true);
			for (int i = 0; i < 20; i++) { ctx.setScreen(() -> new ChatScreen("", false)); waitT(2); ctx.setScreen(() -> null); waitT(2); }
			ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.HIDDEN); });
			for (int i = 0; i < 10; i++) { ctx.setScreen(() -> new ChatScreen("", false)); waitT(2); ctx.setScreen(() -> null); waitT(2); }
			ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
			SpikeWindow.enabled = false;
			for (int i = 0; i < 5; i++) { ctx.setScreen(() -> new ChatScreen("", false)); waitT(2); ctx.setScreen(() -> null); waitT(2); }
			SpikeWindow.enabled = true;
			SpikeWindow.trackFrames = false;
			res("V-55+V-39", SpikeWindow.frameAnomalies.get() == a0 && SpikeWindow.framesChecked.get() > 100,
					"framesChecked", SpikeWindow.framesChecked.get(), "anomalies", SpikeWindow.frameAnomalies.get() - a0);

			// open chat for layering scenes
			ctx.setScreen(() -> new ChatScreen("", false));
			waitT(10);
			Path shots = Files.createDirectories(Path.of("cpx-evidence", "screens"));

			layering("input", () -> ctx.computeOnClient(c -> {
				EditBox in = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input();
				int cx = in.getX() + c.font.width(in.getValue().substring(0, in.getCursorPosition()));
				// drawn background = ChatScreen.render fill(2, h-14, w-2, h-2); cursor cell excluded (blinks between captures)
				return new int[]{2, c.screen.height - 14, c.screen.width - 4, 12, cx - 1, in.getY() - 2, 8, 14};
			}), shots);
			layering("main_chat", () -> mainChatRect(), shots);
			layering("cp_search_field", () -> ctx.computeOnClient(c -> {
				// drawn background = Chat Patches fill(SEARCH_X-2, h-33, w*0.31, h-21)
				int w = c.screen.width, h = c.screen.height;
				return new int[]{20, h - 33, (int) (w * 0.31) - 20, 12};
			}), shots);

			// V-07: tooltip same frame + suppression
			placeUnobstructed();
			int tBefore = SpikeWindow.tooltipDrawFrames.size();
			cursorGui(SpikeWindow.x + 6, SpikeWindow.y + 55);
			waitT(4);
			boolean drawn = SpikeWindow.tooltipDrawFrames.size() > tBefore;
			boolean sameFrame = SpikeWindow.tooltipDrawFrames.stream().anyMatch(SpikeWindow.deferredTooltipFrames::contains);
			ctx.takeScreenshot("v07-tooltip");
			int[] in = ctx.computeOnClient(c -> { EditBox i = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input(); return new int[]{i.getX(), i.getY()}; });
			SpikeWindow.x = in[0] + 30; SpikeWindow.y = in[1] - 54; // hover word now under the input box
			waitT(3);
			int t2 = SpikeWindow.tooltipDrawFrames.size();
			cursorGui(in[0] + 36, in[1] + 3);
			waitT(4);
			boolean suppressed = SpikeWindow.tooltipDrawFrames.size() == t2;
			res("V-07", drawn && sameFrame, "tooltipDrawn", drawn, "drawnInSameFrameAsDeferredPass", sameFrame);
			res("V-08", suppressed, "noTooltipUnderInputBox", suppressed);

			// V-57: event cancellation
			placeUnobstructed();
			ctx.getInput().typeChars("abc");
			waitT(2);
			String[] inputBefore = ctx.computeOnClient(c -> { EditBox i = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input(); return new String[]{i.getValue(), String.valueOf(i.getCursorPosition()), String.valueOf(i.isFocused())}; });
			int vc0 = SpikeWindow.vanillaClicks.get(), cc0 = InputSpike.consumedClicks.get();
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_LEFT);
			int vc1 = SpikeWindow.vanillaClicks.get(), cc1 = InputSpike.consumedClicks.get();
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_RIGHT);
			int vc2 = SpikeWindow.vanillaClicks.get();
			boolean noMenu = ctx.computeOnClient(c -> { var m = ((ChatScreenAccess) c.screen).getContextMenu(); return m == null || !m.isFunctional(); });
			String[] inputAfter = ctx.computeOnClient(c -> { EditBox i = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input(); return new String[]{i.getValue(), String.valueOf(i.getCursorPosition()), String.valueOf(i.isFocused())}; });
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_MIDDLE);
			int vc3 = SpikeWindow.vanillaClicks.get();
			int[] wh = ctx.computeOnClient(c -> new int[]{c.screen.width, c.screen.height});
			clickGui(wh[0] - 20, 12, InputConstants.MOUSE_BUTTON_LEFT); // empty area (above the window, right of chat)
			int vc4 = SpikeWindow.vanillaClicks.get();
			int vs0 = SpikeWindow.vanillaScrolls.get(), cs0 = InputSpike.consumedScrolls.get();
			cursorGui(SpikeWindow.x + 40, SpikeWindow.y + 30); ctx.getInput().scroll(1); waitT(2);
			int vs1 = SpikeWindow.vanillaScrolls.get(), cs1 = InputSpike.consumedScrolls.get();
			int[] mc = mainChatRect();
			cursorGui(mc[0] + 5, mc[1] + 2); ctx.getInput().scroll(1); waitT(2);
			int vs2 = SpikeWindow.vanillaScrolls.get();
			res("V-57", vc1 == vc0 && cc1 == cc0 + 1 && vc2 == vc1 && noMenu && vc3 == vc2 + 1 && vc4 == vc3 + 1
							&& vs1 == vs0 && cs1 == cs0 + 1 && vs2 == vs1 + 1,
					"leftConsumedVanillaDelta", vc1 - vc0, "rightConsumedVanillaDelta", vc2 - vc1, "noContextMenuAfterRight", noMenu,
					"middlePassedVanillaDelta", vc3 - vc2, "emptyAreaVanillaDelta", vc4 - vc3,
					"scrollWindowVanillaDelta", vs1 - vs0, "scrollMainChatVanillaDelta", vs2 - vs1);
			res("V-09c.focus", java.util.Arrays.equals(inputBefore, inputAfter), "before", inputBefore, "after", inputAfter);

			// V-58: foreign listener ordering
			int ours0 = SpikeLog.of("click").size();
			ForeignListener.behaviour = ForeignListener.Behaviour.CONSUME_EARLY;
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_LEFT);
			boolean oursSkipped = SpikeLog.of("click").size() == ours0 && !SpikeLog.of("foreign_consumed").isEmpty();
			ForeignListener.behaviour = ForeignListener.Behaviour.OBSERVE_EARLY;
			int cc5 = InputSpike.consumedClicks.get();
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_LEFT);
			boolean oursAfterObserver = InputSpike.consumedClicks.get() == cc5 + 1 && ForeignListener.observed > 0;
			ForeignListener.behaviour = ForeignListener.Behaviour.CONSUME_LATE;
			int fc = SpikeLog.of("foreign_consumed").size();
			clickGui(SpikeWindow.x + 40, SpikeWindow.y + 30, InputConstants.MOUSE_BUTTON_LEFT);
			boolean oursFirst = SpikeLog.of("foreign_consumed").size() == fc;
			ForeignListener.behaviour = ForeignListener.Behaviour.OFF;
			res("V-58", oursSkipped && oursAfterObserver && oursFirst, "earlyConsumerPreemptsExtended", oursSkipped,
					"extendedWorksAfterObserver", oursAfterObserver, "extendedPreemptsLateConsumer", oursFirst);

			// V-54: visual vs input recipient over main chat + input overlap
			v54(shots, mc);

			// V-09e drag capture + cancel on close (drag crosses the main-chat box: capture must hold)
			placeUnobstructed();
			int sx0 = SpikeWindow.x, sy0 = SpikeWindow.y;
			cursorGui(SpikeWindow.x + 20, SpikeWindow.y + 3);
			ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			waitT(2);
			cursorGui(sx0 - 60, sy0 + 40); // over main-chat lines
			waitT(3);
			boolean moved = SpikeWindow.x != sx0 || SpikeWindow.y != sy0;
			ctx.setScreen(() -> null); // close mid-drag
			waitT(2);
			ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			boolean cancelled = !SpikeLog.of("drag_cancelled_on_close").isEmpty() && !InputSpike.dragging;
			res("V-09e.capture+cancel", moved && cancelled, "movedDuringDrag", moved, "cancelledOnClose", cancelled);

			// V-53 remaining scenes with Chat Patches menus
			ctx.setScreen(() -> new ChatScreen("", false));
			waitT(8);
			ctx.getInput().typeChars("/");
			waitT(10);
			layering("command_suggestions", () -> ctx.computeOnClient(c -> {
				var cs = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$commandSuggestions();
				var list = ((S2AccessorsSpike.CommandSuggestionsAcc) cs).cpx$suggestions();
				if (list == null) return null;
				var r = ((S2AccessorsSpike.SuggestionsListAcc) list).cpx$rect();
				return new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()};
			}), shots);
			ctx.setScreen(() -> null);
			waitT(4);
			ctx.setScreen(() -> new ChatScreen("", false));
			waitT(8);
			clickGui(10, wh[1] - 27, InputConstants.MOUSE_BUTTON_RIGHT); // right-click search button -> settings panel
			waitT(6);
			layering("cp_settings_panel", () -> ctx.computeOnClient(c -> {
				boolean open = ((ChatScreenAccess) c.screen).isMouseOverSettingsMenu(10, c.screen.height - 113 + 10);
				return open ? new int[]{2, c.screen.height - 113, 146, 76} : null;
			}), shots);
			clickGui(10, wh[1] - 27, InputConstants.MOUSE_BUTTON_RIGHT); // close settings
			waitT(4);
			int[] line = mainChatRect();
			clickGui(line[0] + 10, line[1] + line[3] - 4, InputConstants.MOUSE_BUTTON_RIGHT); // open context menu on newest line
			waitT(6);
			layering("cp_context_menu", () -> ctx.computeOnClient(c -> contextMenuBounds((ChatScreen) c.screen)), shots);
			ctx.setScreen(() -> null);
			waitT(4);
		}
	}

	/** Diagnostic for the V-53 input/search-field failure: which stratum does each path draw into? */
	void s2probe() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			for (int i = 0; i < 8; i++) sys(sp, "main chat line " + i + " cpx#M" + i);
			waitT(10);
			ctx.setScreen(() -> new ChatScreen("", false));
			waitT(10);
			Path shots = Files.createDirectories(Path.of("cpx-evidence", "screens"));
			for (String path : new String[]{"screen", "hud"}) {
				SpikeWindow.path = path;
				layering("probe-" + path + "-input", () -> ctx.computeOnClient(c -> {
					EditBox in = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input();
					return new int[]{in.getX(), in.getY(), in.getWidth(), in.getHeight()};
				}), shots);
				layering("probe-" + path + "-main_chat", () -> mainChatRect(), shots);
				SpikeLog.ev("strata", "path", path, "windowStratum", SpikeWindow.windowStratum, "chatScreenRenderStratum", SpikeWindow.chatRenderStratum);
			}
			SpikeWindow.path = "screen";
			ctx.setScreen(() -> null);
			waitT(4);
		}
	}

	/** Places a narrow window in an area no protected element covers (right of the main-chat box, above the input row). */
	void placeUnobstructed() {
		int[] mc = mainChatRect();
		int sw = ctx.computeOnClient(c -> c.screen != null ? c.screen.width : c.getWindow().getGuiScaledWidth());
		SpikeWindow.w = 90; SpikeWindow.h = 60;
		SpikeWindow.x = Math.min(sw - SpikeWindow.w - 2, (mc == null ? 0 : mc[0] + mc[2]) + 6);
		SpikeWindow.y = 30;
		waitT(2);
	}

	int[] mainChatRect() {
		return ctx.computeOnClient(c -> {
			var acc = (ChatComponentAccess) c.gui.getChat();
			int h = c.getWindow().getGuiScaledHeight();
			int minY = Integer.MAX_VALUE, maxY = -1;
			for (int y = 0; y < h; y++) if (acc.getEoEIndex(10, y) >= 0) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
			if (maxY < 0) return null;
			int maxX = 0;
			for (int x = 0; x < c.getWindow().getGuiScaledWidth(); x++) if (acc.getEoEIndex(x, maxY) >= 0) maxX = x;
			return new int[]{0, minY, maxX + 1, maxY - minY + 1};
		});
	}

	/** Drawn area of Chat Patches' context menu = union of its button widgets (isMouseOver's box is larger than what it draws). */
	static int[] contextMenuBounds(ChatScreen s) {
		var m = ((ChatScreenAccess) s).getContextMenu();
		if (m == null || !m.isFunctional()) return null;
		int minX = 9999, minY = 9999, maxX = -1, maxY = -1;
		for (var child : s.children()) {
			if (!(child instanceof net.minecraft.client.gui.components.Button b) || !b.visible) continue;
			if (!m.isMouseOver(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0)) continue;
			if (b.getX() == 2 && b.getY() == s.height - 35 && b.getWidth() == 16) continue; // Chat Patches' search button, not a menu entry
			minX = Math.min(minX, b.getX()); minY = Math.min(minY, b.getY());
			maxX = Math.max(maxX, b.getX() + b.getWidth()); maxY = Math.max(maxY, b.getY() + b.getHeight());
		}
		return maxX < 0 ? null : new int[]{minX, minY, maxX - minX, maxY - minY};
	}

	interface RectSupplier { int[] get() throws Exception; }

	/** V-53: window behind a protected element. C = window disabled, A = stratum on, B = stratum off (diagnostic). */
	void layering(String name, RectSupplier rectSupplier, Path shots) throws Exception {
		int[] r = rectSupplier.get();
		if (r == null) { res("V-53." + name, false, "error", "protected element not present"); return; }
		SpikeWindow.x = Math.max(0, r[0] + r[2] / 2 - SpikeWindow.w / 2);
		SpikeWindow.y = Math.max(0, r[1] + r[3] / 2 - SpikeWindow.h / 2);
		SpikeWindow.enabled = false;
		waitT(3);
		BufferedImage c = ImageIO.read(ctx.takeScreenshot("v53-" + name + "-C").toFile());
		SpikeWindow.enabled = true;
		SpikeWindow.stratum = true;
		waitT(3);
		BufferedImage a = ImageIO.read(ctx.takeScreenshot("v53-" + name + "-A").toFile());
		SpikeWindow.stratum = false;
		waitT(3);
		BufferedImage b = ImageIO.read(ctx.takeScreenshot("v53-" + name + "-B").toFile());
		SpikeWindow.stratum = true;
		double scale = a.getWidth() / (double) ctx.computeOnClient(cl -> cl.getWindow().getGuiScaledWidth());
		int[] sa = scan(a, c, r, scale), sb = scan(b, c, r, scale); // r[4..7] (optional) = excluded cell
		// overlap of window and element (gui px) to ensure the scene is meaningful
		int ox = Math.max(0, Math.min(r[0] + r[2], SpikeWindow.x + SpikeWindow.w) - Math.max(r[0], SpikeWindow.x));
		int oy = Math.max(0, Math.min(r[1] + r[3], SpikeWindow.y + SpikeWindow.h) - Math.max(r[1], SpikeWindow.y));
		res("V-53." + name, ox * oy > 0 && sa[0] == 0 && sa[1] == 0, "rect", r, "overlapGuiPx", ox * oy,
				"A_pureWindowPixelsInElement", sa[0], "A_elementTextPixelsCovered", sa[1], "A_elementTextPixels", sa[2],
				"B_pureWindowPixelsInElement", sb[0], "B_elementTextPixelsCovered", sb[1]);
	}

	/** returns {pure window-colour pixels inside element, element text pixels (bright in C) now window-coloured, element text pixels}. */
	static int[] scan(BufferedImage img, BufferedImage ref, int[] r, double scale) {
		int pure = 0, covered = 0, text = 0;
		int x0 = (int) Math.floor(r[0] * scale), y0 = (int) Math.floor(r[1] * scale);
		int x1 = Math.min(img.getWidth(), (int) Math.ceil((r[0] + r[2]) * scale)), y1 = Math.min(img.getHeight(), (int) Math.ceil((r[1] + r[3]) * scale));
		for (int y = Math.max(0, y0); y < y1; y++) for (int x = Math.max(0, x0); x < x1; x++) {
			if (r.length >= 8 && x >= r[4] * scale && x < (r[4] + r[6]) * scale && y >= r[5] * scale && y < (r[5] + r[7]) * scale) continue;
			int p = img.getRGB(x, y), q = ref.getRGB(x, y);
			boolean pureWin = near(p, SpikeWindow.BG) || near(p, SpikeWindow.TEXT);
			if (pureWin) pure++;
			if (bright(q)) { text++; if (pureWin) covered++; }
		}
		return new int[]{pure, covered, text};
	}

	static boolean near(int p, int c) {
		int dr = Math.abs(((p >> 16) & 255) - ((c >> 16) & 255)), dg = Math.abs(((p >> 8) & 255) - ((c >> 8) & 255)), db = Math.abs((p & 255) - (c & 255));
		return dr <= 10 && dg <= 10 && db <= 10;
	}

	static boolean bright(int p) {
		return ((p >> 16) & 255) > 200 && ((p >> 8) & 255) > 200 && (p & 255) > 200;
	}

	/** V-54: sample a grid; the visually topmost thing (screenshot) must match the input decision. */
	void v54(Path shots, int[] mcRect) throws Exception {
		int[] in = ctx.computeOnClient(c -> { EditBox i = ((S2AccessorsSpike.ChatScreenAcc) c.screen).cpx$input(); return new int[]{i.getX(), i.getY(), i.getWidth(), i.getHeight()}; });
		SpikeWindow.w = 220; SpikeWindow.h = 60;
		SpikeWindow.x = 20;
		SpikeWindow.y = in[1] - SpikeWindow.h + 8; // overlaps the newest main-chat lines and the input box
		waitT(4);
		BufferedImage a = ImageIO.read(ctx.takeScreenshot("v54-grid").toFile());
		double scale = a.getWidth() / (double) ctx.computeOnClient(cl -> cl.getWindow().getGuiScaledWidth());
		int mismatches = 0, samples = 0;
		List<String> bad = new ArrayList<>();
		for (int gy = 0; gy < 6; gy++) for (int gx = 0; gx < 10; gx++) {
			int px = SpikeWindow.x + 5 + gx * (SpikeWindow.w - 10) / 9;
			int py = SpikeWindow.y + 4 + gy * (SpikeWindow.h - 8) / 5;
			int sx = (int) ((px + 0.5) * scale), sy = (int) ((py + 0.5) * scale); // centre of the GUI pixel that receives the click
			int pc = a.getRGB(Math.min(sx, a.getWidth() - 1), Math.min(sy, a.getHeight() - 1));
			boolean visualWindow = near(pc, SpikeWindow.BG) || near(pc, SpikeWindow.TEXT);
			int before = InputSpike.consumedClicks.get();
			clickGui(px + 0.5, py + 0.5, InputConstants.MOUSE_BUTTON_LEFT); // click the same pixel centre that was sampled
			boolean consumed = InputSpike.consumedClicks.get() > before;
			samples++;
			if (visualWindow != consumed) { mismatches++; bad.add(px + "," + py + " visualWindow=" + visualWindow + " consumed=" + consumed); }
		}
		res("V-54", mismatches == 0, "samples", samples, "mismatches", mismatches, "badPoints", bad);
	}

	void cursorGui(double gx, double gy) {
		double[] p = ctx.computeOnClient(c -> {
			var w = c.getWindow();
			return new double[]{gx * w.getScreenWidth() / (double) w.getGuiScaledWidth(), gy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
		});
		ctx.getInput().setCursorPos(p[0], p[1]); // must run on the gametest thread
		waitT(1);
	}

	void clickGui(double gx, double gy, int button) {
		cursorGui(gx, gy);
		ctx.getInput().pressMouse(button);
		waitT(2);
	}

	// ------------------------------------------------------------------ evidence parsing
	@SuppressWarnings("unchecked")
	static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

	static Map<String, Object> normalize(Map<String, Object> m) {
		if (m == null) return null;
		Map<String, Object> n = new LinkedHashMap<>();
		for (var e : m.entrySet()) n.put(e.getKey(), e.getValue() instanceof Number num ? num.doubleValue() : e.getValue());
		return n;
	}

	static Map<String, Map<String, Object>> readStructures(Path p, String origin) throws Exception {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		if (!Files.exists(p)) return out;
		var gson = new com.google.gson.Gson();
		for (String line : Files.readAllLines(p)) {
			Map<String, Object> e = gson.fromJson(line, Map.class);
			if ("structure".equals(e.get("t")) && origin.equals(e.get("origin"))) out.put((String) e.get("token"), castMap(e.get("tree")));
		}
		return out;
	}

	static Integer readS1aLogged(Path p) throws Exception {
		if (!Files.exists(p)) return null;
		var gson = new com.google.gson.Gson();
		Integer v = null;
		for (String line : Files.readAllLines(p)) {
			Map<String, Object> e = gson.fromJson(line, Map.class);
			if ("s1a_logged".equals(e.get("t"))) v = ((Number) e.get("chatLogMessageCount")).intValue();
		}
		return v;
	}

	static int count(String s, String needle) {
		int n = 0, i = 0;
		while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
		return n;
	}
}
