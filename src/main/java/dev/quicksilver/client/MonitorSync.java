package dev.quicksilver.client;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import java.awt.Canvas;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.Display;

/**
 * VSync modes. "On" is the driver's swap interval, which on multi-monitor systems may be timed to a different monitor
 * than the one the window is on (the driver decides). "Match Monitor" turns driver sync off and paces frames to the
 * refresh rate of the monitor that actually contains the window: monitors are read from xrandr on Linux/X11 (with
 * decimals, e.g. 143.98 Hz) and from AWT elsewhere, and re-checked in the background when the window moves.
 */
public final class MonitorSync {
    private MonitorSync() {}

    /** Detected refresh rate of the window's monitor in Hz (0 = unknown). */
    private static volatile double hz;
    private static volatile String monitorName = "?";
    private static Thread probe;

    public static void apply() {
        try {
            Display.setVSyncEnabled(QsConfig.vsyncMode == 1);
        } catch (Throwable t) {
            Quicksilver.LOGGER.warn("Could not change VSync", t);
        }
        if (QsConfig.vsyncMode == 2) startProbe();
    }

    /** Frame interval for "Match Monitor" in ns, or 0. */
    public static long intervalNs() {
        if (QsConfig.vsyncMode != 2) return 0;
        double h = hz;
        return h >= 20 ? (long) (1_000_000_000.0 / h) : 0;
    }

    public static String describe() {
        return QsConfig.vsyncMode == 2 ? String.format("match %s %.2fHz", monitorName, hz) : (QsConfig.vsyncMode == 1 ? "driver" : "off");
    }

    private static synchronized void startProbe() {
        if (probe != null) return;
        probe = new Thread(MonitorSync::probeLoop, "Quicksilver Monitor Probe");
        probe.setDaemon(true);
        probe.setPriority(Thread.MIN_PRIORITY);
        probe.start();
    }

    // ------------------------------------------------------------------ detection (background thread)

    private record Monitor(String name, Rectangle bounds, double hz) {}

    private static void probeLoop() {
        List<Monitor> monitors = List.of();
        long monitorsAt = 0;
        Rectangle lastWindow = null;
        while (true) {
            try {
                if (QsConfig.vsyncMode == 2) {
                    Rectangle w = windowBounds();
                    long now = System.nanoTime();
                    boolean moved = w != null && !w.equals(lastWindow);
                    if (monitors.isEmpty() || now - monitorsAt > 30_000_000_000L || moved) {
                        if (monitors.isEmpty() || now - monitorsAt > 30_000_000_000L) {
                            monitors = listMonitors();
                            monitorsAt = now;
                        }
                        Monitor m = pick(monitors, w);
                        double newHz = m != null ? m.hz() : fallbackHz();
                        String newName = m != null ? m.name() : "default";
                        if (Math.abs(newHz - hz) > 0.01 || !newName.equals(monitorName)) {
                            hz = newHz;
                            monitorName = newName;
                            Quicksilver.LOGGER.info("VSync (match monitor): window is on {} at {} Hz", newName, String.format("%.2f", newHz));
                        }
                        lastWindow = w;
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Quicksilver.LOGGER.warn("Monitor detection failed", t);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static Rectangle windowBounds() {
        try {
            net.minecraft.client.Minecraft mc = RenderDistance.MinecraftHolder.get();
            Canvas c = mc != null ? mc.canvas : null;
            if (c != null && c.isShowing() && !Display.isFullscreen()) {
                Point p = c.getLocationOnScreen();
                return new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
            }
            if (Display.isCreated()) return new Rectangle(Display.getX(), Display.getY(), Display.getWidth(), Display.getHeight());
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The monitor containing the window centre, else the one it overlaps most. */
    private static Monitor pick(List<Monitor> monitors, Rectangle w) {
        if (monitors.isEmpty()) return null;
        if (w == null) return monitors.get(0);
        int cx = w.x + w.width / 2, cy = w.y + w.height / 2;
        for (Monitor m : monitors) if (m.bounds().contains(cx, cy)) return m;
        Monitor best = null;
        long bestArea = -1;
        for (Monitor m : monitors) {
            Rectangle r = m.bounds().intersection(w);
            long a = r.isEmpty() ? 0 : (long) r.width * r.height;
            if (a > bestArea) {
                bestArea = a;
                best = m;
            }
        }
        return best;
    }

    private static List<Monitor> listMonitors() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux") || os.contains("bsd")) {
            List<Monitor> x = xrandr();
            if (!x.isEmpty()) return x;
        }
        return awt();
    }

    private static final Pattern OUTPUT = Pattern.compile("^(\\S+) connected (?:primary )?(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)");
    private static final Pattern CURRENT_RATE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\*");

    /** Parses `xrandr --query`: "DP-0 connected primary 2560x1440+0+0 ..." followed by mode lines, the current rate marked with '*'. */
    private static List<Monitor> xrandr() {
        List<Monitor> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("xrandr", "--query").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String name = null;
                Rectangle bounds = null;
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher o = OUTPUT.matcher(line);
                    if (o.find()) {
                        name = o.group(1);
                        bounds = new Rectangle(Integer.parseInt(o.group(4)), Integer.parseInt(o.group(5)),
                                Integer.parseInt(o.group(2)), Integer.parseInt(o.group(3)));
                        continue;
                    }
                    if (!line.startsWith(" ") && !line.startsWith("\t")) {
                        name = null; // disconnected output or screen header
                        continue;
                    }
                    if (name != null) {
                        Matcher m = CURRENT_RATE.matcher(line);
                        if (m.find()) {
                            out.add(new Monitor(name, bounds, Double.parseDouble(m.group(1))));
                            name = null;
                        }
                    }
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        return out;
    }

    private static List<Monitor> awt() {
        List<Monitor> out = new ArrayList<>();
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                int rate = d.getDisplayMode().getRefreshRate();
                if (rate <= 0) continue;
                out.add(new Monitor(d.getIDstring(), d.getDefaultConfiguration().getBounds(), rate));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static double fallbackHz() {
        try {
            return Display.getDesktopDisplayMode().getFrequency();
        } catch (Throwable t) {
            return 60;
        }
    }
}
