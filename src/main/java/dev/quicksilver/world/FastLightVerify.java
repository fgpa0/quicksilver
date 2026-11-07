package dev.quicksilver.world;

import dev.quicksilver.Quicksilver;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.class_417;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.source.LevelSource;

/** Debug (-Dquicksilver.verifyLight=true): runs each update both ways and compares light data and queued updates. */
final class FastLightVerify {
    private FastLightVerify() {}

    static long checked, mismatches, queueMismatches;

    static void check(Level level, class_417 u, List<Object> q) {
        int ox0 = u.field_1674, oy0 = u.field_1675, oz0 = u.field_1676, ox1 = u.field_1677, oy1 = u.field_1678, oz1 = u.field_1679;
        LevelSource cache = level.getCache();
        int cx0 = (ox0 - 2) >> 4, cx1 = (ox1 + 2) >> 4, cz0 = (oz0 - 2) >> 4, cz1 = (oz1 + 2) >> 4;
        List<Chunk> chunks = new ArrayList<>();
        List<byte[]> before = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!cache.isChunkLoaded(cx, cz)) continue;
                Chunk c = cache.getChunk(cx, cz);
                if (c.field_958 == null) continue;
                chunks.add(c);
                before.add(c.field_958.field_2103.clone());
                before.add(c.field_959.field_2103.clone());
            }
        }
        int qs = q.size();
        int[][] tail = new int[Math.min(5, qs)][];
        for (int i = 0; i < tail.length; i++) {
            class_417 e = (class_417) q.get(qs - 1 - i);
            tail[i] = new int[]{e.field_1674, e.field_1675, e.field_1676, e.field_1677, e.field_1678, e.field_1679};
        }
        u.method_1402(level);
        List<byte[]> afterVanilla = new ArrayList<>();
        for (Chunk c : chunks) {
            afterVanilla.add(c.field_958.field_2103.clone());
            afterVanilla.add(c.field_959.field_2103.clone());
        }
        List<String> queuedVanilla = describe(q, qs);
        // restore and redo with the fast path
        while (q.size() > qs) q.remove(q.size() - 1);
        List<String> tailVanilla = describe(q, qs - tail.length);
        for (int i = 0; i < tail.length; i++) {
            class_417 e = (class_417) q.get(qs - 1 - i);
            e.field_1674 = tail[i][0];
            e.field_1675 = tail[i][1];
            e.field_1676 = tail[i][2];
            e.field_1677 = tail[i][3];
            e.field_1678 = tail[i][4];
            e.field_1679 = tail[i][5];
        }
        for (int i = 0; i < chunks.size(); i++) {
            System.arraycopy(before.get(2 * i), 0, chunks.get(i).field_958.field_2103, 0, before.get(2 * i).length);
            System.arraycopy(before.get(2 * i + 1), 0, chunks.get(i).field_959.field_2103, 0, before.get(2 * i + 1).length);
        }
        class_417 copy = new class_417(u.field_1673, ox0, oy0, oz0, ox1, oy1, oz1);
        if (!FastLight.process(level, copy)) copy.method_1402(level);
        List<String> queuedFast = describe(q, qs);
        boolean queueOk = queuedFast.equals(queuedVanilla) && describe(q, qs - tail.length).subList(0, tail.length).equals(tailVanilla);
        boolean ok = true;
        for (int i = 0; i < chunks.size() && ok; i++) {
            ok = java.util.Arrays.equals(afterVanilla.get(2 * i), chunks.get(i).field_958.field_2103)
                    && java.util.Arrays.equals(afterVanilla.get(2 * i + 1), chunks.get(i).field_959.field_2103);
        }
        checked++;
        if (!queueOk) queueMismatches++;
        if (!ok) {
            mismatches++;
            if (mismatches <= 10) Quicksilver.LOGGER.error("FastLight mismatch for {} {},{},{} -> {},{},{}: queued vanilla {} fast {}",
                    u.field_1673, ox0, oy0, oz0, ox1, oy1, oz1, queuedVanilla.size(), queuedFast.size());
        }
        if (checked % 20000 == 0) Quicksilver.LOGGER.info("FastLight verify: {} checked, {} light mismatches, {} queue mismatches", checked, mismatches, queueMismatches);
    }

    private static List<String> describe(List<Object> q, int from) {
        List<String> r = new ArrayList<>();
        for (int i = from; i < q.size(); i++) {
            class_417 e = (class_417) q.get(i);
            r.add(e.field_1673 + ":" + e.field_1674 + "," + e.field_1675 + "," + e.field_1676 + "," + e.field_1677 + "," + e.field_1678 + "," + e.field_1679);
        }
        return r;
    }
}
