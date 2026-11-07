package dev.quicksilver.mixin.world;

import dev.quicksilver.world.ChunkLoadManager;
import dev.quicksilver.world.QsChunk;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.block.BlockBase;
import net.minecraft.entity.Lightning;
import net.minecraft.entity.player.PlayerBase;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.util.maths.MathHelper;
import net.minecraft.util.maths.Vec2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Level.class)
public abstract class LevelMixin {
    @org.spongepowered.asm.mixin.Unique private static final boolean QS_VERIFY_TICKS = Boolean.getBoolean("quicksilver.verifyTicks");
    @org.spongepowered.asm.mixin.Unique private static final boolean QS_NO_SKIP = Boolean.getBoolean("quicksilver.noTickSkip");
    @org.spongepowered.asm.mixin.Unique private static final boolean QS_STRICT = Boolean.getBoolean("quicksilver.verifyTicksStrict");
    @Shadow private java.util.TreeSet treeSet;
    @Shadow private Set field_184;
    @Shadow protected net.minecraft.level.LevelProperties properties;

    /**
     * Vanilla processes at most 1000 due scheduled ticks (water and lava flow, falling blocks, redstone) per game
     * tick. When a backlog exists (lots of freshly generated terrain), keep working through due entries - in the same
     * order - for up to 1 ms more, so newly scheduled ones (the player's water) aren't stuck behind it.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "method_194(Z)Z", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
    private void qs$catchUpScheduledTicks(boolean force, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (force || this.treeSet.isEmpty()) return;
        Level level = (Level) (Object) this;
        long start = System.nanoTime();
        long now = this.properties.getTime();
        int done = 0;
        while (!this.treeSet.isEmpty()) {
            net.minecraft.TickSchedule t = (net.minecraft.TickSchedule) this.treeSet.first();
            if (t.scheduledTime > now) break;
            if ((++done & 31) == 0 && System.nanoTime() - start > 1_000_000L) break;
            this.treeSet.remove(t);
            this.field_184.remove(t);
            int r = 8;
            if (level.method_155(t.x - r, t.y - r, t.z - r, t.x + r, t.y + r, t.z + r)) {
                int id = level.getTileId(t.x, t.y, t.z);
                if (id == t.blockId && id > 0) BlockBase.BY_ID[id].onScheduledTick(level, t.x, t.y, t.z, this.rand);
            }
        }
        cir.setReturnValue(!this.treeSet.isEmpty());
    }

    @Shadow public List players;
    @Shadow public Random rand;
    @Shadow private Set field_194;
    @Shadow private int field_195;
    @Shadow protected int field_203;
    @Shadow protected int field_209;

    /**
     * @author Quicksilver
     * @reason Vanilla's per-tick chunk loop (cave sounds, lightning, snow/ice, 80 random block ticks per chunk), with
     * two changes: chunks that aren't loaded (with their neighbours) are skipped so ticking never loads or generates
     * chunks synchronously, and a random tick position inside a 16-block section that contains no randomly ticking
     * block at all skips the block lookup (a cache miss into the chunk's block array). The random number sequence and
     * everything that happens are exactly vanilla's.
     */
    @Overwrite
    public void method_248() {
        Level level = (Level) (Object) this;
        this.field_194.clear();

        for (int p = 0; p < this.players.size(); p++) {
            PlayerBase player = (PlayerBase) this.players.get(p);
            int pcx = MathHelper.floor(player.x / 16.0);
            int pcz = MathHelper.floor(player.z / 16.0);
            byte r = 9;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    this.field_194.add(new Vec2i(dx + pcx, dz + pcz));
                }
            }
        }
        ChunkLoadManager.filterLoaded(level, this.field_194);

        if (this.field_195 > 0) {
            this.field_195--;
        }
        long qsT0 = System.nanoTime();
        int qsSkipped = 0, qsRead = 0;

        for (Object o : this.field_194) {
            Vec2i pos = (Vec2i) o;
            int wx = pos.x * 16;
            int wz = pos.z * 16;
            Chunk chunk = level.getChunkFromCache(pos.x, pos.z);
            if (this.field_195 == 0) {
                this.field_203 = this.field_203 * 3 + 1013904223;
                int v = this.field_203 >> 2;
                int x = v & 15;
                int z = v >> 8 & 15;
                int y = v >> 16 & 127;
                int id = chunk.getTileId(x, y, z);
                x += wx;
                z += wz;
                if (id == 0 && level.getLightLevel(x, y, z) <= this.rand.nextInt(8) && level.method_164(LightType.field_2757, x, y, z) <= 0) {
                    PlayerBase near = level.getClosestPlayer((double) x + 0.5, (double) y + 0.5, (double) z + 0.5, 8.0);
                    if (near != null && near.squaredDistanceTo((double) x + 0.5, (double) y + 0.5, (double) z + 0.5) > 4.0) {
                        level.playSound((double) x + 0.5, (double) y + 0.5, (double) z + 0.5, "ambient.cave.cave", 0.7F, 0.8F + this.rand.nextFloat() * 0.2F);
                        this.field_195 = this.rand.nextInt(12000) + 6000;
                    }
                }
            }

            if (this.rand.nextInt(100000) == 0 && level.isRaining() && level.isThundering()) {
                this.field_203 = this.field_203 * 3 + 1013904223;
                int v = this.field_203 >> 2;
                int x = wx + (v & 15);
                int z = wz + (v >> 8 & 15);
                int y = level.method_228(x, z);
                if (level.canRainAt(x, y, z)) {
                    level.method_184(new Lightning(level, (double) x, (double) y, (double) z));
                    this.field_209 = 2;
                }
            }

            if (this.rand.nextInt(16) == 0) {
                this.field_203 = this.field_203 * 3 + 1013904223;
                int v = this.field_203 >> 2;
                int x = v & 15;
                int z = v >> 8 & 15;
                int y = level.method_228(x + wx, z + wz);
                if (level.getBiomeSource().getBiome(x + wx, z + wz).canSnow()
                        && y >= 0
                        && y < 128
                        && chunk.method_864(LightType.field_2758, x, y, z) < 10) {
                    int below = chunk.getTileId(x, y - 1, z);
                    int at = chunk.getTileId(x, y, z);
                    if (level.isRaining()
                            && at == 0
                            && BlockBase.SNOW.canPlaceAt(level, x + wx, y, z + wz)
                            && below != 0
                            && below != BlockBase.ICE.id
                            && BlockBase.BY_ID[below].material.blocksMovement()) {
                        level.setTile(x + wx, y, z + wz, BlockBase.SNOW.id);
                    }

                    if (below == BlockBase.STILL_WATER.id && chunk.method_875(x, y - 1, z) == 0) {
                        level.setTile(x + wx, y - 1, z + wz, BlockBase.ICE.id);
                    }
                }
            }

            QsChunk counts = chunk instanceof QsChunk q && !QS_NO_SKIP ? q : null;
            int mask = counts != null ? qs$tickMask(counts) : 0xFF; // sections that contain randomly ticking blocks
            for (int i = 0; i < 80; i++) {
                this.field_203 = this.field_203 * 3 + 1013904223;
                int v = this.field_203 >> 2;
                int x = v & 15;
                int z = v >> 8 & 15;
                int y = v >> 16 & 127;
                if ((mask >> (y >> 4) & 1) == 0) { // nothing in this section ticks: the lookup can't find anything
                    if (QS_STRICT && BlockBase.TICKS_RANDOMLY[chunk.tiles[x << 11 | z << 7 | y] & 255]) {
                        dev.quicksilver.Quicksilver.LOGGER.error("random tick count wrong at {},{},{}", x + wx, y, z + wz);
                    }
                    qsSkipped++;
                    continue;
                }
                qsRead++;
                int id = chunk.tiles[x << 11 | z << 7 | y] & 255;
                if (BlockBase.TICKS_RANDOMLY[id]) {
                    long qa = QS_VERIFY_TICKS ? System.nanoTime() : 0;
                    BlockBase.BY_ID[id].onScheduledTick(level, x + wx, y, z + wz, this.rand);
                    if (QS_VERIFY_TICKS) {
                        qs$byId[id] += System.nanoTime() - qa;
                        qs$cntId[id]++;
                    }
                    if (counts != null) mask = qs$tickMask(counts); // the tick may have changed blocks here
                }
            }
        }
        if (QS_VERIFY_TICKS) {
            qs$ns += System.nanoTime() - qsT0;
            qs$calls++;
            qs$sk += qsSkipped;
            qs$rd += qsRead;
            if (qs$calls % 100 == 0) {
                dev.quicksilver.Quicksilver.LOGGER.info("chunk tick: {} chunks, avg {} us, skipped {} read {} per tick", this.field_194.size(), qs$ns / qs$calls / 1000, qs$sk / qs$calls, qs$rd / qs$calls);
                StringBuilder sb = new StringBuilder();
                for (int b = 0; b < 256; b++) if (qs$cntId[b] > 0) sb.append(b).append(':').append(qs$cntId[b] / 100).append("x").append(qs$byId[b] / Math.max(1, qs$cntId[b])).append("ns ");
                dev.quicksilver.Quicksilver.LOGGER.info("per tick by block: {}", sb);
                java.util.Arrays.fill(qs$byId, 0);
                java.util.Arrays.fill(qs$cntId, 0);
                qs$ns = qs$calls = qs$sk = qs$rd = 0;
            }
        }
    }
    @org.spongepowered.asm.mixin.Unique
    private static int qs$tickMask(QsChunk c) {
        int m = 0;
        for (int s = 0; s < 8; s++) if (c.qs$randomTickCount(s) != 0) m |= 1 << s;
        return m;
    }

    @org.spongepowered.asm.mixin.Unique private static long qs$ns, qs$calls, qs$sk, qs$rd;
    @org.spongepowered.asm.mixin.Unique private static final long[] qs$byId = new long[256], qs$cntId = new long[256];
}
