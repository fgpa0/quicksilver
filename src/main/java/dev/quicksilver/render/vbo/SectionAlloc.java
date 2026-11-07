package dev.quicksilver.render.vbo;

import net.minecraft.class_66;

/** The GPU storage of one render pass of one chunk section inside a region buffer. */
public final class SectionAlloc {
    public final class_66 section;
    public final int pass;
    final VboRegion region;
    int offset;
    int vertices;
    /** index in the region's per-pass arrays */
    int slot = -1;
    // section bounds in block coordinates for direction culling
    final int minX, minY, minZ;

    SectionAlloc(class_66 section, int pass, VboRegion region) {
        this.section = section;
        this.pass = pass;
        this.region = region;
        this.minX = section.field_231;
        this.minY = section.field_232;
        this.minZ = section.field_233;
    }
}
