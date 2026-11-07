package dev.quicksilver.shader.gui;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.ShaderPack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screen.ScreenBase;
import net.minecraft.client.gui.widgets.Button;

/** Shader pack selection (shaderpacks/ folder). */
public class ShaderPackScreen extends ScreenBase {
    private static final int PER_PAGE = 8;
    private static final int ID_DONE = 1000, ID_PREV = 1001, ID_NEXT = 1002, ID_OPTIONS = 1003, ID_FOLDER = 1004, ID_OFF = 1005;
    private final ScreenBase parent;
    private List<String> packs = new ArrayList<>();
    private int page;
    private Button optionsButton;

    public ShaderPackScreen(ScreenBase parent) {
        this.parent = parent;
    }

    private int pages() {
        return Math.max(1, (packs.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    public void init() {
        packs = ShaderPack.list(Shaders.packsDir());
        if (page >= pages()) page = pages() - 1;
        String sel = Shaders.config().pack;
        int top = this.height / 6 - 12;
        Button off = new Button(ID_OFF, this.width / 2 - 155, top, 310, 20, (sel.isEmpty() ? "> " : "") + "OFF (no shaders)" + (sel.isEmpty() ? " <" : ""));
        this.buttons.add(off);
        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < packs.size(); i++) {
            String name = packs.get(page * PER_PAGE + i);
            String label = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
            if (this.textManager.getTextWidth(label) > 290) label = label.substring(0, Math.min(label.length(), 44)) + "...";
            boolean selected = name.equals(sel);
            this.buttons.add(new Button(i, this.width / 2 - 155, top + 22 * (i + 1), 310, 20, (selected ? "> " : "") + label + (selected ? " <" : "")));
        }
        int by = top + 22 * (PER_PAGE + 1) + 6;
        Button prev = new Button(ID_PREV, this.width / 2 - 155, by, 40, 20, "<");
        Button next = new Button(ID_NEXT, this.width / 2 + 115, by, 40, 20, ">");
        prev.active = page > 0;
        next.active = page < pages() - 1;
        this.buttons.add(prev);
        this.buttons.add(next);
        optionsButton = new Button(ID_OPTIONS, this.width / 2 - 110, by, 108, 20, "Shader Options...");
        optionsButton.active = Shaders.loadedPack() != null;
        this.buttons.add(optionsButton);
        this.buttons.add(new Button(ID_FOLDER, this.width / 2 + 2, by, 108, 20, "Open Folder"));
        this.buttons.add(new Button(ID_DONE, this.width / 2 - 75, by + 24, 150, 20, "Done"));
    }

    @Override
    protected void buttonClicked(Button b) {
        if (!b.active) return;
        switch (b.id) {
            case ID_DONE -> {
                this.minecraft.openScreen(parent);
                return;
            }
            case ID_PREV, ID_NEXT -> page += b.id == ID_NEXT ? 1 : -1;
            case ID_OPTIONS -> {
                if (Shaders.loadedPack() != null) this.minecraft.openScreen(new ShaderOptionsScreen(this, null));
                return;
            }
            case ID_FOLDER -> openFolder();
            case ID_OFF -> select("");
            default -> {
                int i = page * PER_PAGE + b.id;
                if (b.id >= 0 && b.id < PER_PAGE && i < packs.size()) select(packs.get(i));
            }
        }
        this.buttons.clear();
        this.init();
    }

    private void select(String name) {
        if (name.equals(Shaders.config().pack)) return;
        Shaders.config().pack = name;
        Shaders.config().save();
        Shaders.requestReload();
    }

    private void openFolder() {
        String path = Shaders.packsDir().toAbsolutePath().toString();
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("explorer", path).start();
            else if (os.contains("mac")) new ProcessBuilder("open", path).start();
            else new ProcessBuilder("xdg-open", path).start();
        } catch (Exception e) {
            try {
                org.lwjgl.Sys.openURL("file://" + path);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        this.drawTextWithShadowCentred(this.textManager, "Shader Packs (" + (page + 1) + "/" + pages() + ")", this.width / 2, 8, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
        if (optionsButton != null) optionsButton.active = Shaders.loadedPack() != null;
        String status = Shaders.lastError != null ? Shaders.lastError
                : Shaders.active() ? "Active: " + Shaders.config().pack : (Shaders.config().pack.isEmpty() ? "Shaders are off" : "Loading when you enter a world: " + Shaders.config().pack);
        if (this.textManager.getTextWidth(status) > this.width - 10) status = status.substring(0, Math.min(status.length(), 90)) + "...";
        this.drawTextWithShadowCentred(this.textManager, status, this.width / 2, this.height - 12, Shaders.lastError != null ? 0xFF7070 : 0xA0A0A0);
    }
}
