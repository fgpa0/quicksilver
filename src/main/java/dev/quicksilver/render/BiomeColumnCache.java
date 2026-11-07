package dev.quicksilver.render;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.level.Level;
import net.minecraft.level.biome.Biome;
import net.minecraft.level.gen.BiomeSource;

/**
 * A BiomeSource that answers the 1x1 lookups made by grass/leaves/tall-grass colour code from a table computed once
 * per chunk section (one 18x18 noise evaluation instead of three noise evaluations per coloured block).
 * Lookups outside the table fall back to a thread-confined real BiomeSource.
 */
public final class BiomeColumnCache extends BiomeSource {
    private static final int N = 18;
    private static final ThreadLocal<Map<Level, BiomeSource>> BACKING = ThreadLocal.withInitial(WeakHashMap::new);

    private final Level level;
    private final int x0, z0;
    private double[] cachedTemp;
    private double[] cachedRain;
    private Biome[] cachedBiomes;

    public BiomeColumnCache(Level level, int x0, int z0) {
        super();
        this.level = level;
        this.x0 = x0;
        this.z0 = z0;
        this.temperatureNoises = new double[1];
        this.rainfallNoises = new double[1];
        this.biomes = new Biome[1];
    }

    private static BiomeSource backing(Level level) {
        Map<Level, BiomeSource> m = BACKING.get();
        BiomeSource s = m.get(level);
        if (s == null) {
            s = new BiomeSource(level);
            m.put(level, s);
        }
        return s;
    }

    /** One computed 18x18 table; shared by all sections of a chunk column. Immutable once published. */
    private static final class Table {
        Level level;
        int x0, z0;
        double[] temp, rain;
        Biome[] biomes;
    }

    private static final int CACHE = 32;
    private static final ThreadLocal<Table[]> TABLES = ThreadLocal.withInitial(() -> new Table[CACHE]);

    /** Computes the table (or reuses the one of another section in the same column); call on the meshing thread. */
    public void fill() {
        Table[] tables = TABLES.get();
        int slot = (((x0 >> 4) * 0x9E3779B9) ^ ((z0 >> 4) * 0x85EBCA6B)) >>> 27; // 0..31
        Table t = tables[slot];
        if (t == null || t.level != level || t.x0 != x0 || t.z0 != z0) {
            BiomeSource b = backing(level);
            Table n = new Table();
            n.biomes = b.getBiomes(new Biome[N * N], x0, z0, N, N);
            n.temp = b.temperatureNoises.clone();
            n.rain = b.rainfallNoises.clone();
            n.level = level;
            n.x0 = x0;
            n.z0 = z0;
            tables[slot] = t = n;
        }
        cachedTemp = t.temp;
        cachedRain = t.rain;
        cachedBiomes = t.biomes;
    }

    @Override
    public Biome[] getBiomes(int x, int z, int w, int h) {
        int dx = x - x0, dz = z - z0;
        if (w == 1 && h == 1 && cachedTemp != null && dx >= 0 && dz >= 0 && dx < N && dz < N) {
            int i = dx * N + dz;
            temperatureNoises[0] = cachedTemp[i];
            rainfallNoises[0] = cachedRain[i];
            biomes[0] = cachedBiomes[i];
            return biomes;
        }
        BiomeSource b = backing(level);
        Biome[] r = b.getBiomes(x, z, w, h);
        this.temperatureNoises = b.temperatureNoises;
        this.rainfallNoises = b.rainfallNoises;
        this.detailNoises = b.detailNoises;
        this.biomes = r;
        return r;
    }

    @Override
    public Biome[] getBiomes(Biome[] out, int x, int z, int w, int h) {
        BiomeSource b = backing(level);
        Biome[] r = b.getBiomes(out, x, z, w, h);
        this.temperatureNoises = b.temperatureNoises;
        this.rainfallNoises = b.rainfallNoises;
        this.detailNoises = b.detailNoises;
        return r;
    }

    @Override
    public double getTemperature(int x, int z) {
        return backing(level).getTemperature(x, z);
    }

    @Override
    public double[] getTemperatures(double[] out, int x, int z, int w, int h) {
        BiomeSource b = backing(level);
        double[] r = b.getTemperatures(out, x, z, w, h);
        this.detailNoises = b.detailNoises;
        return r;
    }
}
