package dev.quicksilver.shader.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered key/value pairs of a (preprocessed) .properties file. Later keys replace earlier ones. */
public final class PropertiesFile {
    public final Map<String, String> map = new LinkedHashMap<>();

    public static PropertiesFile parse(String text) {
        PropertiesFile p = new PropertiesFile();
        if (text == null) return p;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            while (line.endsWith("\\") && i + 1 < lines.length) line = line.substring(0, line.length() - 1) + " " + lines[++i].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = indexOfSeparator(line);
            if (eq < 0) continue;
            String k = line.substring(0, eq).strip();
            String v = line.substring(eq + 1).strip();
            p.map.remove(k); // keep the latest position for ordered keys (uniforms)
            p.map.put(k, v);
        }
        return p;
    }

    private static int indexOfSeparator(String line) {
        int e = line.indexOf('=');
        int c = line.indexOf(':');
        // "block.10009=leaves:waterlogged=false": the first '=' wins over ':' inside values
        if (e >= 0) return e;
        return c;
    }

    public String get(String key) {
        return map.get(key);
    }

    public String get(String key, String def) {
        String v = map.get(key);
        return v == null ? def : v;
    }

    public boolean getBool(String key, boolean def) {
        String v = map.get(key);
        if (v == null) return def;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("on") || v.equals("1");
    }

    public List<String> keysWithPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : map.keySet()) if (k.startsWith(prefix)) out.add(k);
        return out;
    }
}
