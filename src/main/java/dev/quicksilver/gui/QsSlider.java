package dev.quicksilver.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widgets.Button;
import org.lwjgl.opengl.GL11;

/** A vanilla-looking slider over an arbitrary numeric range with snapping. */
public class QsSlider extends Button {
    private final double min, max, step;
    private double value;
    private boolean dragged;
    private final DoubleConsumer onChange;
    private final DoubleFunction<String> label;

    public QsSlider(int id, int x, int y, int w, double min, double max, double step, double value, DoubleConsumer onChange, DoubleFunction<String> label) {
        super(id, x, y, w, 20, "");
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = value;
        this.onChange = onChange;
        this.label = label;
        this.text = label.apply(value);
    }

    private double fraction() {
        return max > min ? (value - min) / (max - min) : 0;
    }

    private void setFromMouse(int mouseX) {
        double f = (double) (mouseX - (this.x + 4)) / (double) (this.width - 8);
        f = Math.max(0, Math.min(1, f));
        double v = min + f * (max - min);
        if (step > 0) v = Math.round((v - min) / step) * step + min;
        v = Math.max(min, Math.min(max, v));
        if (v != value) {
            value = v;
            onChange.accept(v);
        }
        this.text = label.apply(value);
    }

    @Override
    protected int getYImage(boolean hovered) {
        return 0;
    }

    @Override
    protected void postRender(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        if (dragged) setFromMouse(mouseX);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        int px = this.x + (int) (fraction() * (this.width - 8));
        this.blit(px, this.y, 0, 66, 4, 20);
        this.blit(px + 4, this.y, 196, 66, 4, 20);
    }

    @Override
    public boolean isMouseOver(Minecraft mc, int mouseX, int mouseY) {
        if (super.isMouseOver(mc, mouseX, mouseY)) {
            setFromMouse(mouseX);
            dragged = true;
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragged = false;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
    }
}
