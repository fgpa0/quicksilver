package dev.quicksilver.shader.pack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user-configurable options of a pack, discovered from the shader sources the way OptiFine does it:
 * <ul>
 * <li>switches: {@code #define NAME} or {@code //#define NAME} (off), used by some #ifdef/#ifndef/defined</li>
 * <li>values: {@code #define NAME value // [a b c]}</li>
 * <li>constants: {@code const float NAME = value; // [a b c]} (and const bools)</li>
 * </ul>
 * Values chosen by the user are applied by rewriting those lines while preprocessing.
 */
public final class ShaderOptions {
    public enum Kind { SWITCH, DEFINE, CONST }

    public static final class Option {
        public final String name;
        public final Kind kind;
        public final String defaultValue;
        public final List<String> values;
        public String value;
        /** The comment after the definition (OptiFine shows it as a tooltip if there is no lang entry). */
        public final String comment;

        Option(String name, Kind kind, String defaultValue, List<String> values, String comment) {
            this.name = name;
            this.kind = kind;
            this.defaultValue = defaultValue;
            this.values = values;
            this.value = defaultValue;
            this.comment = comment;
        }

        public boolean isOn() {
            return "true".equals(value);
        }

        public boolean isDefault() {
            return value.equals(defaultValue);
        }
    }

    public final Map<String, Option> options = new LinkedHashMap<>();

    static final Pattern SWITCH = Pattern.compile("^\\s*(//+)?\\s*#define\\s+([A-Za-z0-9_]+)\\s*(//.*)?$");
    static final Pattern DEFINE = Pattern.compile("^\\s*(//+)?\\s*#define\\s+([A-Za-z0-9_]+)\\s+(-?[0-9.]+[fF]?|[A-Za-z0-9_.]+)\\s*(//.*)?$");
    static final Pattern CONST = Pattern.compile("^\\s*const\\s+(int|float|bool)\\s+([A-Za-z0-9_]+)\\s*=\\s*(-?[0-9.]+[fF]?|true|false|[A-Za-z0-9_]+)\\s*;\\s*(//.*)?$");
    private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern IFDEF_USE = Pattern.compile("#\\s*(?:ifdef|ifndef)\\s+([A-Za-z0-9_]+)|defined\\s*\\(?\\s*([A-Za-z0-9_]+)");

    /**
     * Scans the given source files.
     * @param extraNames option names referenced by the screen layout / sliders (defines without a value list are options too then)
     */
    public void discover(List<String> sources, Set<String> extraNames) {
        Set<String> usedInIfdef = new HashSet<>();
        for (String src : sources) {
            Matcher m = IFDEF_USE.matcher(src);
            while (m.find()) usedInIfdef.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        for (String src : sources) {
            for (String line : src.split("\r?\n")) {
                if (line.indexOf("#define") >= 0) {
                    Matcher s = SWITCH.matcher(line);
                    if (s.matches()) {
                        String name = s.group(2);
                        if (!options.containsKey(name) && usedInIfdef.contains(name) && !name.startsWith("MC_")) {
                            boolean on = s.group(1) == null;
                            options.put(name, new Option(name, Kind.SWITCH, on ? "true" : "false", List.of("true", "false"), comment(s.group(3))));
                        }
                        continue;
                    }
                    Matcher d = DEFINE.matcher(line);
                    if (d.matches() && d.group(1) == null) {
                        String name = d.group(2);
                        if (options.containsKey(name)) continue;
                        String val = d.group(3);
                        List<String> list = values(d.group(4));
                        if (list != null || extraNames.contains(name)) {
                            if (list == null) list = new ArrayList<>(List.of(val));
                            if (!list.contains(val)) list.add(0, val);
                            options.put(name, new Option(name, Kind.DEFINE, val, list, comment(d.group(4))));
                        }
                    }
                } else if (line.indexOf("const") >= 0) {
                    Matcher c = CONST.matcher(line);
                    if (c.matches()) {
                        String name = c.group(2);
                        if (options.containsKey(name)) continue;
                        String val = c.group(3);
                        List<String> list = values(c.group(4));
                        if (c.group(1).equals("bool") && list == null && (val.equals("true") || val.equals("false"))) {
                            if (!extraNames.contains(name)) continue;
                            list = List.of("true", "false");
                        }
                        if (list != null) {
                            if (!list.contains(val)) {
                                list = new ArrayList<>(list);
                                list.add(0, val);
                            }
                            options.put(name, new Option(name, Kind.CONST, val, list, comment(c.group(4))));
                        }
                    }
                }
            }
        }
    }

    private static String comment(String c) {
        if (c == null) return "";
        c = c.replaceFirst("^//+", "");
        c = LIST.matcher(c).replaceAll("");
        return c.trim();
    }

    private static List<String> values(String comment) {
        if (comment == null) return null;
        Matcher m = LIST.matcher(comment);
        if (!m.find()) return null;
        List<String> out = new ArrayList<>();
        for (String v : m.group(1).trim().split("\\s+")) if (!v.isEmpty()) out.add(v);
        return out.isEmpty() ? null : out;
    }

    /** Rewrites one source line according to the current option values (returns the line unchanged if not an option line). */
    public String rewrite(String line) {
        if (line.indexOf("#define") >= 0) {
            Matcher s = SWITCH.matcher(line);
            if (s.matches()) {
                Option o = options.get(s.group(2));
                if (o != null && o.kind == Kind.SWITCH) {
                    String tail = s.group(3) == null ? "" : " " + s.group(3);
                    return (o.isOn() ? "#define " : "//#define ") + o.name + tail;
                }
                return line;
            }
            Matcher d = DEFINE.matcher(line);
            if (d.matches() && d.group(1) == null) {
                Option o = options.get(d.group(2));
                if (o != null && o.kind == Kind.DEFINE) return "#define " + o.name + " " + o.value + (d.group(4) == null ? "" : " " + d.group(4));
            }
        } else if (line.indexOf("const") >= 0) {
            Matcher c = CONST.matcher(line);
            if (c.matches()) {
                Option o = options.get(c.group(2));
                if (o != null && o.kind == Kind.CONST) return "const " + c.group(1) + " " + o.name + " = " + o.value + ";" + (c.group(4) == null ? "" : " " + c.group(4));
            }
        }
        return line;
    }

    /** Defines for preprocessing shaders.properties etc.: every option's current value. */
    public void addTo(Macros macros) {
        for (Option o : options.values()) {
            switch (o.kind) {
                case SWITCH -> {
                    if (o.isOn()) macros.define(o.name, "");
                }
                case DEFINE, CONST -> macros.define(o.name, o.value);
            }
        }
    }

    /** Cycle to the next (or previous) allowed value. */
    public static void cycle(Option o, boolean back) {
        int i = o.values.indexOf(o.value);
        int n = o.values.size();
        i = back ? (i <= 0 ? n - 1 : i - 1) : (i + 1) % n;
        o.value = o.values.get(i);
    }
}
