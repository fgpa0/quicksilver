package dev.quicksilver.shader.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** C-preprocessor macro table with expansion and evaluation of #if expressions. */
public final class Macros {
    public static final class Macro {
        public final String name;
        public final List<String> params; // null for object-like
        public final String body;
        private List<String> bodyTokens;

        Macro(String name, List<String> params, String body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        List<String> tokens() {
            if (bodyTokens == null) bodyTokens = tokenize(body);
            return bodyTokens;
        }
    }

    public final Map<String, Macro> map = new HashMap<>();

    public Macros copy() {
        Macros m = new Macros();
        m.map.putAll(map);
        return m;
    }

    public void define(String name, String body) {
        map.put(name, new Macro(name, null, body == null ? "" : body.trim()));
    }

    public boolean isDefined(String name) {
        return map.containsKey(name);
    }

    public void undef(String name) {
        map.remove(name);
    }

    /** Parses the text after "#define". */
    public void defineDirective(String rest) {
        rest = rest.trim();
        int i = 0;
        while (i < rest.length() && isIdentChar(rest.charAt(i))) i++;
        if (i == 0) return;
        String name = rest.substring(0, i);
        if (i < rest.length() && rest.charAt(i) == '(') {
            int close = rest.indexOf(')', i);
            if (close < 0) return;
            List<String> params = new ArrayList<>();
            for (String p : rest.substring(i + 1, close).split(",")) {
                p = p.trim();
                if (!p.isEmpty()) params.add(p);
            }
            map.put(name, new Macro(name, params, rest.substring(close + 1).trim()));
        } else {
            map.put(name, new Macro(name, null, rest.substring(i).trim()));
        }
    }

    /** Value of an object-like macro after expansion, or null. */
    public String valueOf(String name) {
        Macro m = map.get(name);
        if (m == null) return null;
        List<String> t = expand(List.of(name), new HashSet<>());
        return String.join(" ", t);
    }

    // ---------------------------------------------------------------- tokens

