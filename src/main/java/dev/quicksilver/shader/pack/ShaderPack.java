package dev.quicksilver.shader.pack;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read access to the "shaders/" folder of a shader pack, which can be a directory or a zip file.
 * Paths are relative to the shaders folder, separated by '/', without a leading slash.
 */
public final class ShaderPack implements Closeable {
    public final String name;
    private final Path dir;
    private final ZipFile zip;
    private final String zipPrefix;
    private final Map<String, byte[]> cache = new HashMap<>();

    private ShaderPack(String name, Path dir, ZipFile zip, String zipPrefix) {
        this.name = name;
        this.dir = dir;
        this.zip = zip;
        this.zipPrefix = zipPrefix;
    }

    /** Opens a pack from shaderpacks/&lt;name&gt; (directory or zip). Returns null if it has no shaders folder. */
    public static ShaderPack open(Path packsDir, String name) throws IOException {
        Path p = packsDir.resolve(name);
        if (Files.isDirectory(p)) {
            Path s = p.resolve("shaders");
            if (!Files.isDirectory(s)) return null;
            return new ShaderPack(name, s, null, null);
        }
        if (Files.isRegularFile(p) && name.toLowerCase().endsWith(".zip")) {
            ZipFile z = new ZipFile(p.toFile());
            String prefix = findShadersPrefix(z);
            if (prefix == null) {
                z.close();
                return null;
            }
            return new ShaderPack(name, null, z, prefix);
        }
        return null;
    }

    /** "shaders/" usually, but some zips have an extra top-level folder. */
    private static String findShadersPrefix(ZipFile z) {
        String best = null;
        Enumeration<? extends ZipEntry> e = z.entries();
        while (e.hasMoreElements()) {
            String n = e.nextElement().getName();
            int i = n.indexOf("shaders/");
            if (i < 0) continue;
            if (i > 0 && n.charAt(i - 1) != '/') continue;
            String prefix = n.substring(0, i + 8);
            if (best == null || prefix.length() < best.length()) best = prefix;
        }
        return best;
    }

    public static List<String> list(Path packsDir) {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(packsDir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) ? Files.isDirectory(p.resolve("shaders")) : n.toLowerCase().endsWith(".zip")) out.add(n);
            });
        } catch (IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static String normalize(String path) {
        path = path.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(s);
        }
        return String.join("/", parts);
    }

    public synchronized byte[] readBytes(String path) {
        path = normalize(path);
        if (cache.containsKey(path)) return cache.get(path);
        byte[] data = null;
        try {
            if (dir != null) {
                Path p = dir.resolve(path);
                if (Files.isRegularFile(p)) data = Files.readAllBytes(p);
            } else {
                ZipEntry e = zip.getEntry(zipPrefix + path);
                if (e != null && !e.isDirectory()) {
                    try (InputStream in = zip.getInputStream(e)) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        in.transferTo(bo);
                        data = bo.toByteArray();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        cache.put(path, data);
        return data;
    }

    public String read(String path) {
        byte[] b = readBytes(path);
        if (b == null) return null;
        String s = new String(b, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        return s;
    }

    public boolean exists(String path) {
        return readBytes(path) != null;
    }

    /** All file paths under the shaders folder. */
    public List<String> allFiles() {
        List<String> out = new ArrayList<>();
        if (dir != null) {
            try (var s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).forEach(p -> out.add(dir.relativize(p).toString().replace('\\', '/')));
            } catch (IOException ignored) {
            }
        } else {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry z = e.nextElement();
                if (!z.isDirectory() && z.getName().startsWith(zipPrefix)) out.add(z.getName().substring(zipPrefix.length()));
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }
}
