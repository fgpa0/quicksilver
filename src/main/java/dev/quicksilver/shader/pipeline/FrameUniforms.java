package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.util.Mat4;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.BlockBase;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Living;
import net.minecraft.entity.player.PlayerBase;
import net.minecraft.item.ItemInstance;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.level.biome.Biome;
import net.minecraft.util.maths.MathHelper;
import org.lwjgl.opengl.GL11;

/** Computes the built-in uniforms once per frame (after the camera is set up). */
public final class FrameUniforms {
    private final Pipeline p;
    private final Uniforms u;
    Uniforms.Value gbufferModelView, gbufferModelViewInverse, gbufferProjection, gbufferProjectionInverse,
            gbufferPreviousModelView, gbufferPreviousProjection, shadowModelView, shadowModelViewInverse, shadowProjection,
            shadowProjectionInverse, cameraPosition, previousCameraPosition, cameraPositionInt, cameraPositionFract,
            previousCameraPositionInt, previousCameraPositionFract, sunPosition, moonPosition, shadowLightPosition, upPosition,
            worldTime, worldDay, moonPhase, frameCounter, frameTime, frameTimeCounter, sunAngle, shadowAngle, viewWidth, viewHeight,
            aspectRatio, near, far, rainStrength, wetness, thunderStrength, fogColor, skyColor, fogMode, fogStart, fogEnd, fogDensity,
            eyeBrightness, eyeBrightnessSmooth, isEyeInWater, eyeAltitude, eyePosition, relativeEyePosition, playerLookVector,
            playerBodyVector, heldItemId, heldBlockLightValue, heldItemId2, heldBlockLightValue2, blindness, nightVision,
            darknessFactor, darknessLightFactor, screenBrightness, playerMood, entityId, blockEntityId, entityColor, atlasSize,
            terrainTextureSize, terrainIconSize, renderStage, biome, biomeCategory, biomePrecipitation, temperature, rainfall,
            centerDepthSmooth, hideGUI, isElytraFlying, isSpectator, isSneaking, isSprinting, isHurt, isInvisible, isBurning,
            currentRenderedItemId, lightningBoltPosition, dhRenderDistance, constantMood, bossBattle, chunkOffset;

    public final float[] mv = new float[16], proj = new float[16], mvInv = new float[16], projInv = new float[16];
    final float[] prevMv = Mat4.identity(), prevProj = Mat4.identity();
    public final float[] shadowMv = Mat4.identity(), shadowProj = Mat4.identity();
    public double camX, camY, camZ;
    private double prevCamX, prevCamY, prevCamZ;
    float frameTimeSec;
    private long lastNs;
    private double counterSec;
    private int frames;
    private float wetnessValue;
    private float eyeSkySmooth = -1, eyeBlockSmooth = -1;
    public float skyAngleValue;
    float fogR, fogG, fogB;

    FrameUniforms(Pipeline p) {
        this.p = p;
        this.u = p.uniforms;
    }

