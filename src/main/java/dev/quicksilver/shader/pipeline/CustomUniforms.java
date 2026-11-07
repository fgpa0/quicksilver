package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.PropertiesFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** uniform.TYPE.NAME and variable.TYPE.NAME entries of shaders.properties, evaluated once per frame in order. */
public final class CustomUniforms implements Expr.Ctx {
    private record Entry(String name, int size, boolean isInt, boolean isBool, Expr expr, Uniforms.Value value) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Uniforms uniforms;
    private final Map<String, double[]> variables = new HashMap<>();
    private final Map<String, Double> constants;
    private double frameTime;

    public CustomUniforms(PropertiesFile props, Uniforms uniforms, Map<String, Double> constants) {
        this.uniforms = uniforms;
        this.constants = constants;
        for (Map.Entry<String, String> e : props.map.entrySet()) {
            String k = e.getKey();
            boolean uniform = k.startsWith("uniform.");
            if (!uniform && !k.startsWith("variable.")) continue;
            String[] parts = k.split("\\.");
            if (parts.length != 3) continue;
            String type = parts[1], name = parts[2];
            int size = switch (type) {
                case "vec2", "ivec2" -> 2;
                case "vec3", "ivec3" -> 3;
                case "vec4", "ivec4" -> 4;
                default -> 1;
            };
            try {
                Expr expr = Expr.parse(e.getValue(), constants);
                Uniforms.Value v = uniform ? uniforms.add(name, size) : null;
                entries.add(new Entry(name, size, type.startsWith("i") || type.equals("int"), type.equals("bool"), expr, v));
            } catch (RuntimeException ex) {
                Shaders.LOGGER.warn("Custom uniform {}: {}", k, ex.getMessage());
            }
        }
    }

    public void update(double frameTimeSec) {
        this.frameTime = frameTimeSec;
        for (Entry e : entries) {
            double[] r;
            try {
                r = e.expr.eval(this);
            } catch (RuntimeException ex) {
                r = new double[]{0};
            }
            if (e.isInt || e.isBool) {
                r = r.clone();
                for (int i = 0; i < r.length; i++) r[i] = e.isBool ? (r[i] != 0 ? 1 : 0) : (long) r[i];
            }
            variables.put(e.name, r);
            if (e.value != null) e.value.set(r);
        }
    }

    @Override
    public double[] variable(String name) {
        double[] v = variables.get(name);
        if (v != null) return v;
        Uniforms.Value u = uniforms.get(name);
        return u != null ? u.asDoubles() : null;
    }

    @Override
    public double frameTimeSec() {
        return frameTime;
    }
}
