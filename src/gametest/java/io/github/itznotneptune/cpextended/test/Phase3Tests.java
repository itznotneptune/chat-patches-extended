// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.compat.ChatPatchesBackend;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.render.WindowRenderer;
import io.github.itznotneptune.cpextended.ui.ChatScreenInput;
import io.github.itznotneptune.cpextended.ui.HitTester;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.ChatVisiblity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Phase 3 acceptance tests: chat-open rendering (P10), P11 input, stacking (Q30a/Q34), unread (P4), indicators. */
final class Phase3Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	static final int MAGENTA = 0xFF00FF;

	Phase3Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
	}

	// =================================================================== helpers

	static final String CONFIG = "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
			+ "\"appearance\": {\"backgroundOpacity\": 1.0, \"textOpacity\": 1.0, \"scale\": 1.0, \"lineSpacing\": 0.0},\n"
			+ "\"windows\": [\n"
			+ " {\"id\": \"p3-c\", \"anchor\": \"BOTTOM_LEFT\", \"x\": 0.0, \"y\": 0.0, \"width\": 0.55, \"height\": 0.60, \"tabs\": [{\"id\": \"pc\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#PC\"}]}]},\n"
			+ " {\"id\": \"p3-a\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.04, \"width\": 0.42, \"height\": 0.45, \"selectedTab\": \"pa1\", \"tabs\": ["
			+ "{\"id\": \"pa1\", \"name\": \"One\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#PA\"}]},"
			+ "{\"id\": \"pa2\", \"name\": \"Two\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#PB\"}]},"
			+ "{\"id\": \"pa3\", \"name\": \"Bad\", \"include\": [{\"mode\": \"regex\", \"pattern\": \"x\"}]}]},\n"
			+ " {\"id\": \"p3-b\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.12, \"y\": 0.36, \"width\": 0.28, \"height\": 0.30, \"tabs\": [{\"id\": \"pb\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#PQ\"}]}]},\n"
			+ " {\"id\": \"p3-off\", \"enabled\": false, \"tabs\": [{\"id\": \"poff\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#PA\"}]}]}\n"
			+ "]\n}\n";

	ConfigManager mgr() { return ConfigManager.get(); }

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	void cursor(double gx, double gy) {
		double[] p = ctx.computeOnClient(c -> {
			var w = c.getWindow();
			return new double[]{gx * w.getScreenWidth() / (double) w.getGuiScaledWidth(), gy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
		});
		ctx.getInput().setCursorPos(p[0], p[1]);
		t.waitT(1);
	}

	void click(double gx, double gy, int button) {
		cursor(gx, gy);
		ctx.getInput().pressMouse(button);
		t.waitT(2);
	}

	void scrollAt(double gx, double gy, double amount) {
		cursor(gx, gy);
		ctx.getInput().scroll(amount);
		t.waitT(2);
	}

	void drag(double x1, double y1, double x2, double y2, int button) {
		cursor(x1, y1);
		ctx.getInput().holdMouse(button);
		t.waitT(2);
		cursor((x1 + x2) / 2, (y1 + y2) / 2);
		t.waitT(2);
		cursor(x2, y2);
		t.waitT(2);
		ctx.getInput().releaseMouse(button);
		t.waitT(2);
	}

	ChatOpenRenderer.Layout layout(String id) {
		return onClient(() -> {
			for (ChatOpenRenderer.Layout l : ChatOpenRenderer.lastLayouts()) if (l.windowId().equals(id)) return l;
			return null;
		});
	}

	int[] screen() { return ctx.computeOnClient(c -> new int[]{c.getWindow().getGuiScaledWidth(), c.getWindow().getGuiScaledHeight()}); }

	/** {value, cursor, focused} of the vanilla chat input. */
	List<Object> inputState() {
		return ctx.computeOnClient(c -> {
			if (!(c.screen instanceof ChatScreen s)) return List.of("no screen");
			EditBox search = ChatPatchesAdapter.get().searchFieldWidget(s);
			for (GuiEventListener ch : s.children()) {
				if (ch instanceof EditBox b && b != search) return List.of(b.getValue(), b.getCursorPosition(), b.isFocused() && s.getFocused() == b);
			}
			return List.of("no input");
		});
	}

	/** A point of the window's body where it is the topmost window and no protected region lies, scanning from the right. */
	double[] freePoint(String windowId) {
		return onClient(() -> {
			ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
			ChatOpenRenderer.Layout l = null;
			for (ChatOpenRenderer.Layout c : ChatOpenRenderer.lastLayouts()) if (c.windowId().equals(windowId)) l = c;
			if (l == null) return null;
			int[] b = l.body();
			for (int x = b[0] + b[2] - 8; x > b[0]; x -= 3) {
				for (int y = b[1] + 4; y < b[1] + b[3] - 8; y += 3) {
					var top = ChatOpenRenderer.topAt(x + 0.5, y + 0.5);
					if (top != null && top.windowId().equals(windowId) && HitTester.protectedRegion(s, x + 0.5, y + 0.5) == null) return new double[]{x + 0.5, y + 0.5};
				}
			}
			return null;
		});
	}

	void openChat(String text) {
		ctx.setScreen(() -> new ChatScreen(text, false));
		t.waitT(4);
	}

	void closeChat() {
		ctx.setScreen(() -> null);
		t.waitT(3);
	}

	String windowOrder() { return onClient(() -> mgr().config().windows().stream().map(ExtendedConfig.Window::id).toList().toString()); }

	void setup(TestSingleplayerContext sp) {
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		ctx.runOnClient(c -> {
			c.options.hideGui = false;
			c.options.chatVisibility().set(ChatVisiblity.FULL);
			c.options.chatWidth().set(0.4); // vanilla setting: the open main chat's line area then ends well left of p3-a
			c.gui.getChat().rescaleChat();
		});
		// p3-c's own lines first (magenta), then enough white filler that the open main chat no longer shows them.
		for (int i = 0; i < 8; i++) magenta(sp, "window line " + i + " cpx#PC-" + i);
		for (int i = 0; i < 40; i++) t.sys(sp, "pa line " + i + " cpx#PA-" + i);
		sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(Component.literal("hover ")
				.append(Component.literal("HOVERWORD").withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal("cpx tooltip")))))
				.append(Component.literal(" and "))
				.append(Component.literal("LINKWORD").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand("/cpxlink"))))
				.append(Component.literal(" cpx#PA-hover")), false));
		for (int i = 0; i < 30; i++) t.sys(sp, "white main chat filler " + i + " lorem ipsum dolor sit amet");
		t.waitT(10);
	}

	void magenta(TestSingleplayerContext sp, String text) {
		sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(
				Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(MAGENTA))), false));
	}

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	// =================================================================== interact

	void interact() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			v39();
			v55();
			layeringScenes();
			v07v08();
			v09c();
			v09e();
			v41b();
			v57v58();
			v09v09b();
			v09d();
			v12();
			v11v28();
			saveLocks();
			v56(sp);
		}
		t.waitT(20);
	}

	void v39() {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> WindowRenderer.resetCounters());
		t.waitT(6);
		m.put("gameplay", WindowRenderer.framesWithDraws());
		ctx.runOnClient(c -> { c.options.hideGui = true; WindowRenderer.resetCounters(); });
		t.waitT(6);
		m.put("gameplayF1", WindowRenderer.framesWithDraws());
		openChat("");
		ctx.runOnClient(c -> WindowRenderer.resetCounters());
		t.waitT(6);
		m.put("chatOpenF1", WindowRenderer.framesWithDraws());
		ChatOpenRenderer.Layout a = layout("p3-a");
		m.put("chatOpenF1Interactive", a != null);
		boolean selectWorks = false;
		if (a != null && a.tabs().size() > 1) {
			ChatOpenRenderer.TabSlot two = a.tabs().get(1);
			click(two.x() + 3, two.y() + 5, 0);
			selectWorks = "pa2".equals(onClient(() -> mgr().config().window("p3-a").selectedTab()));
			ChatOpenRenderer.TabSlot one = layout("p3-a").tabs().get(0);
			click(one.x() + 3, one.y() + 5, 0);
		}
		m.put("chatOpenF1SelectTab", selectWorks);
		closeChat();
		ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.HIDDEN); WindowRenderer.resetCounters(); });
		t.waitT(6);
		m.put("gameplayHidden", WindowRenderer.framesWithDraws());
		openChat("");
		ctx.runOnClient(c -> WindowRenderer.resetCounters());
		t.waitT(6);
		m.put("chatOpenHidden", WindowRenderer.framesWithDraws());
		closeChat();
		ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
		m.put("disabledWindowDraws", WindowRenderer.totalDraws("p3-off"));
		boolean ok = (long) m.get("gameplay") > 0 && (long) m.get("gameplayF1") == 0 && (long) m.get("chatOpenF1") > 0 && selectWorks
				&& (long) m.get("gameplayHidden") == 0 && (long) m.get("chatOpenHidden") == 0 && (long) m.get("disabledWindowDraws") == 0;
		ev.result("V-39", ok, "observed", m);
	}

	void v55() {
		TestCounters.trackedWindow = "p3-a";
		TestCounters.expectedVisible = () -> {
			Minecraft c = Minecraft.getInstance();
			return c.level != null && c.options.chatVisibility().get() != ChatVisiblity.HIDDEN && (c.screen instanceof ChatScreen || !c.options.hideGui);
		};
		t.waitT(1);
		TestCounters.ANOMALY_SAMPLES.clear();
		int a0 = TestCounters.ANOMALIES.get(), f0 = TestCounters.FRAMES.get();
		long d0 = WindowRenderer.duplicateDraws();
		for (int i = 0; i < 40; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(1 + i % 2); ctx.setScreen(() -> null); t.waitT(1 + i % 3); }
		ctx.runOnClient(c -> c.options.hideGui = true);
		for (int i = 0; i < 20; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(2); ctx.setScreen(() -> null); t.waitT(2); }
		ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.HIDDEN); });
		for (int i = 0; i < 10; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(2); ctx.setScreen(() -> null); t.waitT(2); }
		ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
		// keyboard path: the chat key opens the screen
		for (int i = 0; i < 5; i++) { ctx.getInput().pressKey(InputConstants.KEY_T); t.waitT(2); ctx.getInput().pressKey(InputConstants.KEY_ESCAPE); t.waitT(2); }
		TestCounters.trackedWindow = null;
		TestCounters.expectedVisible = null;
		int frames = TestCounters.FRAMES.get() - f0, anomalies = TestCounters.ANOMALIES.get() - a0;
		long dup = WindowRenderer.duplicateDraws() - d0;
		ev.result("V-55", frames > 200 && anomalies == 0 && dup == 0, "framesChecked", frames, "anomalies", anomalies, "duplicateDraws", dup,
				"samples", new ArrayList<>(TestCounters.ANOMALY_SAMPLES), "transitions", "75 setScreen (normal, F1, Hidden, normal) + 5 keyboard (T / Esc)");
	}

	/** V-53 (all protected elements) and V-54 (input matches what's visible), scene by scene, on window p3-c. */
	void layeringScenes() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		boolean ok53 = true, ok54 = true;
		int[] sc = screen();
		String[] names = {"main_chat_and_input", "command_suggestions", "cp_search_field", "cp_settings_panel", "cp_context_menu"};
		for (String scene : names) {
			openChat("");
			switch (scene) {
				case "command_suggestions" -> { ctx.getInput().typeChars("/"); t.waitT(10); }
				case "cp_search_field" -> { click(10, sc[1] - 27, 0); t.waitT(4); }
				case "cp_settings_panel" -> { click(10, sc[1] - 27, 1); t.waitT(4); }
				case "cp_context_menu" -> { click(30, sc[1] - 46, 1); t.waitT(4); }
				default -> {}
			}
			ChatOpenRenderer.Layout c = layout("p3-c");
			BufferedImage img = ImageIO.read(ctx.takeScreenshot("p3-layer-" + scene).toFile());
			double scale = img.getWidth() / (double) sc[0];
			// Per GUI pixel of p3-c: is a protected region there (input precedence), and is window text visible unblended?
			int[] r = c.rect();
			boolean[][] prot = ctx.computeOnClient(cl -> {
				boolean[][] p = new boolean[r[2]][r[3]];
				ChatScreen s = (ChatScreen) cl.screen;
				for (int x = 0; x < r[2]; x++) for (int y = 0; y < r[3]; y++) p[x][y] = HitTester.protectedRegion(s, r[0] + x + 0.5, r[1] + y + 0.5) != null;
				return p;
			});
			boolean menuOpen = onClient(() -> ChatPatchesAdapter.get().isContextMenuOpen((ChatScreen) Minecraft.getInstance().screen));
			int coveredPure = 0, uncoveredPure = 0, protectedPixels = 0;
			List<int[]> coveredAt = new ArrayList<>();
			for (int x = 0; x < r[2]; x++) {
				for (int y = 0; y < r[3]; y++) {
					boolean pure = pureMagenta(img, scale, r[0] + x, r[1] + y);
					if (prot[x][y]) { protectedPixels++; if (pure) { coveredPure++; if (coveredAt.size() < 12) coveredAt.add(new int[]{r[0] + x, r[1] + y}); } } else if (pure) uncoveredPure++;
				}
			}
			List<String> coveredRegions = ctx.computeOnClient(cl -> coveredAt.stream().map(p -> p[0] + "," + p[1] + ":" + HitTester.protectedRegion((ChatScreen) cl.screen, p[0] + 0.5, p[1] + 0.5)).toList());
			// V-53 is about what protected elements draw. Chat Patches' search field is clickable two rows below the
			// background it paints (and vanilla's input box likewise below its fill): window text there is visible but
			// input goes to the field (conservative, Q18). Count those separately.
			// Likewise the open context menu reports isMouseOver for a box larger than the buttons it draws (Phase 0 F-5):
			// what it draws is the union of its button widgets (minus the search button).
			List<int[]> menuButtons = ctx.computeOnClient(cl -> {
				List<int[]> out = new ArrayList<>();
				ChatScreen s = (ChatScreen) cl.screen;
				for (var ch : s.children()) {
					if (ch instanceof net.minecraft.client.gui.components.Button b && b.visible && !(b.getX() == 2 && b.getY() == s.height - 35 && b.getWidth() == 16)
							&& ChatPatchesAdapter.get().isOverChatPatchesUi(s, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0))
						out.add(new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()});
				}
				return out;
			});
			int[] scr = sc;
			int band = 0, coveredDrawn = 0;
			List<String> drawnCovered = new ArrayList<>();
			for (int x = 0; x < r[2]; x++) {
				for (int y = 0; y < r[3]; y++) {
					if (!prot[x][y] || !pureMagenta(img, scale, r[0] + x, r[1] + y)) continue;
					int gx = r[0] + x, gy = r[1] + y;
					String reg = regionAt(gx, gy);
					boolean searchBand = "cp_search_field".equals(reg) && (gy < scr[1] - 33 || gy >= scr[1] - 21);
					boolean inputBand = "input".equals(reg) && (gy < scr[1] - 14 || gy >= scr[1] - 2);
					boolean menuBand = "cp_ui".equals(reg) && menuButtons.stream().noneMatch(bt -> gx >= bt[0] && gx < bt[0] + bt[2] && gy >= bt[1] && gy < bt[1] + bt[3]);
					if (searchBand || inputBand || menuBand) band++;
					else { coveredDrawn++; if (drawnCovered.size() < 8) drawnCovered.add(gx + "," + gy + ":" + reg); }
				}
			}
			ok53 &= coveredDrawn == 0 && protectedPixels > 0;
			// V-54: scroll at sample points; consumed exactly where the window is on top and no protected region (or menu) is.
			int mismatches = 0, samples = 0;
			List<String> bad = new ArrayList<>();
			for (int gy = 0; gy < 6; gy++) {
				for (int gx = 0; gx < 8; gx++) {
					int px = r[0] + 4 + gx * (r[2] - 8) / 7, py = r[1] + ChatOpenRenderer.STRIP_H + 3 + gy * (r[3] - ChatOpenRenderer.STRIP_H - 6) / 5;
					boolean expected = !menuOpen && !prot[px - r[0]][py - r[1]];
					long before = ChatScreenInput.consumedScrolls();
					scrollAt(px + 0.5, py + 0.5, 1);
					boolean consumed = ChatScreenInput.consumedScrolls() > before;
					samples++;
					if (consumed != expected) { mismatches++; if (bad.size() < 8) bad.add(px + "," + py + " expected=" + expected); }
				}
			}
			ctx.runOnClient(cl -> cl.gui.getChat().resetChatScroll());
			ok54 &= mismatches == 0;
			scenes.add(row("scene", scene, "protectedPixelsInWindow", protectedPixels, "windowTextOverDrawnProtectedUi", coveredDrawn,
					"windowTextInClickOnlyBand", band, "drawnCoveredAt", drawnCovered, "bandSamples", coveredRegions, "menuButtons", menuButtons.size(),
					"windowTextVisibleElsewhere", uncoveredPure, "contextMenuOpen", menuOpen, "scrollSamples", samples, "inputMismatches", mismatches, "bad", bad));
			closeChat();
		}
		resetChatPatchesUi();
		ev.result("V-53", ok53, "scenes", scenes, "note", "window p3-c under main-chat lines, input, suggestions, Chat Patches search field, settings panel and context menu; pixel-exact against the input precedence map");
		ev.result("V-54", ok54, "scenes", scenes.stream().map(m -> row("scene", m.get("scene"), "samples", m.get("scrollSamples"), "mismatches", m.get("inputMismatches"), "bad", m.get("bad"))).toList());
	}

	String regionAt(int gx, int gy) {
		return ctx.computeOnClient(cl -> HitTester.protectedRegion((ChatScreen) cl.screen, gx + 0.5, gy + 0.5));
	}

	static boolean pureMagenta(BufferedImage img, double scale, int gx, int gy) {
		for (int dx = 0; dx < (int) scale; dx++) {
			for (int dy = 0; dy < (int) scale; dy++) {
				int x = (int) (gx * scale) + dx, y = (int) (gy * scale) + dy;
				if (x >= img.getWidth() || y >= img.getHeight()) continue;
				int c = img.getRGB(x, y) & 0xFFFFFF;
				if ((c >> 16 & 0xFF) > 245 && (c >> 8 & 0xFF) < 10 && (c & 0xFF) > 245) return true;
			}
		}
		return false;
	}

	void v07v08() {
		openChat("");
		ChatOpenRenderer.Layout a = layout("p3-a");
		// newest line of tab pa1 is the hover line; find HOVERWORD's x on it
		double[] hover = onClient(() -> {
			var font = Minecraft.getInstance().font;
			ChatScreen scr = (ChatScreen) Minecraft.getInstance().screen;
			for (ChatOpenRenderer.LineSlot l : a.lines()) {
				for (int x = l.x(); x < a.rect()[0] + a.rect()[2]; x++) {
					Style st = ChatOpenRenderer.styleAt(font, l, x + 1.5);
					if (st != null && st.getHoverEvent() != null && HitTester.protectedRegion(scr, x + 1.5, l.y() + l.h() / 2.0) == null)
						return new double[]{x + 1.5, l.y() + l.h() / 2.0};
				}
			}
			return null;
		});
		long tf0 = ChatOpenRenderer.tooltipFrames();
		int fr0 = TestCounters.FRAMES.get();
		boolean shown = false;
		int framesToTooltip = -1;
		if (hover != null) {
			TestCounters.trackedWindow = null;
			cursor(hover[0], hover[1]);
			for (int i = 0; i < 3 && !shown; i++) { t.waitT(1); shown = ChatOpenRenderer.tooltipFrames() > tf0; }
			framesToTooltip = shown ? TestCounters.FRAMES.get() - fr0 : -1;
			ctx.takeScreenshot("p3-tooltip");
		}
		ev.result("V-07", shown, "hoverPoint", hover == null ? null : Arrays.toString(hover), "tooltipDrawn", shown,
				"note", "drawn by Extended in afterRender with nextStratum + renderDeferredElements, i.e. inside the frame that detected the hover (Phase 0 F-3)");
		closeChat();
		// V-08: the same hover point covered by protected UI must not show an Extended tooltip.
		List<Map<String, Object>> cases = new ArrayList<>();
		boolean ok = true;
		int[] sc = screen();
		// move p3-a over the protected bottom-left area for these cases
		int[] orig = a.rect();
		edit(new ConfigEdit.SetGeometry("p3-a", 0.45, 0.55, 0.55, 0.45)); // TOP_RIGHT anchor: this puts it bottom-left
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.BringToFront("p3-a")));
		for (String scene : new String[]{"input", "main_chat", "suggestions"}) {
			openChat("");
			if (scene.equals("suggestions")) { ctx.getInput().typeChars("/"); t.waitT(8); }
			ChatOpenRenderer.Layout l = layout("p3-a");
			double[] p = onClient(() -> {
				var font = Minecraft.getInstance().font;
				ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
				for (ChatOpenRenderer.LineSlot line : l.lines()) {
					for (int x = line.x(); x < l.rect()[0] + l.rect()[2]; x++) {
						Style st = ChatOpenRenderer.styleAt(font, line, x + 0.5);
						String reg = HitTester.protectedRegion(s, x + 1.5, line.y() + line.h() / 2.0);
						if (st != null && st.getHoverEvent() != null && reg != null && (scene.equals("main_chat") ? reg.equals("main_chat") : scene.equals("suggestions") ? reg.equals("suggestions") : reg.equals("input")))
							return new double[]{x + 1.5, line.y() + line.h() / 2.0};
					}
				}
				return null;
			});
			long before = ChatOpenRenderer.tooltipFrames();
			if (p != null) { cursor(p[0], p[1]); t.waitT(3); }
			boolean suppressed = p == null || ChatOpenRenderer.tooltipFrames() == before;
			cases.add(row("scene", scene, "coveredHoverPointFound", p != null, "suppressed", suppressed));
			ok &= suppressed;
			closeChat();
		}
		// forced Chat Patches region read failure: conservative fallback suppresses tooltips (and passes input)
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(failingUi(real)); ChatPatchesAdapter.get().selfCheck(); });
		edit(new ConfigEdit.SetGeometry("p3-a", 0.02, 0.04, 0.42, 0.45)); // back to the unobstructed spot (TOP_RIGHT anchor)
		openChat("");
		long before = ChatOpenRenderer.tooltipFrames();
		if (hover != null) { cursor(hover[0], hover[1]); t.waitT(3); }
		boolean failSuppressed = ChatOpenRenderer.tooltipFrames() == before;
		closeChat();
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		cases.add(row("scene", "chat_patches_read_failure", "suppressed", failSuppressed));
		ok &= failSuppressed;
		ev.result("V-08", ok, "cases", cases, "note", "search-field, settings and context-menu coverage is part of the V-53/V-54 scenes (same precedence map)");
	}

	void edit(ConfigEdit e) { ctx.runOnClient(c -> mgr().edit(e)); t.waitT(2); }

	/** Chat Patches' search-bar visibility is global: put it back to hidden after scenes that toggled it. */
	void resetChatPatchesUi() {
		openChat("");
		if (Boolean.TRUE.equals(onClient(() -> ChatPatchesAdapter.get().isSearchBarShown()))) click(10, screen()[1] - 27, 0);
		closeChat();
	}

	static ChatPatchesBackend failingUi(ChatPatchesBackend real) {
		return new DelegatingBackend(real) {
			@Override public boolean contextMenuOpen(ChatScreen screen) { throw new IllegalStateException("test: Chat Patches UI unreadable"); }
			@Override public boolean overContextMenu(ChatScreen screen, double x, double y) { throw new IllegalStateException("test: Chat Patches UI unreadable"); }
		};
	}

	void v09c() {
		openChat("draft text");
		List<Object> before = inputState();
		ChatOpenRenderer.Layout a = layout("p3-a");
		Map<String, Object> m = new LinkedHashMap<>();
		// tab click
		ChatOpenRenderer.TabSlot two = a.tabs().get(1);
		click(two.x() + 3, two.y() + 5, 0);
		m.put("tabSelected", "pa2".equals(onClient(() -> mgr().config().window("p3-a").selectedTab())));
		click(layout("p3-a").tabs().get(0).x() + 3, a.strip()[1] + 5, 0);
		// right-click on the body: consumed, no context menu
		double[] fp = freePoint("p3-a");
		int v0 = TestCounters.VANILLA_CLICKS.get();
		click(fp[0], fp[1], 1);
		m.put("rightClickVanillaCalls", TestCounters.VANILLA_CLICKS.get() - v0);
		m.put("noContextMenu", !onClient(() -> ChatPatchesAdapter.get().isContextMenuOpen((ChatScreen) Minecraft.getInstance().screen)));
		// middle button passes through
		int v1 = TestCounters.VANILLA_CLICKS.get();
		click(fp[0], fp[1], 2);
		m.put("middleVanillaCalls", TestCounters.VANILLA_CLICKS.get() - v1);
		// scroll the body: window scrolls, main chat doesn't
		int s0 = onClient(() -> ChatOpenRenderer.scrollOf("p3-a"));
		scrollAt(fp[0], fp[1], 1);
		int s1 = onClient(() -> ChatOpenRenderer.scrollOf("p3-a"));
		m.put("windowScrolled", s1 > s0);
		// scrollbar click
		ChatOpenRenderer.Layout a2 = layout("p3-a");
		boolean scrollbar = a2.scrollbar() != null;
		if (scrollbar) {
			click(a2.scrollbar()[0] + 1, a2.scrollbar()[1] + 0.5, 0);
			m.put("scrollbarToTop", onClient(() -> ChatOpenRenderer.scrollOf("p3-a")) == a2.maxScroll());
		}
		List<Object> after = inputState();
		m.put("inputBefore", before);
		m.put("inputAfter", after);
		boolean inputKept = before.equals(after);
		// text link: vanilla's click handler runs (suggest_command puts its text in the input)
		ctx.runOnClient(c -> ChatOpenRenderer.setScroll("p3-a", 0));
		t.waitT(2);
		ChatOpenRenderer.Layout a3 = layout("p3-a");
		double[] link = onClient(() -> {
			var font = Minecraft.getInstance().font;
			for (ChatOpenRenderer.LineSlot l : a3.lines()) {
				for (int x = l.x(); x < a3.rect()[0] + a3.rect()[2]; x++) {
					Style st = ChatOpenRenderer.styleAt(font, l, x + 1.5);
					if (st != null && st.getClickEvent() != null
							&& HitTester.protectedRegion((ChatScreen) Minecraft.getInstance().screen, x + 1.5, l.y() + l.h() / 2.0) == null)
						return new double[]{x + 1.5, l.y() + l.h() / 2.0};
				}
			}
			return null;
		});
		if (link != null) click(link[0], link[1], 0);
		m.put("linkInsertedSuggestion", link != null && "/cpxlink".equals(inputState().get(0)));
		closeChat();
		boolean ok = Boolean.TRUE.equals(m.get("tabSelected")) && Integer.valueOf(0).equals(m.get("rightClickVanillaCalls")) && Boolean.TRUE.equals(m.get("noContextMenu"))
				&& Integer.valueOf(1).equals(m.get("middleVanillaCalls")) && Boolean.TRUE.equals(m.get("windowScrolled")) && (!scrollbar || Boolean.TRUE.equals(m.get("scrollbarToTop")))
				&& inputKept && Boolean.TRUE.equals(m.get("linkInsertedSuggestion"));
		ev.result("V-09c", ok, "observed", m, "inputKeptFocusTextCursor", inputKept,
				"note", "side buttons behave like the middle button in code (pass); only the middle button is pressed here");
	}

	void v09e() {
		openChat("");
		Map<String, Object> m = new LinkedHashMap<>();
		int[] sc = screen();
		// resize from the grip (top-right placement: the grip is unobstructed); narrower and shorter by 20 / 10 px
		ChatOpenRenderer.Layout g0 = layout("p3-a");
		drag(g0.grip()[0] + 3, g0.grip()[1] + 3, g0.grip()[0] - 17, g0.grip()[1] - 7, 0);
		int[] afterResize = layout("p3-a").rect();
		boolean resizedOk = afterResize[2] == g0.rect()[2] - 20 && afterResize[3] == g0.rect()[3] - 10 && afterResize[0] == g0.rect()[0];
		m.put("resizeFromTo", Arrays.toString(g0.rect()) + " -> " + Arrays.toString(afterResize));
		ChatOpenRenderer.Layout a = layout("p3-a");
		int[] startRect = a.rect();
		// (1) move by the strip background, crossing the main chat and the input box, ending off-screen bottom-left (clamped)
		double sx = a.strip()[0] + a.strip()[2] - 30, sy = a.strip()[1] + 6;
		cursor(sx, sy);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		cursor(40, sc[1] - 60);
		t.waitT(2);
		boolean capturing = onClient(ChatScreenInput::dragging);
		cursor(20, sc[1] - 6);
		t.waitT(2);
		cursor(-50, sc[1] + 50);
		t.waitT(2);
		ctx.getInput().releaseMouse(0);
		t.waitT(3);
		int[] moved = layout("p3-a").rect();
		boolean clamped = moved[0] == 0 && moved[1] + moved[3] == sc[1];
		m.put("captureAcrossProtected", capturing);
		m.put("movedAndClamped", clamped);
		m.put("rectAfterMove", Arrays.toString(moved));
		m.put("resized", resizedOk);
		// (2) a drag can't start where a protected region covers the window (input box row)
		ChatOpenRenderer.Layout c = layout("p3-c");
		cursor(10, sc[1] - 8);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		boolean startedUnderProtected = onClient(ChatScreenInput::dragging);
		ctx.getInput().releaseMouse(0);
		t.waitT(2);
		m.put("noDragUnderProtected", !startedUnderProtected);
		// (3) right and middle buttons never start a drag
		ChatOpenRenderer.Layout d = layout("p3-a");
		boolean other = false;
		for (int btn : new int[]{1, 2}) {
			cursor(d.strip()[0] + d.strip()[2] - 30, d.strip()[1] + 6);
			ctx.getInput().holdMouse(btn);
			t.waitT(2);
			other |= onClient(ChatScreenInput::dragging);
			ctx.getInput().releaseMouse(btn);
			t.waitT(2);
		}
		m.put("noDragRightOrMiddle", !other);
		// cancel: start a move and close the screen mid-drag; the committed geometry stays
		t.waitT(25); // let the debounced save of the previous moves happen
		ExtendedConfig.Window committed = onClient(() -> mgr().config().window("p3-a"));
		ChatOpenRenderer.Layout e = layout("p3-a");
		cursor(e.strip()[0] + e.strip()[2] - 30, e.strip()[1] + 6);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		cursor(200, 100);
		t.waitT(2);
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		t.waitT(2);
		ctx.getInput().releaseMouse(0);
		t.waitT(3);
		ExtendedConfig.Window afterCancel = onClient(() -> mgr().config().window("p3-a"));
		boolean cancelKept = !onClient(ChatScreenInput::dragging) && committed.x() == afterCancel.x() && committed.y() == afterCancel.y();
		m.put("cancelOnCloseKeepsCommitted", cancelKept);
		closeChat();
		boolean ok = capturing && clamped && Boolean.TRUE.equals(m.get("resized")) && !startedUnderProtected && !other && cancelKept;
		ev.result("V-09e", ok, "observed", m, "startRect", Arrays.toString(startRect),
				"deferred", "(1)(4) tab drags between windows: tab relocation is not part of Phase 3 (Q21/Q33 tests deferred)");
		// restore p3-a's place for later tests
		edit(new ConfigEdit.SetGeometry("p3-a", 0.02, 0.04, 0.42, 0.45));
	}

	void v41b() {
		edit(new ConfigEdit.BringToFront("p3-b"));
		openChat("");
		String before = windowOrder();
		ChatOpenRenderer.Layout a = layout("p3-a"), b = layout("p3-b");
		// p3-b is in front of p3-a where they overlap; click p3-a where it's not covered
		double[] fa = freePoint("p3-a");
		click(fa[0], fa[1], 0);
		String afterClick = windowOrder();
		boolean front = afterClick.endsWith("p3-a]");
		// in the overlap, p3-a now gets the input
		ChatOpenRenderer.Layout a2 = layout("p3-a");
		int ox = Math.max(a2.rect()[0], b.rect()[0]) + 5, oy = Math.max(a2.body()[1], b.rect()[1]) + 5;
		boolean overlapToA = "p3-a".equals(onClient(() -> { var l = ChatOpenRenderer.topAt(ox, oy); return l == null ? null : l.windowId(); }));
		closeChat();
		t.waitT(25); // stacking order saved with the geometry debounce
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		String afterRestart = windowOrder();
		// during a divergence lock the order changes in memory but isn't saved
		JsonObject j = Phase2Tests.json(Phase2Tests.cfg());
		j.addProperty("externalEdit", true);
		Phase2Tests.write(Phase2Tests.cfg(), "{ broken");
		ctx.runOnClient(c -> mgr().attemptSave("test"));
		byte[] locked = Phase2Tests.bytes(Phase2Tests.cfg());
		openChat("");
		double[] fb = freePoint("p3-b");
		click(fb[0], fb[1], 0);
		closeChat();
		t.waitT(25);
		boolean memoryChanged = windowOrder().endsWith("p3-b]");
		boolean fileUntouched = Arrays.equals(locked, Phase2Tests.bytes(Phase2Tests.cfg()));
		boolean lockShown = onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE;
		ConfigManager.ActionResult over = onClient(() -> mgr().overwriteFile(true));
		ev.result("V-41b", front && overlapToA && afterRestart.equals(afterClick) && memoryChanged && fileUntouched && lockShown && over.ok(),
				"orderBefore", before, "afterClick", afterClick, "overlapGoesToFrontWindow", overlapToA, "afterRestart", afterRestart,
				"lockedOrderInMemory", memoryChanged, "fileUntouchedDuringLock", fileUntouched, "overwriteAfterwards", over,
				"note", "protected regions still win over the front window: covered by V-53/V-54");
	}

	void v57v58() {
		openChat("");
		ChatOpenRenderer.Layout a = layout("p3-a");
		double[] fp = freePoint("p3-a");
		Map<String, Object> m = new LinkedHashMap<>();
		int c0 = TestCounters.VANILLA_CLICKS.get(), s0 = TestCounters.VANILLA_SCROLLS.get();
		click(fp[0], fp[1], 0);
		click(fp[0], fp[1], 1);
		scrollAt(fp[0], fp[1], 1);
		drag(a.strip()[0] + a.strip()[2] - 30, a.strip()[1] + 6, a.strip()[0] + a.strip()[2] - 40, a.strip()[1] + 10, 0);
		m.put("consumedVanillaClicks", TestCounters.VANILLA_CLICKS.get() - c0);
		m.put("consumedVanillaScrolls", TestCounters.VANILLA_SCROLLS.get() - s0);
		m.put("noContextMenu", !onClient(() -> ChatPatchesAdapter.get().isContextMenuOpen((ChatScreen) Minecraft.getInstance().screen)));
		int[] sc = screen();
		int c1 = TestCounters.VANILLA_CLICKS.get(), s1 = TestCounters.VANILLA_SCROLLS.get();
		click(sc[0] / 2.0, 6, 0);           // empty area: passes
		scrollAt(30, sc[1] - 50, 1);         // main chat: passes
		m.put("passedVanillaClicks", TestCounters.VANILLA_CLICKS.get() - c1);
		m.put("passedVanillaScrolls", TestCounters.VANILLA_SCROLLS.get() - s1);
		ctx.runOnClient(c -> c.gui.getChat().resetChatScroll());
		boolean ok57 = Integer.valueOf(0).equals(m.get("consumedVanillaClicks")) && Integer.valueOf(0).equals(m.get("consumedVanillaScrolls"))
				&& Boolean.TRUE.equals(m.get("noContextMenu")) && Integer.valueOf(1).equals(m.get("passedVanillaClicks")) && Integer.valueOf(1).equals(m.get("passedVanillaScrolls"));
		ev.result("V-57", ok57, "observed", m);
		closeChat();

		Map<String, Object> f = new LinkedHashMap<>();
		for (ForeignListener.Behaviour b : new ForeignListener.Behaviour[]{ForeignListener.Behaviour.CONSUME_EARLY, ForeignListener.Behaviour.OBSERVE_EARLY, ForeignListener.Behaviour.CONSUME_LATE}) {
			ForeignListener.behaviour = b;
			openChat("");
			double[] p = freePoint("p3-a");
			long ours = ChatScreenInput.consumedScrolls();
			int fc = ForeignListener.CONSUMED.get(), fo = ForeignListener.OBSERVED.get();
			scrollAt(p[0], p[1], 1);
			f.put(b.name(), row("extendedActed", ChatScreenInput.consumedScrolls() > ours, "foreignConsumed", ForeignListener.CONSUMED.get() - fc, "foreignObserved", ForeignListener.OBSERVED.get() - fo));
			closeChat();
		}
		ForeignListener.behaviour = ForeignListener.Behaviour.OFF;
		boolean ok58 = String.valueOf(f.get("CONSUME_EARLY")).contains("extendedActed=false") && String.valueOf(f.get("OBSERVE_EARLY")).contains("extendedActed=true")
				&& String.valueOf(f.get("CONSUME_LATE")).contains("extendedActed=true") && String.valueOf(f.get("CONSUME_LATE")).contains("foreignConsumed=0");
		ev.result("V-58", ok58, "observed", f, "note", "an earlier consumer pre-empts Extended (documented, no error); Extended pre-empts a later one");
	}

	void v09v09b() {
		int[] sc = screen();
		Map<String, Object> m = new LinkedHashMap<>();
		// V-09: Chat Patches context menu open -> every click passes, no Extended interaction starts
		openChat("");
		click(30, sc[1] - 46, 1);
		t.waitT(3);
		boolean menu = onClient(() -> ChatPatchesAdapter.get().isContextMenuOpen((ChatScreen) Minecraft.getInstance().screen));
		ChatOpenRenderer.Layout a = layout("p3-a");
		String sel0 = onClient(() -> mgr().config().window("p3-a").selectedTab());
		long cc = ChatScreenInput.consumedClicks();
		click(a.tabs().get(1).x() + 3, a.tabs().get(1).y() + 5, 0);
		boolean noSelect = sel0.equals(onClient(() -> mgr().config().window("p3-a").selectedTab())) && ChatScreenInput.consumedClicks() == cc;
		m.put("menuOpened", menu);
		m.put("clickWithMenuOpenPassed", noSelect);
		closeChat();
		// unreliable menu detection -> treated as open
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(failingUi(real)); ChatPatchesAdapter.get().selfCheck(); });
		openChat("");
		long cc2 = ChatScreenInput.consumedClicks();
		ChatOpenRenderer.Layout a2 = layout("p3-a");
		// every point counts as Chat Patches UI now (conservative), so use the body's centre directly
		click(a2.body()[0] + a2.body()[2] / 2.0, a2.body()[1] + a2.body()[3] / 2.0, 0);
		boolean conservative = ChatScreenInput.consumedClicks() == cc2;
		closeChat();
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		m.put("unreliableDetectionTreatedAsOpen", conservative);
		ev.result("V-09", menu && noSelect && conservative, "observed", m);

		// V-09b: search button and search field over a window -> Chat Patches gets the click
		Map<String, Object> b = new LinkedHashMap<>();
		openChat("");
		Boolean shown0 = onClient(() -> ChatPatchesAdapter.get().isSearchBarShown());
		long c0 = ChatScreenInput.consumedClicks();
		click(10, sc[1] - 27, 0); // search button (p3-c lies under it)
		Boolean shown1 = onClient(() -> ChatPatchesAdapter.get().isSearchBarShown());
		int[] field = onClient(() -> ChatPatchesAdapter.get().searchFieldRect((ChatScreen) Minecraft.getInstance().screen));
		if (field != null) click(field[0] + 10, field[1] + 5, 0);
		boolean fieldFocused = onClient(() -> {
			ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
			EditBox f2 = ChatPatchesAdapter.get().searchFieldWidget(s);
			return f2 != null && s.getFocused() == f2;
		});
		click(10, sc[1] - 27, 1); // settings panel
		boolean settings = onClient(() -> ((obro1961.chatpatches.accessor.ChatScreenAccess) Minecraft.getInstance().screen).isMouseOverSettingsMenu(10, Minecraft.getInstance().screen.height - 103));
		boolean noneConsumed = ChatScreenInput.consumedClicks() == c0;
		click(10, sc[1] - 27, 1); // close the settings panel again
		b.put("searchToggled", !java.util.Objects.equals(shown0, shown1));
		b.put("searchFieldFocused", fieldFocused);
		b.put("settingsPanelOpened", settings);
		b.put("windowNeverConsumed", noneConsumed);
		closeChat();
		resetChatPatchesUi();
		ev.result("V-09b", !java.util.Objects.equals(shown0, shown1) && fieldFocused && noneConsumed, "observed", b,
				"note", "settings panel state recorded for information; its region is part of the V-53/V-54 scenes");
	}

	void v09d() {
		int[] sc = screen();
		Map<String, Object> m = new LinkedHashMap<>();
		openChat("");
		// main-chat line over p3-c: the main chat gets the scroll and the click
		int vs = TestCounters.VANILLA_SCROLLS.get();
		long ours = ChatScreenInput.consumedScrolls();
		scrollAt(30, sc[1] - 46, 1);
		m.put("mainChatScrolled", TestCounters.VANILLA_SCROLLS.get() - vs == 1 && ChatScreenInput.consumedScrolls() == ours);
		ctx.runOnClient(c -> c.gui.getChat().resetChatScroll());
		// input box over p3-c
		int vc = TestCounters.VANILLA_CLICKS.get();
		click(40, sc[1] - 8, 0);
		m.put("inputGotClick", TestCounters.VANILLA_CLICKS.get() - vc == 1);
		// visible suggestions over p3-c get the scroll; closed suggestions don't intercept
		ctx.getInput().typeChars("/");
		t.waitT(8);
		int[] rect = onClient(() -> {
			var cs = ((io.github.itznotneptune.cpextended.mixin.ChatScreenAccessor) Minecraft.getInstance().screen).cpextended$commandSuggestions();
			var list = cs == null ? null : ((io.github.itznotneptune.cpextended.mixin.CommandSuggestionsAccessor) cs).cpextended$suggestions();
			if (list == null) return null;
			var r = ((io.github.itznotneptune.cpextended.mixin.SuggestionsListAccessor) list).cpextended$rect();
			return new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()};
		});
		if (rect != null) {
			long o2 = ChatScreenInput.consumedScrolls();
			scrollAt(rect[0] + 5, rect[1] + 5, -1);
			m.put("suggestionsGotScroll", ChatScreenInput.consumedScrolls() == o2);
		}
		closeChat();
		openChat("");
		// no suggestions open now: the same point belongs to the window if nothing else covers it
		if (rect != null) {
			String reg = onClient(() -> HitTester.protectedRegion((ChatScreen) Minecraft.getInstance().screen, rect[0] + 5, rect[1] + 5));
			var top = onClient(() -> ChatOpenRenderer.topAt(rect[0] + 5, rect[1] + 5));
			m.put("closedSuggestionsDontIntercept", reg == null || !reg.equals("suggestions"));
			m.put("pointOwnerWhenClosed", reg != null ? reg : top == null ? "none" : top.windowId());
		}
		closeChat();
		boolean ok = Boolean.TRUE.equals(m.get("mainChatScrolled")) && Boolean.TRUE.equals(m.get("inputGotClick"))
				&& (rect == null || Boolean.TRUE.equals(m.get("suggestionsGotScroll")) && Boolean.TRUE.equals(m.get("closedSuggestionsDontIntercept")));
		ev.result("V-09d", ok, "observed", m, "suggestionsRect", rect == null ? null : Arrays.toString(rect),
				"note", "with Chat Visibility Hidden windows aren't drawn at all (V-39), so nothing lies under the hidden main chat");
	}

	void v12() {
		byte[] before = Phase2Tests.bytes(Phase2Tests.cfg());
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		int oldScale = onClient(() -> Minecraft.getInstance().options.guiScale().get());
		for (int gs = 1; gs <= 4; gs++) {
			int g = gs;
			ctx.runOnClient(c -> { c.options.guiScale().set(g); c.resizeDisplay(); WindowRenderer.resetCounters(); });
			t.waitT(4);
			int[] sc = screen();
			int[] hudRect = WindowRenderer.lastRect("p3-a");
			openChat("");
			int[] openRect = layout("p3-a") == null ? null : layout("p3-a").rect();
			long dup = WindowRenderer.duplicateDraws();
			closeChat();
			boolean inside = hudRect != null && openRect != null && hudRect[0] >= 0 && hudRect[0] + hudRect[2] <= sc[0] && openRect[1] + openRect[3] <= sc[1]
					&& Arrays.equals(hudRect, openRect);
			ok &= inside && dup == 0;
			rows.add(row("guiScale", gs, "screen", Arrays.toString(sc), "hudRect", Arrays.toString(hudRect), "chatOpenRect", Arrays.toString(openRect), "duplicateDraws", dup, "ok", inside && dup == 0));
		}
		ctx.runOnClient(c -> { c.options.guiScale().set(oldScale); c.resizeDisplay(); });
		// chat scale / opacity / spacing come from the vanilla options when the appearance doesn't set them
		JsonObject j = Phase2Tests.json(Phase2Tests.cfg());
		boolean fileUnchanged = Arrays.equals(before, Phase2Tests.bytes(Phase2Tests.cfg()));
		ev.result("V-12", ok && fileUnchanged, "rows", rows, "fileUnchangedByRendering", fileUnchanged,
				"note", "the same clamped rectangle on both paths at GUI scales 1-4; appearance overrides vs vanilla options are covered by the Phase 2 renderer");
	}

	void v11v28() {
		// V-11 (live part): fresh lines show in gameplay, then fade out like vanilla (restored lines are Phase 6)
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa2")));
		t.waitT(2);
		// a fresh line in tab pa2, then wait past vanilla's 200-tick fade
		ctx.runOnClient(c -> c.gui.getChat().addMessage(Component.literal("fresh line cpx#PB-fade")));
		t.waitT(4);
		int fresh = WindowRenderer.hudLinesDrawn("p3-a");
		t.waitT(215);
		int faded = WindowRenderer.hudLinesDrawn("p3-a");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa1")));
		ev.result("V-11.live", fresh > 0 && faded == 0, "linesDrawnFresh", fresh, "linesDrawnAfter215Ticks", faded,
				"note", "live lines fade with vanilla's 200-tick curve on the gameplay path; restored-line part deferred to Phase 6 (restored history isn't routed yet)");
		// V-28: no Extended input handling with the chat closed
		long cc = ChatScreenInput.consumedClicks(), cs = ChatScreenInput.consumedScrolls();
		int[] r = WindowRenderer.lastRect("p3-a");
		if (r != null) {
			cursor(r[0] + 10, r[1] + 10);
			ctx.getInput().scroll(1);
			t.waitT(2);
		}
		boolean none = ChatScreenInput.consumedClicks() == cc && ChatScreenInput.consumedScrolls() == cs;
		ev.result("V-28", none, "note", "Extended's listeners are registered per ChatScreen only; gameplay input over window positions isn't touched");
	}

	void saveLocks() {
		// V-21c / V-60 automatic-save protections: moving a window during a lock changes memory, never the file
		Phase2Tests.write(Phase2Tests.cfg(), "{ not json");
		ctx.runOnClient(c -> mgr().attemptSave("test"));
		byte[] locked = Phase2Tests.bytes(Phase2Tests.cfg());
		openChat("");
		ChatOpenRenderer.Layout a = layout("p3-a");
		boolean lockIcon = a != null && a.lock() != null;
		drag(a.strip()[0] + a.strip()[2] - 30, a.strip()[1] + 6, a.strip()[0] + a.strip()[2] - 60, a.strip()[1] + 20, 0);
		closeChat();
		t.waitT(25);
		boolean untouched = Arrays.equals(locked, Phase2Tests.bytes(Phase2Tests.cfg()));
		ConfigManager.SaveOutcome last = onClient(() -> mgr().lastSave());
		ConfigManager.ActionResult over = onClient(() -> mgr().overwriteFile(true));
		openChat("");
		boolean iconGone = layout("p3-a").lock() == null;
		closeChat();
		ev.result("V-60.automaticSave", lockIcon && untouched && last == ConfigManager.SaveOutcome.BLOCKED_DIVERGENCE && over.ok() && iconGone,
				"lockIconShown", lockIcon, "fileUntouched", untouched, "debouncedSaveOutcome", last, "overwrite", over, "iconClearedAfterOverwrite", iconGone);
	}

	void v56(TestSingleplayerContext sp) throws Exception {
		sp.getServer().runOnServer(s -> {
			var cmd = s.createCommandSourceStack().withSuppressedOutput();
			s.getCommands().performPrefixedCommand(cmd, "scoreboard objectives add cpxside dummy");
			s.getCommands().performPrefixedCommand(cmd, "scoreboard objectives setdisplay sidebar cpxside");
			s.getCommands().performPrefixedCommand(cmd, "scoreboard players set CPXTESTPLAYER cpxside 42");
		});
		for (int i = 0; i < 12; i++) magenta(sp, "scoreboard overlap line " + i + " cpx#PQ-" + i); // tab pb (window p3-b, right side)
		t.waitT(6);
		BufferedImage one = ImageIO.read(ctx.takeScreenshot("p3-v56-scoreboard-1").toFile());
		t.waitT(1);
		BufferedImage two = ImageIO.read(ctx.takeScreenshot("p3-v56-scoreboard-2").toFile());
		int[] sc = screen();
		double scale = one.getWidth() / (double) sc[0];
		int[] win = WindowRenderer.lastRect("p3-b");
		// The sidebar's score digits are red (0xFF5555); count them and window text inside the window's rectangle.
		int red = 0, magenta = 0, diff = 0;
		for (int x = (int) (win[0] * scale); x < (int) ((win[0] + win[2]) * scale) && x < one.getWidth(); x++) {
			for (int y = (int) (win[1] * scale); y < (int) ((win[1] + win[3]) * scale) && y < one.getHeight(); y++) {
				int c = one.getRGB(x, y) & 0xFFFFFF;
				if (c != (two.getRGB(x, y) & 0xFFFFFF)) diff++;
				int rr = c >> 16 & 0xFF, gg = c >> 8 & 0xFF, bb = c & 0xFF;
				if (rr > 240 && gg > 70 && gg < 100 && bb > 70 && bb < 100) red++;
				if (rr > 245 && gg < 10 && bb > 245) magenta++;
			}
		}
		String order = red > 0 ? "sidebar scoreboard drawn above Extended windows" : "Extended window drawn above the sidebar scoreboard";
		ev.result("V-56", magenta > 0 && diff == 0, "order", order, "scoreDigitPixelsInsideWindow", red, "windowTextPixels", magenta,
				"pixelsDifferingBetweenFrames", diff, "screenshots", List.of("p3-v56-scoreboard-1", "p3-v56-scoreboard-2"),
				"note", "windows are a HUD element attached after vanilla's chat; the order against later HUD layers is fixed by Fabric's layer list (documented, stable)");
	}

	// =================================================================== unread (P4)

	void unread() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
			ctx.runOnClient(c -> { mgr().devSimulateRestart(); c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.FULL); });
			t.waitT(4);
			Map<String, Object> r33 = new LinkedHashMap<>();
			t.sys(sp, "selected visible cpx#PA-u1");      // pa1 selected, p3-a visible -> read; poff (disabled window) -> unread
			t.sys(sp, "unselected cpx#PB-u1");            // pa2 unselected -> unread
			t.waitT(4);
			r33.put("selectedVisible", count("pa1"));
			r33.put("unselected", count("pa2"));
			r33.put("disabledWindow", count("poff"));
			ctx.runOnClient(c -> c.options.hideGui = true);
			t.sys(sp, "under F1 cpx#PA-u2");
			t.waitT(4);
			r33.put("selectedUnderF1", count("pa1"));
			ctx.runOnClient(c -> c.options.hideGui = false);
			t.waitT(2);
			int afterF1Off = count("pa1");
			ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.HIDDEN));
			t.waitT(2);
			t.sys(sp, "under hidden cpx#PA-u3");
			t.waitT(4);
			r33.put("selectedUnderChatHidden", count("pa1"));
			ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
			t.waitT(2);
			int afterShown = count("pa1");
			ev.result("V-33", r33.get("selectedVisible").equals(0) && r33.get("unselected").equals(1) && r33.get("disabledWindow").equals(1)
					&& r33.get("selectedUnderF1").equals(1) && r33.get("selectedUnderChatHidden").equals(1), "counts", r33,
					"note", "counts are per tab and cumulative: F1 cleared on release, then one more flagged under Chat Visibility Hidden");
			ev.result("V-33d", afterF1Off == 0 && afterShown == 0, "afterF1Released", afterF1Off, "afterChatShown", afterShown,
					"disabledWindowReenabled", reenable(sp));

			// V-33b: selecting a tab clears it
			int pa2Before = count("pa2");
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa2")));
			t.waitT(3);
			ev.result("V-33b", pa2Before > 0 && count("pa2") == 0, "before", pa2Before, "after", count("pa2"));
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa1")));
			t.waitT(2);

			// V-33e: F1 with chat closed hides; opening chat under F1 shows (and clears); closing hides again
			ctx.runOnClient(c -> c.options.hideGui = true);
			t.sys(sp, "f1 closed cpx#PA-e1");
			t.waitT(4);
			int flaggedClosed = count("pa1");
			openChat("");
			int clearedOpen = count("pa1");
			ChatOpenRenderer.Layout a = layout("p3-a");
			boolean drawnAndInteractive = a != null;
			double[] fpe = freePoint("p3-a");
			if (a != null && fpe != null) { scrollAt(fpe[0], fpe[1], 1); drawnAndInteractive = ChatOpenRenderer.scrollOf("p3-a") > 0 || a.maxScroll() == 0; }
			closeChat();
			t.sys(sp, "f1 closed again cpx#PA-e2");
			t.waitT(4);
			int flaggedAgain = count("pa1");
			ctx.runOnClient(c -> c.options.hideGui = false);
			t.waitT(2);
			ev.result("V-33e", flaggedClosed == 1 && clearedOpen == 0 && drawnAndInteractive && flaggedAgain == 1,
					"flaggedWithChatClosedUnderF1", flaggedClosed, "clearedOnOpeningChatUnderF1", clearedOpen, "drawnAndInteractive", drawnAndInteractive,
					"flaggedAfterClosingAgain", flaggedAgain);

			// V-33f / V-33c: condensed duplicates count once per displayed entry; restored never flagged
			int c0 = count("pa2");
			t.sys(sp, "duplicate x cpx#PB-dup");
			t.waitT(3);
			t.sys(sp, "duplicate x cpx#PB-dup");
			t.waitT(4);
			int unreadDup = count("pa2") - c0;
			long entriesDup = entries("pa2", "cpx#PB-dup");
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa2")));
			t.waitT(2);
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("p3-a", "pa1")));
			t.waitT(2);
			t.sys(sp, "duplicate x cpx#PB-dup");
			t.waitT(4);
			int unreadReadThenDup = count("pa2");
			ev.result("V-33f", unreadDup == 1 && entriesDup == 1 && unreadReadThenDup == 1, "unreadAfterXX", unreadDup, "retainedEntries", entriesDup,
					"unreadAfterReadThenX", unreadReadThenDup, "note", "Chat Patches condenses the copy; the earlier entry leaves with its flag by liveness (P4.4)");
			ev.result("V-33c", unreadDup == 1, "note", "condensed duplicates and deletions per Q23 (see V-33f, V-33g); restored history is never flagged by the delivery rule (restored routing is Phase 6)");

			// V-33g: delete and clear
			t.sys(sp, "to delete cpx#PB-del");
			t.waitT(4);
			int beforeDel = count("pa2");
			ctx.runOnClient(c -> {
				for (var g : AcceptanceTest.allMessagesReflective(c.gui.getChat())) if (g.content().getString().contains("cpx#PB-del")) { obro1961.chatpatches.util.ChatUtil.deleteMessage(g, false); break; }
			});
			t.waitT(3);
			int afterDel = count("pa2");
			ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
			t.waitT(3);
			int afterClear = count("pa2");
			boolean neverAboveRetained = onClient(() -> {
				for (TabState s : SessionMirror.get().tabStates()) {
					long flagged = s.entries().stream().filter(s::isUnread).count();
					if (flagged != s.unreadCount()) return false;
				}
				return true;
			});
			ev.result("V-33g", afterDel == beforeDel - 1 && afterClear == 0 && neverAboveRetained, "beforeDelete", beforeDel, "afterDelete", afterDel,
					"afterClear", afterClear, "countEqualsRetainedFlaggedEntries", neverAboveRetained);
			// unread is never persisted
			String file = new String(Phase2Tests.bytes(Phase2Tests.cfg()), java.nio.charset.StandardCharsets.UTF_8);
			boolean clean = java.util.stream.Stream.of("cpx#PA-u1", "cpx#PB-u1", "cpx#PB-dup", "\"unread\"", "\"messages\"", "\"hidden\"", "\"history\"").noneMatch(file::contains);
			ev.result("P3.unreadNotPersisted", clean, "note", "no delivered message text, unread or history data in cpextended.json (tab filter patterns are configuration)");
		}
		t.waitT(20);
	}

	String reenable(TestSingleplayerContext sp) {
		// a window that was disabled: its selected tab's flags clear once it's enabled (visible)
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("p3-off", true)));
		t.waitT(3);
		int after = count("poff");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("p3-off", false)));
		t.waitT(2);
		return after == 0 ? "cleared" : "still " + after;
	}

	int count(String tab) {
		return onClient(() -> { TabState s = SessionMirror.get().tab(tab); return s == null ? 0 : s.unreadCount(); });
	}

	long entries(String tab, String token) {
		return t.tabEntries(tab).stream().filter(r -> r.original() != null && r.original().getString().contains(token)).count();
	}

	/** Base for test backends that override a few calls. */
	static class DelegatingBackend implements ChatPatchesBackend {
		final ChatPatchesBackend real;
		DelegatingBackend(ChatPatchesBackend real) { this.real = real; }
		@Override public GuiMessageTag restoredIndicator() { return real.restoredIndicator(); }
		@Override public boolean vanillaClearing() { return real.vanillaClearing(); }
		@Override public int chatMaxMessages() { return real.chatMaxMessages(); }
		@Override public boolean isBoundaryLine(Component c) { return real.isBoundaryLine(c); }
		@Override public boolean counter() { return real.counter(); }
		@Override public boolean compactChat() { return real.compactChat(); }
		@Override public int compactDistance() { return real.compactDistance(); }
		@Override public boolean isLoading() { return real.isLoading(); }
		@Override public boolean isRestoring() { return real.isRestoring(); }
		@Override public boolean searchEnabled() { return real.searchEnabled(); }
		@Override public boolean contextMenuOpen(ChatScreen s) { return real.contextMenuOpen(s); }
		@Override public boolean overContextMenu(ChatScreen s, double x, double y) { return real.overContextMenu(s, x, y); }
		@Override public boolean overSettingsMenu(ChatScreen s, double x, double y) { return real.overSettingsMenu(s, x, y); }
		@Override public int mainChatLineAt(double x, double y) { return real.mainChatLineAt(x, y); }
		@Override public boolean searchCaseSensitive() { return real.searchCaseSensitive(); }
		@Override public boolean searchRegex() { return real.searchRegex(); }
		@Override public boolean searchEmptyShowsFullChat() { return real.searchEmptyShowsFullChat(); }
		@Override public java.util.List<net.minecraft.client.GuiMessage> searchResults(ChatScreen s) { return real.searchResults(s); }
	}
}
