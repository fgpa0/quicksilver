package dev.quicksilver.gui;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screen.ScreenBase;
import net.minecraft.client.gui.widgets.Button;

/** In-game configuration screen (Video Settings > Quicksilver...). Changes apply immediately and are saved on close. */
public class QsConfigScreen extends ScreenBase {
    private static final int PER_PAGE = 10;
    private static final int ID_DONE = 1000, ID_PREV = 1001, ID_NEXT = 1002;

    private final ScreenBase parent;
    private final List<Field> fields = new ArrayList<>();
    private final List<Button> optionButtons = new ArrayList<>();
    private final List<Field> optionFields = new ArrayList<>();
    private int page;
    private boolean changedRestart;

    public QsConfigScreen(ScreenBase parent) {
        this.parent = parent;
        for (Field f : QsConfig.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isPublic(m) && !Modifier.isFinal(m) && !f.getName().equals("configVersion")) fields.add(f);
        }
    }

    private int pages() {
        return (fields.size() + PER_PAGE - 1) / PER_PAGE;
    }

    private static final java.util.Map<String, String> NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("progressiveChunkLoading", "Progressive Loading"),
            java.util.Map.entry("chunkUploadBudgetMs", "Upload Budget (ms)"),
            java.util.Map.entry("urgentRebuildDistance", "Instant Rebuild Dist"),
            java.util.Map.entry("chunkLoadBudgetMs", "Load Budget (ms)"),
            java.util.Map.entry("unloadDistantChunks", "Unload Far Chunks"),
            java.util.Map.entry("lightUpdatesPerFrame", "Light Updates/Frame"),
            java.util.Map.entry("lightBudgetMs", "Light Budget (ms)"),
            java.util.Map.entry("disableOcclusionQueries", "Disable GL Queries"),
            java.util.Map.entry("skipGlErrorChecks", "Skip GL Error Checks"),
            java.util.Map.entry("chunkBuildThreads", "Mesh Threads"),
            java.util.Map.entry("worldGenThreads", "Worldgen Threads"),
            java.util.Map.entry("threadedChunkBuilds", "Threaded Meshing"),
            java.util.Map.entry("fastBiomeColours", "Fast Biome Colours"),
            java.util.Map.entry("asyncChunkSaving", "Async Saving"),
            java.util.Map.entry("asyncWorldGen", "Async Worldgen"),
            java.util.Map.entry("chunkPrefetch", "Chunk Prefetch"),
            java.util.Map.entry("caveCulling", "Cave Culling"),
            java.util.Map.entry("debugInfo", "F3 Debug Info"),
            java.util.Map.entry("maxFps", "Max FPS"),
            java.util.Map.entry("vsyncMode", "VSync"),
            java.util.Map.entry("vboRenderer", "VBO Renderer"),
            java.util.Map.entry("skyShader", "GPU Time of Day"));

    static String label(Field f) {
        String n = f.getName();
        if (NAMES.containsKey(n)) return NAMES.get(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (i == 0) sb.append(Character.toUpperCase(c));
            else if (Character.isUpperCase(c) && !Character.isUpperCase(n.charAt(i - 1))) sb.append(' ').append(c);
            else sb.append(c);
        }
        return sb.toString().replace("Ms", "(ms)");
    }

    private static String choiceText(Field f) {
        String[] c = f.getAnnotation(QsConfig.Choices.class).value();
        int v = 0;
        try {
            v = f.getInt(null);
        } catch (Exception ignored) {
        }
        return label(f) + ": " + c[Math.floorMod(v, c.length)];
    }

    private static String fmt(Field f, double v) {
        String name = label(f);
        if (f.getType() == float.class) return name + ": " + (Math.round(v * 10) / 10.0);
        int iv = (int) Math.round(v);
        if (iv == 0 && (f.getName().endsWith("Threads") || f.getName().equals("maxFps"))) return name + ": " + (f.getName().equals("maxFps") ? "Unlimited" : "Auto");
        if (f.getName().equals("renderDistance")) return "Render Distance: " + iv + " chunks";
        return name + ": " + iv;
    }

    @Override
    public void init() {
        optionButtons.clear();
        optionFields.clear();
        if (page >= pages()) page = Math.max(0, pages() - 1);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < fields.size(); i++) {
            Field f = fields.get(start + i);
            int x = this.width / 2 - 155 + (i % 2) * 160;
            int y = this.height / 6 - 12 + 24 * (i / 2);
            Button b;
            try {
                if (f.getType() == boolean.class) {
                    b = new Button(i, x, y, 150, 20, label(f) + ": " + (f.getBoolean(null) ? "ON" : "OFF"));
                } else if (f.isAnnotationPresent(QsConfig.Choices.class)) {
                    b = new Button(i, x, y, 150, 20, choiceText(f));
                } else {
                    QsConfig.Range r = f.getAnnotation(QsConfig.Range.class);
                    double min = r != null ? r.min() : 0, max = r != null ? r.max() : 100, step = r != null ? r.step() : 1;
                    double val = f.getType() == float.class ? f.getFloat(null) : f.getInt(null);
                    b = new QsSlider(i, x, y, 150, min, max, step, val, v -> set(f, v), v -> fmt(f, v));
                }
            } catch (Exception e) {
                continue;
            }
            this.buttons.add(b);
            optionButtons.add(b);
            optionFields.add(f);
        }
        int by = this.height / 6 + 24 * 5 - 4;
        Button prev = new Button(ID_PREV, this.width / 2 - 155, by, 70, 20, "< Prev");
        Button next = new Button(ID_NEXT, this.width / 2 + 85, by, 70, 20, "Next >");
        prev.active = page > 0;
        next.active = page < pages() - 1;
        this.buttons.add(prev);
        this.buttons.add(next);
        this.buttons.add(new Button(ID_DONE, this.width / 2 - 75, by, 150, 20, "Done"));
    }

    private void set(Field f, double v) {
        try {
            if (f.getType() == float.class) f.setFloat(null, (float) v);
            else f.setInt(null, (int) Math.round(v));
            if (f.isAnnotationPresent(QsConfig.Restart.class)) changedRestart = true;
            QsConfig.onChanged(f.getName());
        } catch (Exception e) {
            Quicksilver.LOGGER.warn("Could not set " + f.getName(), e);
        }
    }

    @Override
    protected void buttonClicked(Button b) {
        if (!b.active) return;
        if (b.id == ID_DONE) {
            QsConfig.save();
            this.minecraft.openScreen(parent);
            return;
        }
        if (b.id == ID_PREV || b.id == ID_NEXT) {
            page += b.id == ID_NEXT ? 1 : -1;
            this.buttons.clear();
            this.init();
            return;
        }
        if (b.id >= 0 && b.id < optionFields.size() && !(b instanceof QsSlider)) {
            Field f = optionFields.get(b.id);
            if (f.isAnnotationPresent(QsConfig.Choices.class)) {
                try {
                    int n = f.getAnnotation(QsConfig.Choices.class).value().length;
                    f.setInt(null, (f.getInt(null) + 1) % n);
                    b.text = choiceText(f);
                    QsConfig.onChanged(f.getName());
                } catch (Exception e) {
                    Quicksilver.LOGGER.warn("Could not change " + f.getName(), e);
                }
                return;
            }
            try {
                boolean nv = !f.getBoolean(null);
                f.setBoolean(null, nv);
                b.text = label(f) + ": " + (nv ? "ON" : "OFF");
                if (f.isAnnotationPresent(QsConfig.Restart.class)) changedRestart = true;
                QsConfig.onChanged(f.getName());
            } catch (Exception e) {
                Quicksilver.LOGGER.warn("Could not toggle " + f.getName(), e);
            }
        }
    }

    @Override
    public void onClose() {
        QsConfig.save();
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        this.drawTextWithShadowCentred(this.textManager, "Quicksilver Settings (" + (page + 1) + "/" + pages() + ")", this.width / 2, 12, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
        // description of the hovered option
        for (int i = 0; i < optionButtons.size(); i++) {
            Button b = optionButtons.get(i);
            if (mouseX >= b.x && mouseY >= b.y && mouseX < b.x + 150 && mouseY < b.y + 20) {
                Field f = optionFields.get(i);
                QsConfig.Desc d = f.getAnnotation(QsConfig.Desc.class);
                String text = d != null ? d.value() : "";
                if (f.isAnnotationPresent(QsConfig.Restart.class)) text += " (requires restart)";
                drawWrapped(text, this.height / 6 + 24 * 5 + 22);
                break;
            }
        }
        if (changedRestart) {
            this.drawTextWithShadowCentred(this.textManager, "Some changes need a restart to take effect.", this.width / 2, this.height - 12, 0xFFAA55);
        }
    }

    private void drawWrapped(String text, int y) {
        int maxW = Math.min(this.width - 20, 320);
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String tryLine = line.length() == 0 ? w : line + " " + w;
            if (this.textManager.getTextWidth(tryLine) > maxW && line.length() > 0) {
                this.drawTextWithShadowCentred(this.textManager, line.toString(), this.width / 2, y, 0xA0A0A0);
                y += 10;
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (line.length() > 0) this.drawTextWithShadowCentred(this.textManager, line.toString(), this.width / 2, y, 0xA0A0A0);
    }
}
