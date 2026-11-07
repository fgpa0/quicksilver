package dev.quicksilver.shader.pack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pipeline settings declared inside shader sources: DRAWBUFFERS / RENDERTARGETS comments and "const" declarations
 * like {@code const int colortex0Format = RGBA16F;} (which may be inside comments) or {@code const float shadowDistance}.
 */
public final class Directives {
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+)\\*/");
    private static final Pattern CONST = Pattern.compile("const\\s+(int|float|bool|vec4|ivec3|vec3)\\s+([A-Za-z0-9_]+)\\s*=\\s*([^;]+);");

    /** Buffer indices the fragment shader writes, in output order, or null if not declared. */
    public static int[] drawBuffers(String fragmentSource) {
        int[] result = null;
        int resultPos = -1;
        Matcher m = DRAWBUFFERS.matcher(fragmentSource);
        while (m.find()) {
            String s = m.group(1);
            int[] r = new int[s.length()];
            for (int i = 0; i < s.length(); i++) r[i] = Character.digit(s.charAt(i), 16);
            if (m.start() > resultPos) {
                result = r;
                resultPos = m.start();
            }
        }
        Matcher t = RENDERTARGETS.matcher(fragmentSource);
        while (t.find()) {
            String[] parts = t.group(1).split(",");
            int[] r = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    r[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    r[i] = 0;
                }
            }
            if (t.start() > resultPos) {
                result = r;
                resultPos = t.start();
            }
        }
        return result;
    }

    /** All const declarations (name -> raw value text with macros expanded), later ones win. */
    public static Map<String, String> consts(String source, Macros macros) {
        Map<String, String> out = new HashMap<>();
        Matcher m = CONST.matcher(source);
        while (m.find()) {
            String v = m.group(3).trim();
            if (macros != null && !v.isEmpty() && Macros.isIdentStart(v.charAt(0)) && macros.isDefined(v)) {
                String e = macros.valueOf(v);
                if (e != null) v = e.trim();
            }
            out.put(m.group(2), v);
        }
        return out;
    }

    public static float parseFloat(String v, float def) {
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim().replaceAll("[fF]$", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean parseBool(String v, boolean def) {
        if (v == null) return def;
        v = v.trim();
        if (v.equals("true") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("0")) return false;
        return def;
    }

    /** "vec4(0.0, 0.0, 0.0, 1.0)" -> floats */
    public static float[] parseVec4(String v) {
        if (v == null) return null;
        int a = v.indexOf('('), b = v.lastIndexOf(')');
        if (a < 0 || b < a) return null;
        String[] parts = v.substring(a + 1, b).split(",");
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) r[i] = parseFloat(parts[Math.min(i, parts.length - 1)], 0);
        if (parts.length == 1) r[3] = r[0];
        return r;
    }
}
