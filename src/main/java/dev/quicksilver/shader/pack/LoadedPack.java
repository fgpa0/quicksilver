package dev.quicksilver.shader.pack;

import dev.quicksilver.shader.Shaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The CPU side of a loaded shader pack: options, properties, id maps and preprocessed program sources. */
public final class LoadedPack {
    public final ShaderPack pack;
    public final ShaderOptions options = new ShaderOptions();
    public final Map<String, String> lang = new HashMap<>();
    public final List<String> standardDefines;
    public PropertiesFile props;
    public IdMap ids;
    /** profile name -> option assignments */
    public final Map<String, String> profiles = new LinkedHashMap<>();
    public final Set<String> sliders = new HashSet<>();

    public LoadedPack(ShaderPack pack, List<String> standardDefines) {
        this.pack = pack;
        this.standardDefines = standardDefines;
    }

    private static final Pattern SCREEN_NAMES = Pattern.compile("[A-Za-z0-9_]+");

    /** Discovers options, applies the saved values and parses the properties files. */
    public void load(Path settingsFile) {
        // option names referenced by the GUI layout count as options even without a value list
        String rawProps = pack.read("shaders.properties");
        Set<String> screenNames = new HashSet<>();
        if (rawProps != null) {
            for (String line : rawProps.split("\r?\n")) {
                String t = line.strip();
                if (t.startsWith("screen") || t.startsWith("sliders")) {
                    int eq = t.indexOf('=');
                    if (eq < 0) continue;
                    Matcher m = SCREEN_NAMES.matcher(t.substring(eq + 1));
                    while (m.find()) screenNames.add(m.group());
                    if (t.startsWith("sliders")) {
                        Matcher s = SCREEN_NAMES.matcher(t.substring(eq + 1));
                        while (s.find()) sliders.add(s.group());
                    }
                }
            }
        }
        List<String> sources = new ArrayList<>();
        for (String f : pack.allFiles()) {
            String l = f.toLowerCase();
            if (l.endsWith(".glsl") || l.endsWith(".vsh") || l.endsWith(".fsh") || l.endsWith(".gsh") || l.endsWith(".csh") || l.endsWith(".inc")) {
                String src = pack.read(f);
                if (src != null) sources.add(src);
            }
        }
        options.discover(sources, screenNames);
        loadLang();
        loadSettings(settingsFile);
        reloadProperties();
    }

    /** Re-evaluates the properties files with the current option values. */
    public void reloadProperties() {
        props = PropertiesFile.parse(preprocessText("shaders.properties"));
        profiles.clear();
        for (String k : props.keysWithPrefix("profile.")) profiles.put(k.substring(8), props.get(k));
        ids = IdMap.load(
                pack.exists("block.properties") ? PropertiesFile.parse(preprocessText("block.properties")) : null,
                pack.exists("item.properties") ? PropertiesFile.parse(preprocessText("item.properties")) : null,
                pack.exists("entity.properties") ? PropertiesFile.parse(preprocessText("entity.properties")) : null);
    }

    /** Macros for preprocessing properties files: the standard defines plus every option value. */
    public Macros propertyMacros() {
        Macros m = new Macros();
        for (String d : standardDefines) if (d.startsWith("#define ")) m.defineDirective(d.substring(8));
        options.addTo(m);
        return m;
    }

    private String preprocessText(String path) {
        String text = pack.read(path);
        if (text == null) return null;
        Preprocessor p = new Preprocessor(pack::read, null, propertyMacros(), null);
        return p.runText(path, text);
    }