    void register() {
        gbufferModelView = u.add("gbufferModelView", 16);
        gbufferModelViewInverse = u.add("gbufferModelViewInverse", 16);
        gbufferProjection = u.add("gbufferProjection", 16);
        gbufferProjectionInverse = u.add("gbufferProjectionInverse", 16);
        gbufferPreviousModelView = u.add("gbufferPreviousModelView", 16);
        gbufferPreviousProjection = u.add("gbufferPreviousProjection", 16);
        shadowModelView = u.add("shadowModelView", 16);
        shadowModelViewInverse = u.add("shadowModelViewInverse", 16);
        shadowProjection = u.add("shadowProjection", 16);
        shadowProjectionInverse = u.add("shadowProjectionInverse", 16);
        cameraPosition = u.add("cameraPosition", 3);
        previousCameraPosition = u.add("previousCameraPosition", 3);
        cameraPositionInt = u.add("cameraPositionInt", 3);
        cameraPositionFract = u.add("cameraPositionFract", 3);
        previousCameraPositionInt = u.add("previousCameraPositionInt", 3);
        previousCameraPositionFract = u.add("previousCameraPositionFract", 3);
        sunPosition = u.add("sunPosition", 3);
        moonPosition = u.add("moonPosition", 3);
        shadowLightPosition = u.add("shadowLightPosition", 3);
        upPosition = u.add("upPosition", 3);
        worldTime = u.add("worldTime", 1);
        worldDay = u.add("worldDay", 1);
        moonPhase = u.add("moonPhase", 1);
        frameCounter = u.add("frameCounter", 1);
        frameTime = u.add("frameTime", 1);
        frameTimeCounter = u.add("frameTimeCounter", 1);
        sunAngle = u.add("sunAngle", 1);
        shadowAngle = u.add("shadowAngle", 1);
        viewWidth = u.add("viewWidth", 1);
        viewHeight = u.add("viewHeight", 1);
        aspectRatio = u.add("aspectRatio", 1);
        near = u.add("near", 1);
        far = u.add("far", 1);
        rainStrength = u.add("rainStrength", 1);
        wetness = u.add("wetness", 1);
        thunderStrength = u.add("thunderStrength", 1);
        fogColor = u.add("fogColor", 3);
        skyColor = u.add("skyColor", 3);
        fogMode = u.add("fogMode", 1);
        fogStart = u.add("fogStart", 1);
        fogEnd = u.add("fogEnd", 1);
        fogDensity = u.add("fogDensity", 1);
        eyeBrightness = u.add("eyeBrightness", 2);
        eyeBrightnessSmooth = u.add("eyeBrightnessSmooth", 2);
        isEyeInWater = u.add("isEyeInWater", 1);
        eyeAltitude = u.add("eyeAltitude", 1);
        eyePosition = u.add("eyePosition", 3);
        relativeEyePosition = u.add("relativeEyePosition", 3);
        playerLookVector = u.add("playerLookVector", 3);
        playerBodyVector = u.add("playerBodyVector", 3);
        heldItemId = u.add("heldItemId", 1);
        heldBlockLightValue = u.add("heldBlockLightValue", 1);
        heldItemId2 = u.add("heldItemId2", 1);
        heldBlockLightValue2 = u.add("heldBlockLightValue2", 1);
        blindness = u.add("blindness", 1);
        nightVision = u.add("nightVision", 1);
        darknessFactor = u.add("darknessFactor", 1);
        darknessLightFactor = u.add("darknessLightFactor", 1);
        screenBrightness = u.add("screenBrightness", 1);
        playerMood = u.add("playerMood", 1);
        constantMood = u.add("constantMood", 1);
        entityId = u.add("entityId", 1);
        blockEntityId = u.add("blockEntityId", 1);
        entityColor = u.add("entityColor", 4);
        atlasSize = u.add("atlasSize", 2);
        terrainTextureSize = u.add("terrainTextureSize", 2);
        terrainIconSize = u.add("terrainIconSize", 1);
        renderStage = u.add("renderStage", 1);
        biome = u.add("biome", 1);
        biomeCategory = u.add("biome_category", 1);
        biomePrecipitation = u.add("biome_precipitation", 1);
        temperature = u.add("temperature", 1);
        rainfall = u.add("rainfall", 1);
        centerDepthSmooth = u.add("centerDepthSmooth", 1);
        hideGUI = u.add("hideGUI", 1);
        isElytraFlying = u.add("isElytraFlying", 1);
        isSpectator = u.add("isSpectator", 1);
        isSneaking = u.add("isSneaking", 1);
        isSprinting = u.add("isSprinting", 1);
        isHurt = u.add("isHurt", 1);
        isInvisible = u.add("isInvisible", 1);
        isBurning = u.add("isBurning", 1);
        currentRenderedItemId = u.add("currentRenderedItemId", 1);
        lightningBoltPosition = u.add("lightningBoltPosition", 4);
        bossBattle = u.add("bossBattle", 1);
        chunkOffset = u.add("chunkOffset", 3);
        heldItemId.set(-1);
        heldItemId2.set(-1);
        entityId.set(-1);
        blockEntityId.set(-1);
        currentRenderedItemId.set(-1);
        centerDepthSmooth.set(0.5f);
        near.set(0.05f);
    }

    // biome ids (1.12 registry numbers) and categories, as OptiFine's expression constants
    static final int B_OCEAN = 0, B_PLAINS = 1, B_DESERT = 2, B_FOREST = 4, B_TAIGA = 5, B_SWAMP = 6, B_HELL = 8, B_SKY = 9, B_ICE_FLATS = 12,
            B_JUNGLE = 21, B_BIRCH_FOREST = 27, B_SAVANNA = 35;

