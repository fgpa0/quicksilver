package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.LoadedPack;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

/** A linked shader program with its uniform bindings, samplers and draw buffers. */
public final class Program {
    public enum Stage { SHADOW, GBUFFERS, DEFERRED, COMPOSITE, FINAL }

    public final String name;
    public final Stage stage;
    public final int id;
    public final int[] drawBuffers;
    final List<Uniforms.Binding> bindings = new ArrayList<>();
    /** sampler unit -> what to bind */
    final List<Sampler> samplers = new ArrayList<>();
    /** blend override from shaders.properties ("off" = disabled; null = keep vanilla's) */
    public int[] blend;
    public boolean blendOff;
    /** per-buffer blend disabled (blend.program.colortexN=off) */
    public final boolean[] bufferBlendOff = new boolean[PipelineSettings.COLOR_BUFFERS];
    /** alpha test override: func, ref (func 0 = keep vanilla) */
    public int alphaFunc;
    public float alphaRef;
    public boolean alphaOff;
    /** gbuffers: FBO for this program's draw buffers */
    int fbo;
    int fboVersion = -1;
    /** draw buffers mapped to existing buffers (writes to buffers that don't exist are dropped) */
    int[] targets;

    public enum SamplerKind { GTEXTURE, LIGHTMAP, NORMALS, SPECULAR, COLORTEX, DEPTHTEX, SHADOWTEX, SHADOWCOLOR, NOISE, CUSTOM, UNKNOWN }

    static final class Sampler {
        final String name;
        final SamplerKind kind;
        final int index;
        final int unit;
        final boolean shadowCompare;
        final int customTexture;

        Sampler(String name, SamplerKind kind, int index, int unit, boolean shadowCompare, int customTexture) {
            this.name = name;
            this.kind = kind;
            this.index = index;
            this.unit = unit;
            this.shadowCompare = shadowCompare;
            this.customTexture = customTexture;
        }
    }

    private Program(String name, Stage stage, int id, int[] drawBuffers) {
        this.name = name;
        this.stage = stage;
        this.id = id;
        this.drawBuffers = drawBuffers;
    }

