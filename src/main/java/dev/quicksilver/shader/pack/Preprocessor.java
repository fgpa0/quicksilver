package dev.quicksilver.shader.pack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Resolves #include, #if/#ifdef/#ifndef/#elif/#else/#endif (with macro expansion) and applies option values, producing
 * a single GLSL source with only the active lines. #define/#undef lines stay in the output so the driver still
 * expands macros in code; conditionals are fully resolved here (so pipeline directives in inactive code, such as a
 * second DRAWBUFFERS comment, are dropped exactly like the driver would drop the code).
 */
public final class Preprocessor {
    private final Function<String, String> files;
    private final ShaderOptions options;
    public final Macros macros;
    private final StringBuilder out = new StringBuilder();
    /** origin of every output line: "file:line" */
    public final List<String> lineOrigins = new ArrayList<>();
    private String version;
    private int versionInsertAt = -1;
    private final List<String> injected;
    private int includeDepth;
    /** properties files: '#' is also the comment character, so "# if ..." is a comment, "#if" a directive */
    private boolean strict;
    public final List<String> errors = new ArrayList<>();

    /**
     * @param files reads a pack file by normalized path (null if missing)
     * @param options option values to apply (may be null)
     * @param macros predefined macros (standard + program defines); modified while processing
     * @param injected lines inserted right after #version (the standard defines)
     */
    public Preprocessor(Function<String, String> files, ShaderOptions options, Macros macros, List<String> injected) {
        this.files = files;
        this.options = options;
        this.macros = macros;
        this.injected = injected;
    }

    private static final class Cond {
        final boolean parentActive;
        boolean active;
        boolean taken;

        Cond(boolean parentActive, boolean active) {
            this.parentActive = parentActive;
            this.active = active;
            this.taken = active;
        }
    }

    private final Deque<Cond> conds = new ArrayDeque<>();

    private boolean active() {
        return conds.isEmpty() || conds.peek().active;
    }

    public String run(String path) {
        processFile(ShaderPack.normalize(path));
        String body = out.toString();
        StringBuilder sb = new StringBuilder();
        List<String> origins = new ArrayList<>();
        sb.append(version != null ? version : "#version 120").append('\n');
        origins.add("<version>");
        if (injected != null) {
            for (String l : injected) {
                sb.append(l).append('\n');
                origins.add("<injected>");
            }
        }
        sb.append(body);
        origins.addAll(lineOrigins);
        lineOrigins.clear();
        lineOrigins.addAll(origins);
        return sb.toString();
    }

    /** Processes a properties-style file (no #version handling). */
    public String runText(String path, String text) {
        strict = true;
        processText(path, text);
        return out.toString();
    }

    private void processFile(String path) {
        String text = files.apply(path);
        if (text == null) {
            errors.add("missing file " + path);
            return;
        }
        processText(path, text);
    }

    private void processText(String path, String text) {
        String[] raw = text.split("\r?\n", -1);
        boolean inComment = false;
        for (int ln = 0; ln < raw.length; ln++) {
            String line = raw[ln];
            int startLine = ln + 1;
            // join continuation lines
            while (line.endsWith("\\") && ln + 1 < raw.length) {
                line = line.substring(0, line.length() - 1) + " " + raw[++ln];
            }
            boolean startsInComment = inComment;
            inComment = !strict && updateCommentState(line, inComment);
            if (!startsInComment) {
                if (options != null) line = options.rewrite(line);
                String t = line.stripLeading();
                if (t.startsWith("#") && !(strict && (t.length() < 2 || !Macros.isIdentStart(t.charAt(1))))) {
                    directive(path, startLine, t.substring(1).stripLeading(), line);
                    continue;
                }
            }
            if (active()) emit(line, path, startLine);
        }
    }

    private void emit(String line, String path, int ln) {
        out.append(line).append('\n');
        lineOrigins.add(path + ":" + ln);
    }

    /** Tracks whether a block comment is open at the end of the line. */
    private static boolean updateCommentState(String line, boolean in) {
        int i = 0, n = line.length();
        while (i < n) {
            if (in) {
                int e = line.indexOf("*/", i);
                if (e < 0) return true;
                in = false;
                i = e + 2;
            } else {
                char c = line.charAt(i);
                if (c == '/' && i + 1 < n) {
                    char d = line.charAt(i + 1);
                    if (d == '/') return false;
                    if (d == '*') {
                        in = true;
                        i += 2;
                        continue;
                    }
                }
                if (c == '"') { // skip string literals (#include paths)
                    int e = line.indexOf('"', i + 1);
                    i = e < 0 ? n : e + 1;
                    continue;
                }
                i++;
            }
        }
        return in;
    }

    private static String stripComment(String s) {
        int i = s.indexOf("//");
        if (i >= 0) s = s.substring(0, i);
        int b;
        while ((b = s.indexOf("/*")) >= 0) {
            int e = s.indexOf("*/", b + 2);
            s = e < 0 ? s.substring(0, b) : s.substring(0, b) + " " + s.substring(e + 2);
        }
        return s.trim();
    }

    private void directive(String path, int ln, String d, String original) {
        int sp = 0;
        while (sp < d.length() && Macros.isIdentChar(d.charAt(sp))) sp++;
        String name = d.substring(0, sp);
        String rest = d.substring(sp);
        switch (name) {
            case "if" -> {
                boolean parent = active();
                conds.push(new Cond(parent, parent && macros.evaluate(stripComment(rest))));
            }
            case "ifdef" -> {
                boolean parent = active();
                conds.push(new Cond(parent, parent && macros.isDefined(firstWord(stripComment(rest)))));
            }
            case "ifndef" -> {
                boolean parent = active();
                conds.push(new Cond(parent, parent && !macros.isDefined(firstWord(stripComment(rest)))));
            }
            case "elif" -> {
                Cond c = conds.peek();
                if (c == null) return;
                if (c.taken || !c.parentActive) c.active = false;
                else {
                    c.active = macros.evaluate(stripComment(rest));
                    c.taken = c.active;
                }
            }
            case "else" -> {
                Cond c = conds.peek();
                if (c == null) return;
                c.active = c.parentActive && !c.taken;
                c.taken = true;
            }
            case "endif" -> {
                if (!conds.isEmpty()) conds.pop();
            }
            default -> {
                if (!active()) return;
                switch (name) {
                    case "version" -> {
                        if (version == null) version = "#version " + stripComment(rest);
                    }
                    case "include" -> include(path, stripComment(rest));
                    case "define" -> {
                        macros.defineDirective(stripComment(rest));
                        emit(original, path, ln);
                    }
                    case "undef" -> {
                        macros.undef(firstWord(stripComment(rest)));
                        emit(original, path, ln);
                    }
                    case "line" -> {
                    }
                    default -> emit(original, path, ln);
                }
            }
        }
    }

    private void include(String from, String arg) {
        String p = arg.trim();
        if (p.length() >= 2 && (p.charAt(0) == '"' || p.charAt(0) == '<')) p = p.substring(1, p.length() - 1);
        String target;
        if (p.startsWith("/")) target = ShaderPack.normalize(p);
        else {
            int slash = from.lastIndexOf('/');
            target = ShaderPack.normalize((slash >= 0 ? from.substring(0, slash + 1) : "") + p);
        }
        if (includeDepth > 64) {
            errors.add("include depth exceeded at " + target);
            return;
        }
        includeDepth++;
        try {
            processFile(target);
        } finally {
            includeDepth--;
        }
    }

    private static String firstWord(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && Macros.isIdentChar(s.charAt(i))) i++;
        return s.substring(0, i);
    }
}
