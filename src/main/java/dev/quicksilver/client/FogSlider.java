package dev.quicksilver.client;

import dev.quicksilver.QsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widgets.Slider;
import net.minecraft.client.options.Option;
import org.lwjgl.opengl.GL11;

/** Video settings slider for the fog distance: far left is "Auto" (follows the render distance), then 2..48 chunks. */
public final class FogSlider extends Slider {
    public static final int ID = 120;
    private static final int STEPS = 47;

    public FogSlider(int x, int y) {
        super(ID, x, y, Option.field_1101, label(QsConfig.fogDistance), toValue(QsConfig.fogDistance));
    }

    static float toValue(int chunks) {
        return chunks <= 0 ? 0f : (Math.min(chunks, 48) - 1) / (float) STEPS;
    }

    static int toChunks(float value) {
        int n = Math.round(value * STEPS);
        return n <= 0 ? 0 : n + 1;
    }

    static String label(int chunks) {
        return "Fog distance: " + (chunks <= 0 ? "Auto" : chunks + " chunks");
    }

    private void apply(float v) {
        this.value = Math.max(0f, Math.min(1f, v));
        int chunks = toChunks(this.value);
        if (chunks != QsConfig.fogDistance) {
            QsConfig.fogDistance = chunks;
            QsConfig.save();
        }
        this.text = label(chunks);
    }

    @Override
    protected void postRender(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        if (this.dragged) apply((float) (mouseX - (this.x + 4)) / (float) (this.width - 8));
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.blit(this.x + (int) (this.value * (this.width - 8)), this.y, 0, 66, 4, 20);
        this.blit(this.x + (int) (this.value * (this.width - 8)) + 4, this.y, 196, 66, 4, 20);
    }

    @Override
    public boolean isMouseOver(Minecraft mc, int mouseX, int mouseY) {
        if (!super.isMouseOver(mc, mouseX, mouseY)) return false;
        apply((float) (mouseX - (this.x + 4)) / (float) (this.width - 8));
        this.dragged = true;
        return true;
    }
}
