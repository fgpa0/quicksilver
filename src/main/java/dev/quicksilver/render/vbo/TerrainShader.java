package dev.quicksilver.render.vbo;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * Small program for the plain (no shader pack) terrain pass. Vertices carry the block light and the sky light they
 * receive (not darkened for the time of day); the program applies the table-based brightness and the time of day, so a
 * changing sky never needs a rebuild. Fog and the fade-in alpha are done here too (fixed-function fog is not applied
 * while a program is bound).
 */
public final class TerrainShader {
    private TerrainShader() {}

    /** generic attribute: (block light, sky light) as unsigned bytes, level * 17 */
    public static final int ATTRIB_LIGHT = 7;

    private static final String VERT = """
            #version 120
            attribute vec2 qsLight;
            uniform float qsDark;
            uniform float qsRadial;
            uniform float qsTable[16];
            varying vec4 vColor;
            varying float vFog;
            float tl(float x) {
                x = clamp(x, 0.0, 15.0);
                int i = int(floor(x));
                float f = x - float(i);
                int j = i < 15 ? i + 1 : 15;
                return mix(qsTable[i], qsTable[j], f);
            }
            void main() {
                vec4 ev = gl_ModelViewMatrix * gl_Vertex;
                gl_Position = gl_ProjectionMatrix * ev;
                gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
                float b = qsLight.x * (15.0 / 255.0);
                float s = qsLight.y * (15.0 / 255.0);
                float br = max(tl(b), tl(s - qsDark));
                vColor = vec4(gl_Color.rgb * br, gl_Color.a);
                vFog = qsRadial > 0.5 ? length(ev.xyz) : abs(ev.z);
            }
            """;

    private static final String FRAG = """
            #version 120
            uniform sampler2D qsTex;
            uniform float qsAlpha;
            uniform int qsFog;
            varying vec4 vColor;
            varying float vFog;
            void main() {
                vec4 c = texture2D(qsTex, gl_TexCoord[0].st) * vColor;
                c.a *= qsAlpha;
                float f = 1.0;
                if (qsFog == 1) f = clamp((gl_Fog.end - vFog) * gl_Fog.scale, 0.0, 1.0);
                else if (qsFog == 2) f = clamp(exp(-gl_Fog.density * vFog), 0.0, 1.0);
                else if (qsFog == 3) { float e = gl_Fog.density * vFog; f = clamp(exp(-e * e), 0.0, 1.0); }
                c.rgb = mix(gl_Fog.color.rgb, c.rgb, f);
                gl_FragColor = c;
            }
            """;

    private static int program, locDark, locTable, locAlpha, locFog, locTex, locRadial;
    private static boolean radial;
    private static volatile boolean ready;
    private static boolean tried;
    private static final FloatBuffer TABLE = BufferUtils.createFloatBuffer(16);

    /** True if terrain meshes carry light levels and the program applies the time of day. Decided once (GL context needed). */
    public static boolean enabled() {
        if (!tried) {
            tried = true;
            ready = QsConfig.skyShader && !Boolean.getBoolean("quicksilver.noSkyShader") && init();
            Quicksilver.LOGGER.info("GPU time of day for terrain: {}", ready ? "enabled" : "disabled");
        }
        return ready;
    }

    /** Same answer as {@link #enabled()} without touching GL (worker threads); false until it was decided. */
    public static boolean decided() {
        return ready;
    }

    private static boolean init() {
        try {
            ContextCapabilities c = GLContext.getCapabilities();
            if (!c.OpenGL20) return false;
            int v = compile(GL20.GL_VERTEX_SHADER, VERT);
            int f = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
            if (v == 0 || f == 0) return false;
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, v);
            GL20.glAttachShader(p, f);
            GL20.glBindAttribLocation(p, ATTRIB_LIGHT, "qsLight");
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                Quicksilver.LOGGER.warn("Terrain shader link failed: {}", GL20.glGetProgramInfoLog(p, 4096));
                return false;
            }
            GL20.glDeleteShader(v);
            GL20.glDeleteShader(f);
            program = p;
            locDark = GL20.glGetUniformLocation(p, "qsDark");
            locTable = GL20.glGetUniformLocation(p, "qsTable");
            locAlpha = GL20.glGetUniformLocation(p, "qsAlpha");
            locFog = GL20.glGetUniformLocation(p, "qsFog");
            locTex = GL20.glGetUniformLocation(p, "qsTex");
            locRadial = GL20.glGetUniformLocation(p, "qsRadial");
            // vanilla asks for radial fog distance where NV_fog_distance exists, else the fixed-function default (eye depth)
            radial = c.GL_NV_fog_distance;
            return true;
        } catch (Throwable t) {
            Quicksilver.LOGGER.warn("Terrain shader unavailable", t);
            return false;
        }
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            Quicksilver.LOGGER.warn("Terrain shader compile failed: {}", GL20.glGetShaderInfoLog(s, 4096));
            return 0;
        }
        return s;
    }

    /** Bind the program and set the per-frame uniforms. */
    public static void begin(float[] lightTable, int skyDarken) {
        GL20.glUseProgram(program);
        GL20.glUniform1i(locTex, 0);
        GL20.glUniform1f(locDark, skyDarken);
        TABLE.clear();
        TABLE.put(lightTable, 0, 16).flip();
        GL20.glUniform1(locTable, TABLE);
        GL20.glUniform1f(locAlpha, 1f);
        int mode = 0;
        if (GL11.glIsEnabled(GL11.GL_FOG)) {
            int m = GL11.glGetInteger(GL11.GL_FOG_MODE);
            mode = m == GL11.GL_LINEAR ? 1 : (m == GL11.GL_EXP ? 2 : 3);
        }
        GL20.glUniform1i(locFog, mode);
        GL20.glUniform1f(locRadial, radial ? 1f : 0f);
        GL20.glEnableVertexAttribArray(ATTRIB_LIGHT);
    }

    public static void alpha(float a) {
        GL20.glUniform1f(locAlpha, a);
    }

    public static void end() {
        GL20.glDisableVertexAttribArray(ATTRIB_LIGHT);
        GL20.glUseProgram(0);
    }
}