    static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final String[] OPS = {"<<=", ">>=", "...", "&&", "||", "==", "!=", "<=", ">=", "<<", ">>", "##", "++", "--"};

    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') break;
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            }
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentChar(s.charAt(j))) j++;
                out.add(s.substring(i, j));
                i = j;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_') j++;
                    else if ((d == '+' || d == '-') && (s.charAt(j - 1) == 'e' || s.charAt(j - 1) == 'E') && !s.substring(i, j).startsWith("0x")) j++;
                    else break;
                }
                out.add(s.substring(i, j));
                i = j;
                continue;
            }
            boolean matched = false;
            for (String op : OPS) {
                if (s.startsWith(op, i)) {
                    out.add(op);
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- expansion

    public List<String> expand(List<String> tokens, Set<String> disabled) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            Macro m = isIdentStart(t.charAt(0)) && !disabled.contains(t) ? map.get(t) : null;
            if (m == null) {
                out.add(t);
                i++;
                continue;
            }
            if (m.params == null) {
                Set<String> d = new HashSet<>(disabled);
                d.add(t);
                out.addAll(expand(m.tokens(), d));
                i++;
                continue;
            }
            // function-like: needs '('
            if (i + 1 >= tokens.size() || !tokens.get(i + 1).equals("(")) {
                out.add(t);
                i++;
                continue;
            }
            List<List<String>> args = new ArrayList<>();
            List<String> cur = new ArrayList<>();
            int depth = 0;
            int j = i + 2;
            for (; j < tokens.size(); j++) {
                String a = tokens.get(j);
                if (a.equals("(")) depth++;
                else if (a.equals(")")) {
                    if (depth == 0) break;
                    depth--;
                } else if (a.equals(",") && depth == 0) {
                    args.add(cur);
                    cur = new ArrayList<>();
                    continue;
                }
                cur.add(a);
            }
            if (!cur.isEmpty() || !args.isEmpty()) args.add(cur);
            List<String> body = new ArrayList<>();
            for (String bt : m.tokens()) {
                int pi = m.params.indexOf(bt);
                if (pi >= 0) body.addAll(pi < args.size() ? expand(args.get(pi), disabled) : List.of());
                else body.add(bt);
            }
            Set<String> d = new HashSet<>(disabled);
            d.add(t);
            out.addAll(expand(body, d));
            i = j + 1;
        }
        return out;
    }

    // ---------------------------------------------------------------- #if evaluation

    /** Evaluates a #if / #elif expression. Undefined identifiers are 0. */
    public boolean evaluate(String expr) {
        List<String> raw = tokenize(expr);
        // resolve "defined X" / "defined(X)" before expansion
        List<String> t = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String s = raw.get(i);
            if (s.equals("defined")) {
                String name;
                if (i + 1 < raw.size() && raw.get(i + 1).equals("(")) {
                    name = i + 2 < raw.size() ? raw.get(i + 2) : "";
                    i += 3;
                } else {
                    name = i + 1 < raw.size() ? raw.get(i + 1) : "";
                    i += 1;
                }
                t.add(map.containsKey(name) ? "1" : "0");
            } else {
                t.add(s);
            }
        }
        List<String> e = expand(t, new HashSet<>());
        Parser p = new Parser(e);
        try {
            double v = p.ternary();
            return v != 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static final class Parser {
        final List<String> t;
        int i;

        Parser(List<String> t) {
            this.t = t;
        }

        String peek() {
            return i < t.size() ? t.get(i) : "";
        }

        boolean eat(String s) {
            if (peek().equals(s)) {
                i++;
                return true;
            }
            return false;
        }

        double ternary() {
            double c = or();
            if (eat("?")) {
                double a = ternary();
                eat(":");
                double b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        double or() {
            double v = and();
            while (eat("||")) {
                double r = and();
                v = (v != 0 || r != 0) ? 1 : 0;
            }
            return v;
        }

        double and() {
            double v = bitor();
            while (eat("&&")) {
                double r = bitor();
                v = (v != 0 && r != 0) ? 1 : 0;
            }
            return v;
        }

        double bitor() {
            double v = bitxor();
            while (peek().equals("|")) {
                i++;
                v = (long) v | (long) bitxor();
            }
            return v;
        }

        double bitxor() {
            double v = bitand();
            while (eat("^")) v = (long) v ^ (long) bitand();
            return v;
        }

        double bitand() {
            double v = equality();
            while (peek().equals("&")) {
                i++;
                v = (long) v & (long) equality();
            }
            return v;
        }

        double equality() {
            double v = relational();
            while (true) {
                if (eat("==")) v = v == relational() ? 1 : 0;
                else if (eat("!=")) v = v != relational() ? 1 : 0;
                else return v;
            }
        }

        double relational() {
            double v = shift();
            while (true) {
                if (eat("<=")) v = v <= shift() ? 1 : 0;
                else if (eat(">=")) v = v >= shift() ? 1 : 0;
                else if (eat("<")) v = v < shift() ? 1 : 0;
                else if (eat(">")) v = v > shift() ? 1 : 0;
                else return v;
            }
        }

        double shift() {
            double v = additive();
            while (true) {
                if (eat("<<")) v = (long) v << (long) additive();
                else if (eat(">>")) v = (long) v >> (long) additive();
                else return v;
            }
        }

        double additive() {
            double v = mult();
            while (true) {
                if (eat("+")) v += mult();
                else if (eat("-")) v -= mult();
                else return v;
            }
        }

        double mult() {
            double v = unary();
            while (true) {
                if (eat("*")) v *= unary();
                else if (eat("/")) {
                    double r = unary();
                    if (r == 0) v = 0;
                    else if (v == Math.rint(v) && r == Math.rint(r)) v = (long) v / (long) r;
                    else v /= r;
                } else if (eat("%")) {
                    double r = unary();
                    v = r == 0 ? 0 : v % r;
                } else return v;
            }
        }

        double unary() {
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            if (eat("~")) return ~(long) unary();
            return primary();
        }

        double primary() {
            if (eat("(")) {
                double v = ternary();
                eat(")");
                return v;
            }
            String s = peek();
            i++;
            if (s.isEmpty()) throw new RuntimeException("unexpected end");
            if (s.equals("true")) return 1;
            if (s.equals("false")) return 0;
            if (Character.isDigit(s.charAt(0)) || s.charAt(0) == '.') return number(s);
            return 0; // undefined identifier
        }

        static double number(String s) {
            String l = s.toLowerCase();
            if (l.startsWith("0x")) {
                l = l.replaceAll("[ul]+$", "");
                return Long.parseLong(l.substring(2), 16);
            }
            l = l.replaceAll("(lf|[ulf])+$", "");
            try {
                return Double.parseDouble(l);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
