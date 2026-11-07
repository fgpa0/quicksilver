package dev.quicksilver.shader.pipeline;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Values of the uniforms shader programs can declare. Each value has a version that changes when the value
 * changes, so programs only upload what changed since they last used it.
 */
public final class Uniforms {
    public static final class Value {
        public final String name;
        final float[] v;
        int version = 1;

        Value(String name, int size) {
            this.name = name;
            this.v = new float[size];
        }

        public void set(float a) {
            if (v[0] != a) {
                v[0] = a;
                version++;
            }
        }

        public void set(float a, float b) {
            if (v[0] != a || v[1] != b) {
                v[0] = a;
                v[1] = b;
                version++;
            }
        }

        public void set(float a, float b, float c) {
            if (v[0] != a || v[1] != b || v[2] != c) {
                v[0] = a;
                v[1] = b;
                v[2] = c;
                version++;
            }
        }

        public void set(float a, float b, float c, float d) {
            if (v[0] != a || v[1] != b || v[2] != c || v[3] != d) {
                v[0] = a;
                v[1] = b;
                v[2] = c;
                v[3] = d;
                version++;
            }
        }

        public void set(float[] m) {
            boolean diff = false;
            for (int i = 0; i < v.length && i < m.length; i++) {
                if (v[i] != m[i]) {
                    v[i] = m[i];
                    diff = true;
                }
            }
            if (diff) version++;
        }

        public void set(double[] d) {
            boolean diff = false;
            for (int i = 0; i < v.length; i++) {
                float f = (float) d[Math.min(i, d.length - 1)];
                if (v[i] != f) {
                    v[i] = f;
                    diff = true;
                }
            }
            if (diff) version++;
        }

        public float get() {
            return v[0];
        }

        public float[] raw() {
            return v;
        }

        public double[] asDoubles() {
            double[] d = new double[v.length];
            for (int i = 0; i < v.length; i++) d[i] = v[i];
            return d;
        }
    }

    public final Map<String, Value> values = new HashMap<>();

    public Value add(String name, int size) {
        return values.computeIfAbsent(name, n -> new Value(n, size));
    }

    public Value get(String name) {
        return values.get(name);
    }

    // ------------------------------------------------------------------ per-program binding

    public static final class Binding {
        final int location, glType, count;
        final Value value;
        int lastVersion = -1;

        Binding(int location, int glType, int count, Value value) {
            this.location = location;
            this.glType = glType;
            this.count = count;
            this.value = value;
        }
    }

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    /** Uploads b's value if it changed since the last upload (program must be in use). */
    static void upload(Binding b) {
        Value val = b.value;
        if (b.lastVersion == val.version) return;
        b.lastVersion = val.version;
        float[] v = val.v;
        int loc = b.location;
        switch (b.glType) {
            case GL11.GL_FLOAT -> GL20.glUniform1f(loc, v[0]);
            case GL20.GL_FLOAT_VEC2 -> GL20.glUniform2f(loc, v[0], at(v, 1));
            case GL20.GL_FLOAT_VEC3 -> GL20.glUniform3f(loc, v[0], at(v, 1), at(v, 2));
            case GL20.GL_FLOAT_VEC4 -> GL20.glUniform4f(loc, v[0], at(v, 1), at(v, 2), at(v, 3));
            case GL11.GL_INT, GL20.GL_BOOL -> GL20.glUniform1i(loc, Math.round(v[0]));
            case GL20.GL_INT_VEC2, GL20.GL_BOOL_VEC2 -> GL20.glUniform2i(loc, Math.round(v[0]), Math.round(at(v, 1)));
            case GL20.GL_INT_VEC3, GL20.GL_BOOL_VEC3 -> GL20.glUniform3i(loc, Math.round(v[0]), Math.round(at(v, 1)), Math.round(at(v, 2)));
            case GL20.GL_INT_VEC4, GL20.GL_BOOL_VEC4 -> GL20.glUniform4i(loc, Math.round(v[0]), Math.round(at(v, 1)), Math.round(at(v, 2)), Math.round(at(v, 3)));
            case GL20.GL_FLOAT_MAT4 -> {
                if (v.length < 16) return;
                MAT.clear();
                MAT.put(v, 0, 16).flip();
                GL20.glUniformMatrix4(loc, false, MAT);
            }
            case 0x1405 /* GL_UNSIGNED_INT */ -> GL20.glUniform1i(loc, Math.round(v[0]));
            default -> {
            }
        }
    }

    private static float at(float[] v, int i) {
        return i < v.length ? v[i] : v[v.length - 1];
    }
}
