package dev.quicksilver.shader.gui;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.LoadedPack;
import dev.quicksilver.shader.pack.ShaderOptions;
import dev.quicksilver.gui.QsSlider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.screen.ScreenBase;
import net.minecraft.client.gui.widgets.Button;
import org.lwjgl.input.Keyboard;

/** Shader options laid out like OptiFine: screen / screen.NAME entries of shaders.properties, with sub-screens. */
public class ShaderOptionsScreen extends ScreenBase {
    private static final int ID_DONE = 10000, ID_PREV = 10001, ID_NEXT = 10002, ID_RESET = 10003;
    private final ScreenBase parent;
    /** null = main screen */
    private final String screen;
    private final LoadedPack pack;
    private final List<String> entries = new ArrayList<>();
    private final List<String> buttonEntries = new ArrayList<>();
    private final List<Button> entryButtons = new ArrayList<>();
    private int page, columns = 2, perPage, entryWidth = 150;
    /** options changed in any screen since opening the top level */
    static boolean changed;

    public ShaderOptionsScreen(ScreenBase parent, String screen) {
        this.parent = parent;
        this.screen = screen;
        this.pack = Shaders.loadedPack();
        if (screen == null) changed = false;
        String def = pack.props.get(screen == null ? "screen" : "screen." + screen);
        String cols = pack.props.get(screen == null ? "screen.columns" : "screen." + screen + ".columns");
        if (cols != null) {
            try {
                columns = Math.max(1, Math.min(6, Integer.parseInt(cols.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        if (def == null) {
            // no layout: all options
            entries.addAll(pack.options.options.keySet());
        } else {
            Set<String> listed = new HashSet<>();
            for (String k : pack.props.map.keySet()) {
                if (k.equals("screen") || (k.startsWith("screen.") && !k.endsWith(".columns"))) {
                    for (String t : pack.props.get(k).trim().split("\\s+")) listed.add(t);
                }
            }
            for (String t : def.trim().split("\\s+")) {
                if (t.isEmpty()) continue;
                if (t.equals("*")) {
                    for (String o : pack.options.options.keySet()) if (!listed.contains(o)) entries.add(o);
                } else entries.add(t);
            }
        }
    }

    private int pages() {
        return Math.max(1, (entries.size() + perPage - 1) / perPage);
    }

    @Override
    public void init() {
        entryButtons.clear();
        buttonEntries.clear();
        int rows = Math.max(1, (this.height - 80) / 22);
        perPage = rows * columns;
        if (page >= pages()) page = pages() - 1;
        int total = Math.min(310, this.width - 20);
        int w = (total - (columns - 1) * 4) / columns;
        entryWidth = w;
        int left = this.width / 2 - total / 2;
        int top = 26;
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < entries.size(); i++) {
            String e = entries.get(start + i);
            int x = left + (i % columns) * (w + 4), y = top + 22 * (i / columns);
            Button b = makeButton(i, e, x, y, w);
            if (b == null) continue;
            this.buttons.add(b);
            entryButtons.add(b);
            buttonEntries.add(e);
        }
        int by = this.height - 28;
        if (pages() > 1) {
            Button prev = new Button(ID_PREV, left, by, 40, 20, "<");
            Button next = new Button(ID_NEXT, left + total - 40, by, 40, 20, ">");
            prev.active = page > 0;
            next.active = page < pages() - 1;
            this.buttons.add(prev);
            this.buttons.add(next);
        }
        if (screen == null) this.buttons.add(new Button(ID_RESET, this.width / 2 - 104, by, 100, 20, "Reset"));
        this.buttons.add(new Button(ID_DONE, this.width / 2 + (screen == null ? 4 : -50), by, 100, 20, screen == null ? "Done" : "Back"));
    }

    private Button makeButton(int id, String e, int x, int y, int w) {
        if (e.equals("<empty>")) return null;
        if (e.equals("<profile>")) {
            String cur = pack.currentProfile();
            String label = cur == null ? "Custom" : pack.translate("profile." + cur, cur);
            return new Button(id, x, y, w, 20, pack.translate("profile", "Profile") + ": " + label);
        }
        if (e.startsWith("[") && e.endsWith("]")) {
            String n = e.substring(1, e.length() - 1);
            return new Button(id, x, y, w, 20, clip(pack.translate("screen." + n, n) + "...", w));
        }
        ShaderOptions.Option o = pack.options.options.get(e);
        if (o == null) {
            // informational entry without an option (OptiFine shows it greyed out)
            Button b = new Button(id, x, y, w, 20, clip(pack.translate("option." + e, e), w));
            b.active = false;
            return b;
        }
        if (pack.sliders.contains(o.name) && o.kind != ShaderOptions.Kind.SWITCH && o.values.size() > 1) {
            int idx = Math.max(0, o.values.indexOf(o.value));
            return new QsSlider(id, x, y, w, 0, o.values.size() - 1, 1, idx, v -> {
                o.value = o.values.get((int) Math.round(v));
                changed = true;
            }, v -> clip(label(o, o.values.get((int) Math.round(Math.max(0, Math.min(o.values.size() - 1, v))))), w));
        }
        return new Button(id, x, y, w, 20, clip(label(o, o.value), w));
    }

    private String label(ShaderOptions.Option o, String value) {
        String name = pack.translate("option." + o.name, o.name);
        String v;
        if (o.kind == ShaderOptions.Kind.SWITCH || "true".equals(value) || "false".equals(value)) {
            v = "true".equals(value) ? "ON" : "OFF";
            v = pack.translate("value." + o.name + "." + value, v);
        } else {
            v = pack.translate("value." + o.name + "." + value, pack.translate("prefix." + o.name, "") + value + pack.translate("suffix." + o.name, ""));
        }
        return name + ": " + v;
    }

    private String clip(String s, int w) {
        if (this.textManager == null || this.textManager.getTextWidth(s) <= w - 6) return s;
        while (s.length() > 3 && this.textManager.getTextWidth(s + "..") > w - 6) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    @Override
    protected void buttonClicked(Button b) {
        if (!b.active) return;
        if (b.id == ID_DONE) {
            if (screen == null) {
                if (changed) {
                    pack.saveSettings(Shaders.settingsFile(pack.pack.name));
                    Shaders.requestReload();
                }
            }
            this.minecraft.openScreen(parent);
            return;
        }
        if (b.id == ID_RESET) {
            for (ShaderOptions.Option o : pack.options.options.values()) o.value = o.defaultValue;
            changed = true;
        } else if (b.id == ID_PREV || b.id == ID_NEXT) {
            page += b.id == ID_NEXT ? 1 : -1;
        } else if (b.id >= 0 && b.id < 10000 && !(b instanceof QsSlider)) {
            int i = entryButtons.indexOf(b);
            if (i < 0) return;
            String e = buttonEntries.get(i);
            boolean back = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            if (e.equals("<profile>")) {
                List<String> names = new ArrayList<>(pack.profiles.keySet());
                if (names.isEmpty()) return;
                String cur = pack.currentProfile();
                int idx = cur == null ? -1 : names.indexOf(cur);
                idx = back ? (idx <= 0 ? names.size() - 1 : idx - 1) : (idx + 1) % names.size();
                pack.applyProfile(names.get(idx));
                changed = true;
            } else if (e.startsWith("[")) {
                this.minecraft.openScreen(new ShaderOptionsScreen(this, e.substring(1, e.length() - 1)));
                return;
            } else {
                ShaderOptions.Option o = pack.options.options.get(e);
                if (o == null) return;
                ShaderOptions.cycle(o, back);
                changed = true;
            }
        }
        this.buttons.clear();
        this.init();
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        String title = screen == null ? "Shader Options: " + pack.pack.name : pack.translate("screen." + screen, screen);
        if (pages() > 1) title += " (" + (page + 1) + "/" + pages() + ")";
        this.drawTextWithShadowCentred(this.textManager, title, this.width / 2, 10, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
        for (int i = 0; i < entryButtons.size(); i++) {
            Button b = entryButtons.get(i);
            if (mouseX >= b.x && mouseY >= b.y && mouseX < b.x + entryWidth && mouseY < b.y + 20) {
                String e = buttonEntries.get(i);
                String c = pack.lang.get("option." + e + ".comment");
                if (c == null && e.startsWith("[")) c = pack.lang.get("screen." + e.substring(1, e.length() - 1) + ".comment");
                if (c == null) {
                    ShaderOptions.Option o = pack.options.options.get(e);
                    if (o != null && !o.comment.isEmpty()) c = o.comment;
                }
                if (c != null) drawWrapped(c, this.height - 52);
                break;
            }
        }
        if (changed) this.drawTextWithShadowCentred(this.textManager, "Changes apply when you close the options", this.width / 2, this.height - 40, 0xFFAA55);
    }

    private void drawWrapped(String text, int bottom) {
        int maxW = Math.min(this.width - 20, 360);
        List<String> lines = new ArrayList<>();
        for (String para : text.split("\\. ")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split(" ")) {
                String t = line.length() == 0 ? w : line + " " + w;
                if (this.textManager.getTextWidth(t) > maxW && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(w);
                } else line = new StringBuilder(t);
            }
            if (line.length() > 0) lines.add(line.toString());
        }
        int y = bottom - lines.size() * 10;
        for (String l : lines) {
            this.drawTextWithShadowCentred(this.textManager, l, this.width / 2, y, 0xC0C0C0);
            y += 10;
        }
    }
}