    /** Compiles and links; returns null (and logs) on failure. */
    public static Program create(LoadedPack.ProgramSource src, Stage stage, int[] drawBuffers) {
        int vs = compile(GL20.GL_VERTEX_SHADER, src.vertex, src.vertexPath, src.vertexLines);
        if (vs == 0) return null;
        int gs = 0;
        if (src.geometry != null) {
            gs = compile(GL32.GL_GEOMETRY_SHADER, src.geometry, src.geometryPath, src.geometryLines);
            if (gs == 0) {
                GL20.glDeleteShader(vs);
                return null;
            }
        }
        int fs = compile(GL20.GL_FRAGMENT_SHADER, src.fragment, src.fragmentPath, src.fragmentLines);
        if (fs == 0) {
            GL20.glDeleteShader(vs);
            if (gs != 0) GL20.glDeleteShader(gs);
            return null;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        if (gs != 0) GL20.glAttachShader(p, gs);
        GL20.glAttachShader(p, fs);
        GL20.glBindAttribLocation(p, dev.quicksilver.shader.ShaderBridge.ATTRIB_ENTITY, "mc_Entity");
        GL20.glBindAttribLocation(p, dev.quicksilver.shader.ShaderBridge.ATTRIB_MID_TEX, "mc_midTexCoord");
        GL20.glBindAttribLocation(p, dev.quicksilver.shader.ShaderBridge.ATTRIB_TANGENT, "at_tangent");
        GL20.glLinkProgram(p);
        GL20.glDetachShader(p, vs);
        GL20.glDeleteShader(vs);
        if (gs != 0) {
            GL20.glDetachShader(p, gs);
            GL20.glDeleteShader(gs);
        }
        GL20.glDetachShader(p, fs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(p, 8192);
            Shaders.LOGGER.error("Linking {} failed: {}", src.name, log);
            GL20.glDeleteProgram(p);
            return null;
        }
        String log = GL20.glGetProgramInfoLog(p, 8192);
        if (log != null && !log.isBlank() && Shaders.verbose()) Shaders.LOGGER.info("Link log {}: {}", src.name, log.trim());
        return new Program(src.name, stage, p, drawBuffers);
    }

    private static int compile(int type, String source, String path, List<String> origins) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        String log = GL20.glGetShaderInfoLog(s, 16384);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            Shaders.LOGGER.error("Compiling {} failed:\n{}", path, annotate(log, origins));
            if (Shaders.verbose()) Shaders.dumpSource(path, source);
            GL20.glDeleteShader(s);
            return 0;
        }
        if (log != null && !log.isBlank() && Shaders.verbose()) Shaders.LOGGER.info("Compile log {}: {}", path, log.trim());
        return s;
    }

    /** Adds the original file:line to driver messages of the form "0(123)" or "0:123". */
    private static String annotate(String log, List<String> origins) {
        if (log == null || origins == null) return log;
        StringBuilder sb = new StringBuilder();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\s*(?:ERROR: )?\\d+[:(](\\d+)");
        for (String line : log.split("\n")) {
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                int ln = Integer.parseInt(m.group(1)) - 1;
                if (ln >= 0 && ln < origins.size()) line = line + "   [" + origins.get(ln) + "]";
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ uniforms and samplers

    /** Resolves the active uniforms against the uniform registry and assigns sampler units. */
    void resolve(Uniforms uniforms, SamplerResolver resolver) {
        GL20.glUseProgram(id);
        int n = GL20.glGetProgrami(id, GL20.GL_ACTIVE_UNIFORMS);
        IntBuffer sizeType = BufferUtils.createIntBuffer(2);
        int nextUnit = stage == Stage.GBUFFERS || stage == Stage.SHADOW ? 4 : 0;
        List<String[]> pendingSamplers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sizeType.clear();
            String uname = GL20.glGetActiveUniform(id, i, 256, sizeType);
            if (uname.endsWith("[0]")) uname = uname.substring(0, uname.length() - 3);
            if (uname.startsWith("gl_")) continue;
            int t = sizeType.get(1);
            int loc = GL20.glGetUniformLocation(id, uname);
            if (loc < 0) continue;
            if (isSampler(t)) {
                pendingSamplers.add(new String[]{uname, Integer.toString(t), Integer.toString(loc)});
                continue;
            }
            Uniforms.Value v = uniforms.get(uname);
            if (v != null) bindings.add(new Uniforms.Binding(loc, t, sizeType.get(0), v));
        }
        // fixed units first (gbuffers: texture 0, lightmap 1, normals 2, specular 3)
        for (String[] ps : pendingSamplers) {
            String uname = ps[0];
            int t = Integer.parseInt(ps[1]);
            int loc = Integer.parseInt(ps[2]);
            Sampler s = resolver.resolve(this, uname, t);
            int unit;
            if (stage == Stage.GBUFFERS || stage == Stage.SHADOW) {
                unit = switch (s.kind) {
                    case GTEXTURE -> 0;
                    case LIGHTMAP -> 1;
                    case NORMALS -> 2;
                    case SPECULAR -> 3;
                    default -> nextUnit++;
                };
            } else {
                unit = nextUnit++;
            }
            GL20.glUniform1i(loc, unit);
            if (s.kind != SamplerKind.GTEXTURE) samplers.add(new Sampler(uname, s.kind, s.index, unit, s.shadowCompare, s.customTexture));
        }
        GL20.glUseProgram(0);
    }

    static boolean isSampler(int t) {
        return switch (t) {
            case GL20.GL_SAMPLER_1D, GL20.GL_SAMPLER_2D, GL20.GL_SAMPLER_3D, GL20.GL_SAMPLER_CUBE, GL20.GL_SAMPLER_1D_SHADOW, GL20.GL_SAMPLER_2D_SHADOW,
                 0x8DCA /* isampler2D */, 0x8DD2 /* usampler2D */, 0x8DCB /* isampler3D */, 0x8DD3 /* usampler3D */, 0x8B63 /* sampler2DRect */,
                 0x8DC1 /* sampler2DArray */, 0x8B64 /* sampler2DRectShadow */ -> true;
            default -> false;
        };
    }

    interface SamplerResolver {
        Sampler resolve(Program p, String name, int glType);
    }

    static Sampler sampler(String name, SamplerKind kind, int index, boolean compare, int custom) {
        return new Sampler(name, kind, index, -1, compare, custom);
    }

    public void delete() {
        GL20.glDeleteProgram(id);
        if (fbo != 0) org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
    }
}