    Map<String, Double> constants() {
        Map<String, Double> c = new HashMap<>();
        String[][] biomes = {
                {"OCEAN", "0"}, {"PLAINS", "1"}, {"DESERT", "2"}, {"EXTREME_HILLS", "3"}, {"MOUNTAINS", "3"}, {"WINDSWEPT_HILLS", "3"},
                {"FOREST", "4"}, {"TAIGA", "5"}, {"SWAMPLAND", "6"}, {"SWAMP", "6"}, {"RIVER", "7"}, {"HELL", "8"}, {"NETHER", "8"},
                {"NETHER_WASTES", "8"}, {"SKY", "9"}, {"THE_END", "9"}, {"FROZEN_OCEAN", "10"}, {"FROZEN_RIVER", "11"}, {"ICE_FLATS", "12"},
                {"SNOWY_TUNDRA", "12"}, {"SNOWY_PLAINS", "12"}, {"ICE_MOUNTAINS", "13"}, {"MUSHROOM_ISLAND", "14"}, {"MUSHROOM_FIELDS", "14"},
                {"BEACH", "16"}, {"JUNGLE", "21"}, {"BIRCH_FOREST", "27"}, {"ROOFED_FOREST", "29"}, {"DARK_FOREST", "29"},
                {"TAIGA_COLD", "30"}, {"SNOWY_TAIGA", "30"}, {"SAVANNA", "35"}, {"MESA", "37"}, {"BADLANDS", "37"},
                {"SOUL_SAND_VALLEY", "170"}, {"CRIMSON_FOREST", "171"}, {"WARPED_FOREST", "172"}, {"BASALT_DELTAS", "173"},
                {"PALE_GARDEN", "180"}, {"SULFUR_CAVES", "181"}, {"MEADOW", "182"}, {"CHERRY_GROVE", "183"}, {"DEEP_DARK", "184"},
                {"LUSH_CAVES", "185"}, {"DRIPSTONE_CAVES", "186"}, {"MANGROVE_SWAMP", "187"},
        };
        for (String[] b : biomes) c.put("BIOME_" + b[0], Double.parseDouble(b[1]));
        String[] cats = {"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN",
                "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "UNDERGROUND", "MOUNTAIN"};
        for (int i = 0; i < cats.length; i++) c.put("CAT_" + cats[i], (double) i);
        c.put("PPT_NONE", 0.0);
        c.put("PPT_RAIN", 1.0);
        c.put("PPT_SNOW", 2.0);
        c.put("MC_VERSION", 10710.0);
        return c;
    }

    void update(float delta) {
        Minecraft mc = Shaders.mc();
        Level level = mc.level;
        Living view = mc.viewEntity;
        long now = System.nanoTime();
        frameTimeSec = lastNs == 0 ? 0.016f : Math.min(1f, (now - lastNs) / 1e9f);
        lastNs = now;
        counterSec += frameTimeSec;
        if (counterSec >= 3600) counterSec -= 3600;
        frames++;
        frameCounter.set(frames % 720720);
        frameTime.set(frameTimeSec);
        frameTimeCounter.set((float) counterSec);

        Mat4.get(GL11.GL_MODELVIEW_MATRIX, mv);
        Mat4.get(GL11.GL_PROJECTION_MATRIX, proj);
        Mat4.invert(mv, mvInv);
        Mat4.invert(proj, projInv);
        gbufferModelView.set(mv);
        gbufferModelViewInverse.set(mvInv);
        gbufferProjection.set(proj);
        gbufferProjectionInverse.set(projInv);
        gbufferPreviousModelView.set(prevMv);
        gbufferPreviousProjection.set(prevProj);

        camX = view.prevRenderX + (view.x - view.prevRenderX) * delta;
        camY = view.prevRenderY + (view.y - view.prevRenderY) * delta;
        camZ = view.prevRenderZ + (view.z - view.prevRenderZ) * delta;
        if (frames == 1) {
            prevCamX = camX;
            prevCamY = camY;
            prevCamZ = camZ;
        }
        cameraPosition.set((float) camX, (float) camY, (float) camZ);
        previousCameraPosition.set((float) prevCamX, (float) prevCamY, (float) prevCamZ);
        cameraPositionInt.set((float) Math.floor(camX), (float) Math.floor(camY), (float) Math.floor(camZ));
        cameraPositionFract.set((float) (camX - Math.floor(camX)), (float) (camY - Math.floor(camY)), (float) (camZ - Math.floor(camZ)));
        previousCameraPositionInt.set((float) Math.floor(prevCamX), (float) Math.floor(prevCamY), (float) Math.floor(prevCamZ));
        previousCameraPositionFract.set((float) (prevCamX - Math.floor(prevCamX)), (float) (prevCamY - Math.floor(prevCamY)), (float) (prevCamZ - Math.floor(prevCamZ)));
        eyePosition.set((float) camX, (float) camY, (float) camZ);
        eyeAltitude.set((float) camY);

        // time and celestial bodies
        long time = level.getLevelTime();
        worldTime.set(time % 24000L);
        worldDay.set(time / 24000L);
        moonPhase.set((int) ((time / 24000L) % 8L));
        float skyAngle = level.method_198(delta);
        skyAngleValue = skyAngle;
        float sa = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
        sunAngle.set(sa);
        shadowAngle.set(sa <= 0.5f ? sa : sa - 0.5f);
        float[] cel = celestial(skyAngle);
        float[] sun = Mat4.transform(cel, 0, 100, 0, 0);
        float[] moon = Mat4.transform(cel, 0, -100, 0, 0);
        sunPosition.set(sun[0], sun[1], sun[2]);
        moonPosition.set(moon[0], moon[1], moon[2]);
        if (sa <= 0.5f) shadowLightPosition.set(sun[0], sun[1], sun[2]);
        else shadowLightPosition.set(moon[0], moon[1], moon[2]);
        float[] up = Mat4.transform(mv, 0, 100, 0, 0);
        upPosition.set(up[0], up[1], up[2]);

        viewWidth.set(p.targets.width);
        viewHeight.set(p.targets.height);
        aspectRatio.set((float) p.targets.width / Math.max(1, p.targets.height));
        far.set(dev.quicksilver.client.RenderDistance.fogDistance() * 0.75f);
        float rain = level.getRainGradient(delta);
        rainStrength.set(rain);
        thunderStrength.set(level.getThunderGradient(delta));
        float half = rain > wetnessValue ? p.settings.wetnessHalflife : p.settings.drynessHalflife;
        wetnessValue = smooth(wetnessValue, rain, half, frameTimeSec);
        wetness.set(wetnessValue);
        fogColor.set(fogR, fogG, fogB);
        net.minecraft.util.maths.Vec3f sky = level.method_279(view, delta);
        skyColor.set((float) sky.x, (float) sky.y, (float) sky.z);

        int ex = MathHelper.floor(view.x), ey = MathHelper.floor(view.y), ez = MathHelper.floor(view.z);
        int skyL = ey >= 128 ? 15 : (ey < 0 ? 0 : level.method_164(LightType.field_2757, ex, ey, ez));
        int blkL = ey >= 128 || ey < 0 ? 0 : level.method_164(LightType.field_2758, ex, ey, ez);
        eyeBrightness.set(blkL * 16f, skyL * 16f);
        if (eyeSkySmooth < 0) {
            eyeSkySmooth = skyL * 16f;
            eyeBlockSmooth = blkL * 16f;
        }
        eyeSkySmooth = smooth(eyeSkySmooth, skyL * 16f, p.settings.eyeBrightnessHalflife, frameTimeSec);
        eyeBlockSmooth = smooth(eyeBlockSmooth, blkL * 16f, p.settings.eyeBrightnessHalflife, frameTimeSec);
        eyeBrightnessSmooth.set(Math.round(eyeBlockSmooth), Math.round(eyeSkySmooth));
        isEyeInWater.set(view.isInFluid(Material.WATER) ? 1 : view.isInFluid(Material.LAVA) ? 2 : 0);
        float yawR = (float) Math.toRadians(view.yaw), pitchR = (float) Math.toRadians(view.pitch);
        playerLookVector.set((float) (-Math.sin(yawR) * Math.cos(pitchR)), (float) -Math.sin(pitchR), (float) (Math.cos(yawR) * Math.cos(pitchR)));
        playerBodyVector.set((float) -Math.sin(yawR), 0, (float) Math.cos(yawR));
        isHurt.set(view.hurtTime > 0 ? 1 : 0);
        isSneaking.set(view.method_1373() ? 1 : 0);

        int held = -1, heldLight = 0;
        if (view instanceof PlayerBase pl) {
            ItemInstance it = pl.inventory.getHeldItem();
            if (it != null && it.itemId > 0) {
                held = p.pack.ids.item(it.itemId, it.getDamage());
                if (it.itemId < 256) heldLight = BlockBase.EMITTANCE[it.itemId];
            }
        }
        heldItemId.set(held);
        heldBlockLightValue.set(heldLight);
        screenBrightness.set(Shaders.config().screenBrightness);
        hideGUI.set(mc.options.hideHud ? 1 : 0);
        atlasSize.set(256, 256);
        terrainTextureSize.set(256, 256);
        terrainIconSize.set(16);

        Biome b = level.getBiomeSource().getBiome(ex, ez);
        int[] bi = biomeInfo(b);
        biome.set(bi[0]);
        biomeCategory.set(bi[1]);
        biomePrecipitation.set(b == null ? 0 : b.canSnow() ? 2 : b.canRain() ? 1 : 0);
        temperature.set(b == Biome.DESERT || b == Biome.SAVANNA || b == Biome.NETHER ? 2.0f : b != null && b.canSnow() ? 0.0f : 0.8f);
        rainfall.set(b == Biome.DESERT || b == Biome.NETHER ? 0.0f : b == Biome.RAINFOREST || b == Biome.SWAMPLAND ? 0.9f : 0.4f);
    }

    /** gbufferModelView * rotY(-90) * rotZ(sunPathRotation) * rotX(skyAngle * 360) (the modern sun path). */
    float[] celestial(float skyAngle) {
        float[] m = new float[16];
        Mat4.mul(mv, Mat4.rotation(-90, 0, 1, 0), m);
        Mat4.mul(m, Mat4.rotation(p.settings.sunPathRotation, 0, 0, 1), m);
        Mat4.mul(m, Mat4.rotation(skyAngle * 360f, 1, 0, 0), m);
        return m;
    }

    private static int[] biomeInfo(Biome b) {
        if (b == Biome.RAINFOREST) return new int[]{B_JUNGLE, 3};
        if (b == Biome.SWAMPLAND) return new int[]{B_SWAMP, 14};
        if (b == Biome.SEASONAL_FOREST) return new int[]{B_BIRCH_FOREST, 10};
        if (b == Biome.FOREST) return new int[]{B_FOREST, 10};
        if (b == Biome.SAVANNA) return new int[]{B_SAVANNA, 6};
        if (b == Biome.SHRUBLAND) return new int[]{B_PLAINS, 5};
        if (b == Biome.TAIGA) return new int[]{B_TAIGA, 1};
        if (b == Biome.DESERT) return new int[]{B_DESERT, 12};
        if (b == Biome.PLAINS) return new int[]{B_PLAINS, 5};
        if (b == Biome.ICE_DESERT || b == Biome.TUNDRA) return new int[]{B_ICE_FLATS, 7};
        if (b == Biome.NETHER) return new int[]{B_HELL, 16};
        if (b == Biome.SKY) return new int[]{B_SKY, 8};
        return new int[]{B_PLAINS, 5};
    }

    /** Exponential approach with a half life in seconds (OptiFine's smoothing of wetness and eye brightness). */
    static float smooth(float current, float target, float halfLife, float dt) {
        if (halfLife <= 0) return target;
        float k = (float) Math.pow(0.5, dt / halfLife);
        return target + (current - target) * k;
    }

    /** The shadow camera (OptiFine): looks at the sun or moon from 100 blocks away, snapped to a grid. */
    void computeShadowMatrices() {
        float dist = p.settings.shadowDistance;
        float[] m = Mat4.translation(0, 0, -100);
        Mat4.mul(m, Mat4.rotation(90, 1, 0, 0), m);
        float angle = skyAngleValue * -360f;
        float sa = skyAngleValue < 0.75f ? skyAngleValue + 0.25f : skyAngleValue - 0.75f;
        Mat4.mul(m, Mat4.rotation(sa <= 0.5f ? angle : angle + 180f, 0, 0, 1), m);
        Mat4.mul(m, Mat4.rotation(p.settings.sunPathRotation, 1, 0, 0), m);
        float interval = p.settings.shadowIntervalSize;
        if (interval > 0) {
            float h = interval / 2f;
            Mat4.mul(m, Mat4.translation((float) (camX % interval) - h, (float) (camY % interval) - h, (float) (camZ % interval) - h), m);
        }
        System.arraycopy(m, 0, shadowMv, 0, 16);
        float[] pr = Mat4.ortho(-dist, dist, -dist, dist, 0.05f, 256f);
        System.arraycopy(pr, 0, shadowProj, 0, 16);
        float[] inv = new float[16];
        shadowModelView.set(shadowMv);
        Mat4.invert(shadowMv, inv);
        shadowModelViewInverse.set(inv);
        shadowProjection.set(shadowProj);
        Mat4.invert(shadowProj, inv);
        shadowProjectionInverse.set(inv);
    }

    void endFrame() {
        System.arraycopy(mv, 0, prevMv, 0, 16);
        System.arraycopy(proj, 0, prevProj, 0, 16);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
    }

    public void setFog(float r, float g, float b) {
        fogR = r;
        fogG = g;
        fogB = b;
    }
}
