package dev.quicksilver.render;

import net.minecraft.class_66;

public final class BuildTask implements Comparable<BuildTask> {
    public final class_66 section;
    public final int epoch;
    public final int generation;
    public final SectionSnapshot snapshot;
    public final boolean[] safe;
    public final float priority;
    public final long seq;
    public boolean vbo;
    /** built in the extended (shader) vertex format */
    public boolean ext;
    /** plain terrain with GPU time of day: vertices carry light levels (see CompactMesh.appendSky) */
    public boolean sky;
    /** smooth lighting (extended format light interpolation) */
    public boolean smooth;

    public BuildTask(class_66 section, int epoch, int generation, SectionSnapshot snapshot, boolean[] safe, float priority, long seq) {
        this.section = section;
        this.epoch = epoch;
        this.generation = generation;
        this.snapshot = snapshot;
        this.safe = safe;
        this.priority = priority;
        this.seq = seq;
    }

    @Override
    public int compareTo(BuildTask o) {
        int c = Float.compare(priority, o.priority);
        return c != 0 ? c : Long.compare(seq, o.seq);
    }
}
