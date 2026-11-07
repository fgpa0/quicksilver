package dev.quicksilver.mixin.io;

import dev.quicksilver.QsConfig;
import dev.quicksilver.io.RegionIO;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import net.minecraft.level.LevelProperties;
import net.minecraft.level.dimension.DimensionFile;
import net.minecraft.util.io.AbstractTag;
import net.minecraft.util.io.CompoundTag;
import net.minecraft.util.io.NBTIO;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DimensionFile.class)
public abstract class DimensionFileMixin {
    @Shadow @Final private File parentFolder;

    @Unique private long qs$lastLockCheck;

    /** The session lock file is re-read on every chunk save; once every two seconds is plenty. */
    @Inject(method = "checkSessionLock()V", at = @At("HEAD"), cancellable = true)
    private void qs$throttleLockCheck(CallbackInfo ci) {
        if (QsConfig.asyncChunkSaving && System.currentTimeMillis() - qs$lastLockCheck < 2000L) ci.cancel();
    }

    @Inject(method = "checkSessionLock()V", at = @At("RETURN"))
    private void qs$lockChecked(CallbackInfo ci) {
        qs$lastLockCheck = System.currentTimeMillis();
    }

    /** level.dat is rewritten every autosave (2 s); serialize here, write + rename on the IO thread. */
    @Inject(method = "saveLevelDataOnMP(Lnet/minecraft/level/LevelProperties;Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
    private void qs$asyncLevelDat(LevelProperties props, List players, CallbackInfo ci) {
        if (!QsConfig.asyncChunkSaving) return;
        CompoundTag data = props.getFirstEntityDataFromList(players);
        qs$write(data);
        ci.cancel();
    }

    @Inject(method = "saveLevelData(Lnet/minecraft/level/LevelProperties;)V", at = @At("HEAD"), cancellable = true)
    private void qs$asyncLevelDat2(LevelProperties props, CallbackInfo ci) {
        if (!QsConfig.asyncChunkSaving) return;
        CompoundTag data = props.getPlayerTag();
        qs$write(data);
        ci.cancel();
    }

    @Unique
    private void qs$write(CompoundTag data) {
        CompoundTag root = new CompoundTag();
        root.put("Data", (AbstractTag) data);
        File dir = this.parentFolder;
        RegionIO.submitFileTask("level.dat", () -> {
            try {
                File tmp = new File(dir, "level.dat_new");
                File old = new File(dir, "level.dat_old");
                File cur = new File(dir, "level.dat");
                NBTIO.writeGzipped(root, new FileOutputStream(tmp));
                if (old.exists()) old.delete();
                cur.renameTo(old);
                if (cur.exists()) cur.delete();
                tmp.renameTo(cur);
                if (tmp.exists()) tmp.delete();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
