package dev.quicksilver.shader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/quicksilver-shaders.properties */
public final class ShaderConfig {
    /** selected shader pack (file or folder name in shaderpacks/), empty = shaders off */
    public String pack = "";
    /** value of the screenBrightness uniform (vanilla 1.7 "brightness"; Beta has no such setting) */
    public float screenBrightness = 0.5f;
    /** log shader compile output and dump failing sources */
    public boolean verbose = false;

    private Path file;

    public static ShaderConfig load(Path file) {
        ShaderConfig c = new ShaderConfig();
        c.file = file;
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
                c.pack = p.getProperty("pack", c.pack).trim();
                c.screenBrightness = Float.parseFloat(p.getProperty("screenBrightness", "" + c.screenBrightness));
                c.verbose = Boolean.parseBoolean(p.getProperty("verbose", "" + c.verbose));
            } catch (IOException | NumberFormatException e) {
                Shaders.LOGGER.warn("Could not read " + file, e);
            }
        }
        return c;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("pack", pack);
        p.setProperty("screenBrightness", Float.toString(screenBrightness));
        p.setProperty("verbose", Boolean.toString(verbose));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Quicksilver shader settings");
            }
        } catch (IOException e) {
            Shaders.LOGGER.warn("Could not save " + file, e);
        }
    }
}
