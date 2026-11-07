package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.Directives;
import dev.quicksilver.shader.pack.LoadedPack;
import dev.quicksilver.shader.pack.Macros;
import dev.quicksilver.shader.util.Mat4;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * The OptiFine-style deferred pipeline for one loaded pack and dimension: shadow pass, gbuffers programs per render
 * phase, deferred passes before translucent terrain, composite passes and the final pass to the screen.
 */
public final class Pipeline {
    public final LoadedPack pack;
    public final int dimension;
    public final PipelineSettings settings = new PipelineSettings();
    public final Uniforms uniforms = new Uniforms();
    public final FrameUniforms frame;
    public RenderTargets targets;
    public ShadowTargets shadow;
    private final Map<String, Program> programs = new HashMap<>();
    /** gbuffers program name -> program after fallbacks (null = fixed function) */
    private final Map<String, Program> resolved = new HashMap<>();
    private final List<Program> deferred = new ArrayList<>(), composite = new ArrayList<>();
    private Program finalProgram, shadowProgram;
    CustomUniforms custom;
    int noiseTex, lightmapTex, normalsTex, specularTex;
    int samplerCompare, samplerPlain, samplerLinear;
    /** texture.STAGE.BUFFER overrides: stage -> buffer index -> texture */
    private final Map<String, Map<Integer, Integer>> customTex = new HashMap<>();
    private final List<Integer> ownedTextures = new ArrayList<>();
    private int fixedFbo = -1, fixedFboVersion = -1, compositeFbo;
    public boolean shadowsEnabled;
    private boolean oldLighting, separateAo = true;
    public boolean vanillaClouds = true;
    /** shadowEntities / shadowPlayer properties */
    boolean shadowEntities = true, shadowEntitiesOther = true, shadowPlayer = true;

    public static final String[][] GBUFFER_FALLBACKS = {
            {"gbuffers_basic", null},
            {"gbuffers_line", "gbuffers_basic"},
            {"gbuffers_textured", "gbuffers_basic"},
            {"gbuffers_textured_lit", "gbuffers_textured"},
            {"gbuffers_skybasic", "gbuffers_basic"},
            {"gbuffers_skytextured", "gbuffers_textured"},
            {"gbuffers_clouds", "gbuffers_textured"},
            {"gbuffers_terrain", "gbuffers_textured_lit"},
            {"gbuffers_damagedblock", "gbuffers_terrain"},
            {"gbuffers_block", "gbuffers_terrain"},
            {"gbuffers_beaconbeam", "gbuffers_textured"},
            {"gbuffers_item", "gbuffers_textured_lit"},
            {"gbuffers_entities", "gbuffers_textured_lit"},
            {"gbuffers_entities_glowing", "gbuffers_entities"},
            {"gbuffers_lightning", "gbuffers_entities"},
            {"gbuffers_armor_glint", "gbuffers_textured"},
            {"gbuffers_spidereyes", "gbuffers_textured"},
            {"gbuffers_hand", "gbuffers_textured_lit"},
            {"gbuffers_hand_water", "gbuffers_hand"},
            {"gbuffers_weather", "gbuffers_textured_lit"},
            {"gbuffers_water", "gbuffers_terrain"},
    };

    public Pipeline(LoadedPack pack, int dimension) {
        this.pack = pack;
        this.dimension = dimension;
        this.frame = new FrameUniforms(this);
    }

    // ================================================================== creation