    private void loadLang() {
        for (String f : new String[]{"lang/en_US.lang", "lang/en_us.lang"}) {
            String t = pack.read(f);
            if (t == null) continue;
            for (String line : t.split("\r?\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) lang.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
    }

    // ------------------------------------------------------------------ settings (OptiFine format: shaderpacks/<pack>.txt)

    public void loadSettings(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                ShaderOptions.Option o = options.options.get(k);
                if (o != null && (o.values.contains(v) || o.kind != ShaderOptions.Kind.SWITCH)) o.value = v;
            }
        } catch (IOException e) {
            Shaders.LOGGER.warn("Could not read shader settings " + file, e);
        }
    }

    public void saveSettings(Path file) {
        StringBuilder sb = new StringBuilder();
        for (ShaderOptions.Option o : options.options.values()) {
            if (!o.isDefault()) sb.append(o.name).append('=').append(o.value).append('\n');
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Shaders.LOGGER.warn("Could not save shader settings " + file, e);
        }
    }

    /** Applies a profile ("OPT=val OPT2 !OPT3 profile.OTHER"). */
    public void applyProfile(String name) {
        String def = profiles.get(name);
        if (def == null) return;
        for (String tok : def.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.startsWith("profile.")) {
                applyProfile(tok.substring(8));
                continue;
            }
            boolean neg = tok.startsWith("!");
            String t = neg ? tok.substring(1) : tok;
            int eq = t.indexOf(':') >= 0 && t.indexOf('=') < 0 ? t.indexOf(':') : t.indexOf('=');
            String k = eq >= 0 ? t.substring(0, eq) : t;
            ShaderOptions.Option o = options.options.get(k);
            if (o == null) continue;
            if (eq >= 0) o.value = t.substring(eq + 1);
            else if (o.kind == ShaderOptions.Kind.SWITCH) o.value = neg ? "false" : "true";
        }
    }

    /** The profile matching the current values, or null (custom). */
    public String currentProfile() {
        outer:
        for (String name : profiles.keySet()) {
            Map<String, String> want = new HashMap<>();
            collectProfile(name, want, 0);
            for (Map.Entry<String, String> e : want.entrySet()) {
                ShaderOptions.Option o = options.options.get(e.getKey());
                if (o != null && !o.value.equals(e.getValue())) continue outer;
            }
            return name;
        }
        return null;
    }

    private void collectProfile(String name, Map<String, String> out, int depth) {
        String def = profiles.get(name);
        if (def == null || depth > 8) return;
        for (String tok : def.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.startsWith("profile.")) {
                collectProfile(tok.substring(8), out, depth + 1);
                continue;
            }
            boolean neg = tok.startsWith("!");
            String t = neg ? tok.substring(1) : tok;
            int eq = t.indexOf('=');
            if (eq < 0) eq = t.indexOf(':');
            if (eq >= 0) out.put(t.substring(0, eq), t.substring(eq + 1));
            else out.put(t, neg ? "false" : "true");
        }
    }

    // ------------------------------------------------------------------ programs

    /** Preprocessed sources and directives of one program. */
    public static final class ProgramSource {
        public final String name;
        public String vertex, geometry, fragment;
        public List<String> vertexLines, geometryLines, fragmentLines;
        public Macros fragmentMacros;
        public String vertexPath, geometryPath, fragmentPath;

        ProgramSource(String name) {
            this.name = name;
        }
    }

    /** Loads a program from world&lt;dim&gt;/ or the shaders root; null if it doesn't exist. */
    public ProgramSource program(String name, int dimension) {
        String[] dirs = {"world" + dimension + "/", ""};
        for (String dir : dirs) {
            String v = dir + name + ".vsh", f = dir + name + ".fsh";
            if (pack.exists(v) && pack.exists(f)) {
                ProgramSource ps = new ProgramSource(name);
                ps.vertexPath = v;
                ps.fragmentPath = f;
                Preprocessor pv = preprocessor();
                ps.vertex = pv.run(v);
                ps.vertexLines = pv.lineOrigins;
                String g = dir + name + ".gsh";
                if (pack.exists(g)) {
                    Preprocessor pg = preprocessor();
                    ps.geometryPath = g;
                    ps.geometry = pg.run(g);
                    ps.geometryLines = pg.lineOrigins;
                }
                Preprocessor pf = preprocessor();
                ps.fragment = pf.run(f);
                ps.fragmentLines = pf.lineOrigins;
                ps.fragmentMacros = pf.macros;
                for (String e : pv.errors) Shaders.LOGGER.warn("[{}] {}", v, e);
                for (String e : pf.errors) Shaders.LOGGER.warn("[{}] {}", f, e);
                return ps;
            }
        }
        return null;
    }

    private Preprocessor preprocessor() {
        Macros m = new Macros();
        for (String d : standardDefines) if (d.startsWith("#define ")) m.defineDirective(d.substring(8));
        return new Preprocessor(pack::read, options, m, standardDefines);
    }

    public String translate(String key, String fallback) {
        String v = lang.get(key);
        return v != null ? v : fallback;
    }
}