    public boolean create() {
        long t0 = System.nanoTime();
        frame.register();
        settings.applyProperties(pack.props);
        oldLighting = pack.props.getBool("oldLighting", false);
        separateAo = pack.props.getBool("separateAo", false);
        String clouds = pack.props.get("clouds");
        vanillaClouds = clouds == null || !clouds.trim().equalsIgnoreCase("off");
        custom = new CustomUniforms(pack.props, uniforms, frame.constants());
        shadowEntitiesOther = pack.props.getBool("shadowEntities", true);
        shadowPlayer = pack.props.getBool("shadowPlayer", shadowEntitiesOther);
        shadowEntities = shadowEntitiesOther || shadowPlayer;

        Map<String, LoadedPack.ProgramSource> sources = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (String[] g : GBUFFER_FALLBACKS) if (!g[0].equals("gbuffers_line")) names.add(g[0]); // needs core-profile attributes
        names.add("shadow");
        names.add("deferred");
        for (int i = 1; i < 16; i++) names.add("deferred" + i);
        names.add("composite");
        for (int i = 1; i < 16; i++) names.add("composite" + i);
        names.add("final");
        for (String n : names) {
            if (!enabled(n)) continue;
            LoadedPack.ProgramSource src = pack.program(n, dimension);
            if (src != null) sources.put(n, src);
        }
        if (sources.isEmpty()) {
            Shaders.LOGGER.error("Shader pack {} has no programs for dimension {}", pack.pack.name, dimension);
            return false;
        }
        // pipeline constants from every program
        for (LoadedPack.ProgramSource s : sources.values()) {
            settings.apply(Directives.consts(s.vertex, null));
            settings.apply(Directives.consts(s.fragment, s.fragmentMacros));
        }
        // compile
        int failed = 0;
        for (Map.Entry<String, LoadedPack.ProgramSource> e : sources.entrySet()) {
            String n = e.getKey();
            LoadedPack.ProgramSource src = e.getValue();
            Program.Stage stage = n.startsWith("gbuffers") ? Program.Stage.GBUFFERS : n.equals("shadow") ? Program.Stage.SHADOW
                    : n.startsWith("deferred") ? Program.Stage.DEFERRED : n.equals("final") ? Program.Stage.FINAL : Program.Stage.COMPOSITE;
            int[] db = Directives.drawBuffers(src.fragment);
            if (db == null) db = defaultDrawBuffers(src.fragment);
            Program p = Program.create(src, stage, db);
            if (p == null) {
                failed++;
                continue;
            }
            applyProgramProperties(p);
            programs.put(n, p);
        }
        if (failed > 0) Shaders.LOGGER.warn("{} shader program(s) failed to compile", failed);
        // fallbacks
        for (String[] g : GBUFFER_FALLBACKS) {
            String n = g[0];
            Program p = programs.get(n);
            String fb = g[1];
            while (p == null && fb != null) {
                p = programs.get(fb);
                if (p != null) break;
                String next = null;
                for (String[] h : GBUFFER_FALLBACKS) if (h[0].equals(fb)) next = h[1];
                fb = next;
            }
            resolved.put(n, p);
        }
        shadowProgram = programs.get("shadow");
        if (programs.containsKey("deferred")) deferred.add(programs.get("deferred"));
        for (int i = 1; i < 16; i++) if (programs.containsKey("deferred" + i)) deferred.add(programs.get("deferred" + i));
        if (programs.containsKey("composite")) composite.add(programs.get("composite"));
        for (int i = 1; i < 16; i++) if (programs.containsKey("composite" + i)) composite.add(programs.get("composite" + i));
        finalProgram = programs.get("final");

        // buffers in use: drawn or sampled
        for (Program p : programs.values()) {
            if (p.stage != Program.Stage.SHADOW && p.stage != Program.Stage.FINAL) for (int b : p.drawBuffers) if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS) settings.used[b] = true;
            if (p.stage == Program.Stage.SHADOW) for (int b : p.drawBuffers) if (b >= 0 && b < 2) settings.shadowColorUsed[b] = true;
        }
        settings.used[0] = true;
        // samplers (marks more used buffers) - resolve before creating targets
        for (Program p : programs.values()) p.resolve(uniforms, this::resolveSampler);
        shadowsEnabled = shadowProgram != null;

        // textures
        noiseTex = loadCustomTexture("texture.noise", true, true);
        if (noiseTex == 0) noiseTex = own(Textures.noise(settings.noiseTextureResolution));
        normalsTex = own(Textures.solid(127, 127, 255, 255));
        specularTex = own(Textures.solid(0, 0, 0, 0));
        lightmapTex = own(Textures.createLightmap());
        for (String k : pack.props.keysWithPrefix("texture.")) {
            String[] parts = k.split("\\.");
            if (parts.length != 3) continue;
            int b = PipelineSettings.bufferIndex(parts[2]);
            if (b < 0) continue;
            int t = loadCustomTexture(k, true, true);
            if (t != 0) customTex.computeIfAbsent(parts[1], s -> new HashMap<>()).put(b, t);
        }
        samplerCompare = Textures.sampler(true, true);
        samplerPlain = Textures.sampler(false, false);
        samplerLinear = Textures.sampler(false, true);
        compositeFbo = GL30.glGenFramebuffers();
        targets = new RenderTargets(settings);
        if (shadowsEnabled) shadow = new ShadowTargets(settings);
        Shaders.LOGGER.info("Shader pack {} ready: {} programs ({} deferred, {} composite{}), shadow map {}, {} ms",
                pack.pack.name, programs.size(), deferred.size(), composite.size(), finalProgram != null ? ", final" : "",
                shadowsEnabled ? settings.shadowMapResolution + "px/" + (int) settings.shadowDistance + " blocks" : "off",
                (System.nanoTime() - t0) / 1_000_000);
        return true;
    }

    private int own(int t) {
        if (t != 0) ownedTextures.add(t);
        return t;
    }

    private int loadCustomTexture(String key, boolean linear, boolean repeat) {
        String v = pack.props.get(key);
        if (v == null) return 0;
        v = v.trim();
        if (v.startsWith("minecraft:") || v.contains(" ")) return 0; // resource-pack textures / raw formats: not supported
        String path = v.startsWith("/") ? v.substring(1) : v;
        return own(Textures.fromPack(pack.pack, path, linear, repeat));
    }

    private static final Pattern FRAGDATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");

    private static int[] defaultDrawBuffers(String fs) {
        Matcher m = FRAGDATA.matcher(fs);
        int max = -1;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        if (max < 0) return new int[]{0};
        int[] r = new int[Math.min(max + 1, 8)];
        for (int i = 0; i < r.length; i++) r[i] = i;
        return r;
    }

    /** program.[worldN/]NAME.enabled = expression */
    private boolean enabled(String name) {
        String v = pack.props.get("program.world" + dimension + "/" + name + ".enabled");
        if (v == null) v = pack.props.get("program." + name + ".enabled");
        if (v == null) return true;
        v = v.trim();
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        Macros m = pack.propertyMacros();
        // "OPTION" means defined; anything else is an #if expression
        if (v.matches("!?[A-Za-z_][A-Za-z0-9_]*")) {
            boolean neg = v.startsWith("!");
            String n = neg ? v.substring(1) : v;
            boolean d = m.isDefined(n) && !"0".equals(m.valueOf(n)) && !"false".equals(m.valueOf(n));
            return neg != d;
        }
        return m.evaluate(v.replace("defined(", "defined ("));
    }

    private void applyProgramProperties(Program p) {
        String b = pack.props.get("blend." + p.name);
        if (b != null) {
            b = b.trim();
            if (b.equalsIgnoreCase("off")) p.blendOff = true;
            else {
                String[] parts = b.split("\\s+");
                if (parts.length == 4) {
                    p.blend = new int[]{blendFactor(parts[0]), blendFactor(parts[1]), blendFactor(parts[2]), blendFactor(parts[3])};
                }
            }
        }
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            String bb = pack.props.get("blend." + p.name + ".colortex" + i);
            if (bb != null && bb.trim().equalsIgnoreCase("off")) p.bufferBlendOff[i] = true;
        }
        String a = pack.props.get("alphaTest." + p.name);
        if (a != null) {
            a = a.trim();
            if (a.equalsIgnoreCase("off") || a.equalsIgnoreCase("false")) p.alphaOff = true;
            else {
                String[] parts = a.split("\\s+");
                if (parts.length == 2) {
                    p.alphaFunc = switch (parts[0].toUpperCase()) {
                        case "NEVER" -> GL11.GL_NEVER;
                        case "LESS" -> GL11.GL_LESS;
                        case "EQUAL" -> GL11.GL_EQUAL;
                        case "LEQUAL" -> GL11.GL_LEQUAL;
                        case "GREATER" -> GL11.GL_GREATER;
                        case "NOTEQUAL" -> GL11.GL_NOTEQUAL;
                        case "GEQUAL" -> GL11.GL_GEQUAL;
                        default -> GL11.GL_ALWAYS;
                    };
                    p.alphaRef = Directives.parseFloat(parts[1], 0.1f);
                }
            }
        }
    }

    private static int blendFactor(String s) {
        return switch (s.toUpperCase()) {
            case "ZERO" -> GL11.GL_ZERO;
            case "ONE" -> GL11.GL_ONE;
            case "SRC_COLOR" -> GL11.GL_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> GL11.GL_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> GL11.GL_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> GL11.GL_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> GL11.GL_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> GL11.GL_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> GL11.GL_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> GL11.GL_ONE_MINUS_DST_ALPHA;
            case "SRC_ALPHA_SATURATE" -> GL11.GL_SRC_ALPHA_SATURATE;
            default -> GL11.GL_ONE;
        };
    }

    // ================================================================== samplers

    private Program.Sampler resolveSampler(Program p, String name, int glType) {
        boolean compare = glType == GL20.GL_SAMPLER_2D_SHADOW;
        boolean gb = p.stage == Program.Stage.GBUFFERS || p.stage == Program.Stage.SHADOW;
        switch (name) {
            case "texture", "tex", "gtexture":
                if (gb) return Program.sampler(name, Program.SamplerKind.GTEXTURE, 0, false, 0);
                break;
            case "lightmap":
                return Program.sampler(name, Program.SamplerKind.LIGHTMAP, 0, false, 0);
            case "normals":
                return Program.sampler(name, Program.SamplerKind.NORMALS, 0, false, 0);
            case "specular":
                return Program.sampler(name, Program.SamplerKind.SPECULAR, 0, false, 0);
            case "noisetex":
                return Program.sampler(name, Program.SamplerKind.NOISE, 0, false, 0);
            case "depthtex0":
                return Program.sampler(name, Program.SamplerKind.DEPTHTEX, 0, compare, 0);
            case "depthtex1":
                return Program.sampler(name, Program.SamplerKind.DEPTHTEX, 1, compare, 0);
            case "depthtex2":
                return Program.sampler(name, Program.SamplerKind.DEPTHTEX, 2, compare, 0);
            case "shadow", "shadowtex0":
                settings.shadowUsed = true;
                return Program.sampler(name, Program.SamplerKind.SHADOWTEX, 0, compare || (settings.shadowHardwareFiltering || settings.shadowHardwareFilteringN[0]) && compare, 0);
            case "watershadow", "shadowtex1":
                settings.shadowUsed = true;
                return Program.sampler(name, Program.SamplerKind.SHADOWTEX, 1, compare, 0);
            case "shadowcolor", "shadowcolor0":
                settings.shadowUsed = true;
                settings.shadowColorUsed[0] = true;
                return Program.sampler(name, Program.SamplerKind.SHADOWCOLOR, 0, false, 0);
            case "shadowcolor1":
                settings.shadowUsed = true;
                settings.shadowColorUsed[1] = true;
                return Program.sampler(name, Program.SamplerKind.SHADOWCOLOR, 1, false, 0);
            default:
                break;
        }
        int b = PipelineSettings.bufferIndex(name);
        if (b >= 0) {
            settings.used[b] = true;
            return Program.sampler(name, Program.SamplerKind.COLORTEX, b, false, 0);
        }
        return Program.sampler(name, Program.SamplerKind.UNKNOWN, 0, false, 0);
    }

    private String stageName(Program p) {
        return switch (p.stage) {
            case SHADOW -> "shadow";
            case GBUFFERS -> "gbuffers";
            case DEFERRED -> "deferred";
            default -> "composite";
        };
    }

    /** Binds the textures of p's samplers (p must be the current program). */
    private void bindSamplers(Program p) {
        Map<Integer, Integer> over = customTex.get(stageName(p));
        for (Program.Sampler s : p.samplers) {
            int tex;
            int sampler = 0;
            switch (s.kind) {
                case LIGHTMAP -> tex = lightmapTex;
                case NORMALS -> tex = normalsTex;
                case SPECULAR -> tex = specularTex;
                case NOISE -> tex = noiseTex;
                case DEPTHTEX -> {
                    tex = s.index == 0 ? targets.depthTex0 : s.index == 1 ? targets.depthTex1 : targets.depthTex2;
                    sampler = s.shadowCompare ? samplerCompare : samplerPlain;
                }
                case SHADOWTEX -> {
                    tex = shadow == null ? 0 : (s.index == 0 ? shadow.depth0 : shadow.depth1);
                    sampler = s.shadowCompare ? samplerCompare : samplerLinear;
                }
                case SHADOWCOLOR -> tex = shadow == null ? 0 : shadow.color[s.index];
                case COLORTEX -> {
                    Integer o = over != null ? over.get(s.index) : null;
                    tex = o != null ? o : targets.readTexture(s.index);
                }
                default -> tex = 0;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + s.unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL33.glBindSampler(s.unit, sampler);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void unbindSamplers(Program p) {
        for (Program.Sampler s : p.samplers) GL33.glBindSampler(s.unit, 0);
    }

    // ================================================================== program use

    Program current;
    private boolean blendWasEnabled, alphaWasEnabled;
    private boolean overrideBlend, overrideAlpha;
    private final IntBuffer drawBufs = BufferUtils.createIntBuffer(16);

    public Program program(String name) {
        return resolved.get(name);
    }

    public boolean hasProgram(String name) {
        return programs.containsKey(name);
    }

    /** Switches to a gbuffers program (null = fixed function into colortex0). */
    public void useGbuffers(Program p) {
        if (current != null) endProgram();
        bindGbufferFbo(p);
        if (p == null) {
            GL20.glUseProgram(0);
            return;
        }
        GL20.glUseProgram(p.id);
        current = p;
        bindSamplers(p);
        uploadUniforms(p);
        applyStateOverrides(p);
    }

    private void applyStateOverrides(Program p) {
        overrideBlend = p.blendOff || p.blend != null;
        if (overrideBlend) {
            blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
            if (p.blendOff) GL11.glDisable(GL11.GL_BLEND);
            else {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(p.blend[0], p.blend[1], p.blend[2], p.blend[3]);
            }
        }
        if (p.targets != null) {
            for (int k = 0; k < p.targets.length; k++) {
                if (p.bufferBlendOff[p.targets[k]]) GL30.glDisablei(GL11.GL_BLEND, k);
            }
        }
        overrideAlpha = p.alphaOff || p.alphaFunc != 0;
        if (overrideAlpha) {
            alphaWasEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            if (p.alphaOff) GL11.glDisable(GL11.GL_ALPHA_TEST);
            else {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(p.alphaFunc, p.alphaRef);
            }
        }
    }

    private void endProgram() {
        Program p = current;
        if (p == null) return;
        if (overrideBlend) {
            if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
            else GL11.glDisable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            overrideBlend = false;
        } else if (p.targets != null) {
            boolean any = false;
            for (int k = 0; k < p.targets.length; k++) any |= p.bufferBlendOff[p.targets[k]];
            if (any) {
                if (GL11.glIsEnabled(GL11.GL_BLEND)) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
            }
        }
        if (overrideAlpha) {
            if (alphaWasEnabled) GL11.glEnable(GL11.GL_ALPHA_TEST);
            else GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            overrideAlpha = false;
        }
        unbindSamplers(p);
        current = null;
    }

    /** Uploads uniforms that changed (call after per-draw uniforms such as entityId change). */
    public void refreshUniforms() {
        if (current != null) uploadUniforms(current);
    }

    private void uploadUniforms(Program p) {
        for (Uniforms.Binding b : p.bindings) Uniforms.upload(b);
    }

    private void bindGbufferFbo(Program p) {
        int[] db = p == null ? new int[]{0} : p.drawBuffers;
        if (p != null) {
            if (p.fbo == 0) p.fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, p.fbo);
            if (p.fboVersion != targets.version) {
                attachGbuffers(db, p);
                p.fboVersion = targets.version;
            }
        } else {
            if (fixedFbo <= 0) fixedFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fixedFbo);
            if (fixedFboVersion != targets.version) {
                attachGbuffers(db, null);
                fixedFboVersion = targets.version;
            }
        }
        GL11.glViewport(0, 0, targets.width, targets.height);
    }

    private void attachGbuffers(int[] db, Program p) {
        List<Integer> valid = new ArrayList<>();
        for (int b : db) if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS && settings.used[b]) valid.add(b);
        for (int k = 0; k < 8; k++) {
            int t = k < valid.size() ? targets.readTexture(valid.get(k)) : 0;
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + k, GL11.GL_TEXTURE_2D, t, 0);
        }
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, targets.depthTex0, 0);
        drawBufs.clear();
        // outputs map 1:1 to the program's DRAWBUFFERS entries, so keep positions (missing buffers -> NONE)
        int k = 0;
        int outputs = Math.min(db.length, 8);
        int[] map = new int[outputs];
        for (int i = 0; i < outputs; i++) {
            int b = db[i];
            if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS && settings.used[b]) {
                drawBufs.put(GL30.GL_COLOR_ATTACHMENT0 + k);
                map[i] = b;
                k++;
            } else {
                drawBufs.put(GL11.GL_NONE);
                map[i] = 0;
            }
        }
        drawBufs.flip();
        GL20.glDrawBuffers(drawBufs);
        if (p != null) p.targets = map;
    }

    // ================================================================== frame

    public boolean inFrame;
    private long statSince, statShadowNs;
    private int statFrames;
    private float[] fogColor = {0, 0, 0, 0};

    /** Start of the world render (before vanilla clears): sizes the targets. */
    public void beginFrame(int width, int height) {
        targets.ensureSize(width, height);
        inFrame = true;
        java.util.Arrays.fill(targets.mipDirty, true);
    }

    /** Replaces vanilla's colour/depth clear at the start of the world render. */
    public void clear(float r, float g, float b) {
        fogColor = new float[]{r, g, b, 1};
        targets.clearForFrame(fogColor);
        useGbuffers(null);
    }

    /** After the camera is set up: matrices and uniforms, the shadow pass, then the gbuffers stage. */
    public void afterCamera(float delta) {
        frame.update(delta);
        custom.update(frame.frameTimeSec);
        GpuTimer.switchTo(GpuTimer.SHADOW);
        if (shadowsEnabled && shadow != null) ShadowPass.render(this, delta);
        GpuTimer.switchTo(GpuTimer.OPAQUE);
        useGbuffers(null);
        GL11.glDepthMask(true);
    }

    private boolean deferredDone;

    /** Before translucent terrain: depthtex1 and the deferred passes. */
    public void beforeTranslucent() {
        if (deferredDone) return;
        deferredDone = true;
        GpuTimer.switchTo(GpuTimer.DEFERRED);
        Program saved = current;
        if (current != null) endProgram();
        targets.copyDepth(targets.depthTex1);
        if (!deferred.isEmpty()) {
            // full-screen passes bind buffers on texture unit 0: keep vanilla's texture (terrain.png for water)
            int tex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            runPasses(deferred, false);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex0);
        }
        GL20.glUseProgram(0);
        bindGbufferFbo(saved);
        if (saved != null) useGbuffers(saved);
        GpuTimer.switchTo(GpuTimer.TRANSLUCENT);
    }

    public void beforeHand() {
        targets.copyDepth(targets.depthTex2);
    }

    /** End of the world render: composites and final to the screen. */
    public void endFrame() {
        if (!inFrame) return;
        if (current != null) endProgram();
        if (!deferredDone) {
            GpuTimer.switchTo(GpuTimer.DEFERRED);
            targets.copyDepth(targets.depthTex1);
            if (!deferred.isEmpty()) runPasses(deferred, false);
        }
        deferredDone = false;
        boolean dump = DebugDump.due();
        if (dump) {
            DebugDump.dump(this, "a-gbuffers");
            Shaders.LOGGER.info("shadow pass: {} sections, {} ms", ShadowPass.lastSections, ShadowPass.lastNs / 1e6);
        }
        GpuTimer.switchTo(GpuTimer.COMPOSITE);
        runPasses(composite, false);
        if (dump) DebugDump.dump(this, "b-composite");
        GpuTimer.switchTo(GpuTimer.FINAL);
        runFinal();
        GpuTimer.endFrame();
        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glDrawBuffer(GL11.GL_BACK);
        GL11.glViewport(0, 0, targets.width, targets.height);
        inFrame = false;
        frame.endFrame();
        statFrames++;
        statShadowNs += ShadowPass.lastNs;
        long now = System.nanoTime();
        if (statSince == 0) statSince = now;
        if (now - statSince > 10_000_000_000L) {
            if (dev.quicksilver.QsConfig.debugInfo) {
                Shaders.LOGGER.info("[shaders] {} fps | shadow pass {} ms/frame (cpu), {} sections, {} entities | {}x{}, shadow map {}",
                        Math.round(statFrames / ((now - statSince) / 1e9)), String.format("%.2f", statShadowNs / 1e6 / statFrames),
                        ShadowPass.lastSections, ShadowPass.lastEntities, targets.width, targets.height, shadowsEnabled ? settings.shadowMapResolution : 0);
            }
            statSince = now;
            statFrames = 0;
            statShadowNs = 0;
        }
    }

    /** Runs full-screen passes: each reads the current buffers and writes the alternate ones, which then become current. */
    private void runPasses(List<Program> passes, boolean unused) {
        if (passes.isEmpty()) return;
        beginFullscreen();
        for (Program p : passes) {
            // mipmaps of buffers that want them
            for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
                if (settings.used[i] && settings.mipmap[i] && targets.mipDirty[i]) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, targets.readTexture(i));
                    GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                    targets.mipDirty[i] = false;
                }
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, compositeFbo);
            int n = 0;
            int vw = targets.width, vh = targets.height;
            drawBufs.clear();
            for (int k = 0; k < 8; k++) {
                int t = 0;
                if (k < p.drawBuffers.length) {
                    int b = p.drawBuffers[k];
                    if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS && settings.used[b]) {
                        t = targets.altTexture(b);
                        if (n == 0) {
                            vw = targets.bufferWidth(b);
                            vh = targets.bufferHeight(b);
                        }
                        n++;
                    }
                }
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + k, GL11.GL_TEXTURE_2D, t, 0);
            }
            int k2 = 0;
            for (int i = 0; i < Math.min(p.drawBuffers.length, 8); i++) {
                int b = p.drawBuffers[i];
                if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS && settings.used[b]) drawBufs.put(GL30.GL_COLOR_ATTACHMENT0 + k2++);
                else drawBufs.put(GL11.GL_NONE);
            }
            drawBufs.flip();
            if (n == 0) continue;
            GL20.glDrawBuffers(drawBufs);
            GL11.glViewport(0, 0, vw, vh);
            // read buffers that this pass doesn't write keep their content; written buffers must be copied first if
            // the pass may not write every pixel - OptiFine semantics: passes write full screen quads
            GL20.glUseProgram(p.id);
            bindSamplers(p);
            uploadUniforms(p);
            applyStateOverrides(p);
            drawQuad();
            current = p;
            endProgram();
            for (int i = 0; i < Math.min(p.drawBuffers.length, 8); i++) {
                int b = p.drawBuffers[i];
                if (b >= 0 && b < PipelineSettings.COLOR_BUFFERS && settings.used[b]) {
                    targets.flip(b);
                    targets.mipDirty[b] = true;
                }
            }
        }
        endFullscreen();
    }

    private void runFinal() {
        beginFullscreen();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glDrawBuffer(GL11.GL_BACK);
        GL11.glViewport(0, 0, targets.width, targets.height);
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            if (settings.used[i] && settings.mipmap[i] && targets.mipDirty[i]) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, targets.readTexture(i));
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                targets.mipDirty[i] = false;
            }
        }
        if (finalProgram != null) {
            GL20.glUseProgram(finalProgram.id);
            bindSamplers(finalProgram);
            uploadUniforms(finalProgram);
            drawQuad();
            unbindSamplers(finalProgram);
        } else {
            // no final pass: blit colortex0
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, targets.readTexture(0));
            GL11.glColor4f(1, 1, 1, 1);
            drawQuad();
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        endFullscreen();
    }

    private void beginFullscreen() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 1, 0, 1, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColorMask(true, true, true, true);
    }

    private void endFullscreen() {
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private static void drawQuad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glNormal3f(0, 0, 1);
        GL11.glTexCoord2f(0, 0);
        GL11.glVertex3f(0, 0, 0);
        GL11.glTexCoord2f(1, 0);
        GL11.glVertex3f(1, 0, 0);
        GL11.glTexCoord2f(1, 1);
        GL11.glVertex3f(1, 1, 0);
        GL11.glTexCoord2f(0, 1);
        GL11.glVertex3f(0, 1, 0);
        GL11.glEnd();
    }

    // ================================================================== shadow pass support

    Program shadowProgram() {
        return shadowProgram;
    }

    void useShadowProgram() {
        GL20.glUseProgram(shadowProgram.id);
        current = shadowProgram;
        bindSamplers(shadowProgram);
        uploadUniforms(shadowProgram);
    }

    void endShadowProgram() {
        if (current != null) endProgram();
        GL20.glUseProgram(0);
    }

    public void frame_entityId(int id) {
        frame.entityId.set(id);
    }

    public void frame_blockEntityId(int id) {
        frame.blockEntityId.set(id);
    }

    public void frame_entityColor(float r, float g, float b, float a) {
        frame.entityColor.set(r, g, b, a);
    }

    public boolean oldLighting() {
        return oldLighting;
    }

    public boolean separateAo() {
        return separateAo;
    }

    public void destroy() {
        if (current != null) endProgram();
        GL20.glUseProgram(0);
        for (Program p : programs.values()) p.delete();
        programs.clear();
        if (targets != null) targets.destroy();
        if (shadow != null) shadow.destroy();
        for (int t : ownedTextures) GL11.glDeleteTextures(t);
        ownedTextures.clear();
        if (fixedFbo > 0) GL30.glDeleteFramebuffers(fixedFbo);
        if (compositeFbo != 0) GL30.glDeleteFramebuffers(compositeFbo);
        GL33.glDeleteSamplers(samplerCompare);
        GL33.glDeleteSamplers(samplerPlain);
        GL33.glDeleteSamplers(samplerLinear);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }
}
