package dev.quicksilver.shader.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Expressions of custom uniforms and variables in shaders.properties (OptiFine syntax): arithmetic, comparisons,
 * boolean logic, component access (cameraPosition.y), vecN constructors and functions like if(), smooth(), in(),
 * clamp(). Values are double vectors of 1 to 4 components.
 */
public abstract class Expr {
    public abstract double[] eval(Ctx ctx);

    public double evalFloat(Ctx ctx) {
        return eval(ctx)[0];
    }

    /** Evaluation context: variable lookup and frame time (for smooth()). */
    public interface Ctx {
        double[] variable(String name);

        double frameTimeSec();
    }

    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) {
            super(msg);
        }
    }

    private static double[] v(double d) {
        return new double[]{d};
    }

    private static boolean truthy(double[] a) {
        return a[0] != 0;
    }

    // ------------------------------------------------------------------ parser

    public static Expr parse(String s, Map<String, Double> constants) {
        Parser p = new Parser(s, constants);
        Expr e = p.expr();
        p.skipWs();
        if (p.i < s.length()) throw new ParseException("unexpected '" + s.substring(p.i) + "' in " + s);
        return e;
    }

    private static final class Parser {
        final String s;
        final Map<String, Double> constants;
        int i;

        Parser(String s, Map<String, Double> constants) {
            this.s = s;
            this.constants = constants;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean eat(String t) {
            skipWs();
            if (s.startsWith(t, i)) {
                // don't take '=' of "==" for "=", or "&" for "&&"
                i += t.length();
                return true;
            }
            return false;
        }

        boolean peekOp(String t) {
            skipWs();
            return s.startsWith(t, i);
        }

        Expr expr() {
            return or();
        }

        Expr or() {
            Expr l = and();
            while (eat("||")) {
                Expr a = l, b = and();
                l = new Expr() {
                    public double[] eval(Ctx c) {
                        return v(truthy(a.eval(c)) || truthy(b.eval(c)) ? 1 : 0);
                    }
                };
            }
            return l;
        }

        Expr and() {
            Expr l = cmp();
            while (eat("&&")) {
                Expr a = l, b = cmp();
                l = new Expr() {
                    public double[] eval(Ctx c) {
                        return v(truthy(a.eval(c)) && truthy(b.eval(c)) ? 1 : 0);
                    }
                };
            }
            return l;
        }

        Expr cmp() {
            Expr l = add();
            while (true) {
                String op;
                if (eat("==")) op = "==";
                else if (eat("!=")) op = "!=";
                else if (eat(">=")) op = ">=";
                else if (eat("<=")) op = "<=";
                else if (eat(">")) op = ">";
                else if (eat("<")) op = "<";
                else return l;
                Expr a = l, b = add();
                String o = op;
                l = new Expr() {
                    public double[] eval(Ctx c) {
                        double x = a.eval(c)[0], y = b.eval(c)[0];
                        boolean r = switch (o) {
                            case "==" -> x == y;
                            case "!=" -> x != y;
                            case ">=" -> x >= y;
                            case "<=" -> x <= y;
                            case ">" -> x > y;
                            default -> x < y;
                        };
                        return v(r ? 1 : 0);
                    }
                };
            }
        }

        Expr add() {
            Expr l = mul();
            while (true) {
                char op;
                if (eat("+")) op = '+';
                else if (peekOp("-")) {
                    i++;
                    op = '-';
                } else return l;
                l = binary(l, mul(), op);
            }
        }

        Expr mul() {
            Expr l = unary();
            while (true) {
                char op;
                if (eat("*")) op = '*';
                else if (eat("/")) op = '/';
                else if (eat("%")) op = '%';
                else return l;
                l = binary(l, unary(), op);
            }
        }

        static Expr binary(Expr a, Expr b, char op) {
            return new Expr() {
                public double[] eval(Ctx c) {
                    double[] x = a.eval(c), y = b.eval(c);
                    int n = Math.max(x.length, y.length);
                    double[] r = new double[n];
                    for (int k = 0; k < n; k++) {
                        double p = x[Math.min(k, x.length - 1)], q = y[Math.min(k, y.length - 1)];
                        r[k] = switch (op) {
                            case '+' -> p + q;
                            case '-' -> p - q;
                            case '*' -> p * q;
                            case '/' -> q == 0 ? 0 : p / q;
                            default -> q == 0 ? 0 : p % q;
                        };
                    }
                    return r;
                }
            };
        }

        Expr unary() {
            if (eat("!")) {
                Expr a = unary();
                return new Expr() {
                    public double[] eval(Ctx c) {
                        return v(truthy(a.eval(c)) ? 0 : 1);
                    }
                };
            }
            if (peekOp("-")) {
                i++;
                Expr a = unary();
                return new Expr() {
                    public double[] eval(Ctx c) {
                        double[] x = a.eval(c).clone();
                        for (int k = 0; k < x.length; k++) x[k] = -x[k];
                        return x;
                    }
                };
            }
            if (eat("+")) return unary();
            return postfix(primary());
        }

        Expr postfix(Expr e) {
            while (true) {
                skipWs();
                if (i < s.length() && s.charAt(i) == '.' && i + 1 < s.length() && Character.isLetter(s.charAt(i + 1))) {
                    i++;
                    int st = i;
                    while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
                    String sw = s.substring(st, i);
                    int[] idx = new int[sw.length()];
                    for (int k = 0; k < sw.length(); k++) idx[k] = "xyzw".indexOf(sw.charAt(k)) >= 0 ? "xyzw".indexOf(sw.charAt(k)) : "rgba".indexOf(sw.charAt(k));
                    Expr base = e;
                    e = new Expr() {
                        public double[] eval(Ctx c) {
                            double[] b = base.eval(c);
                            double[] r = new double[idx.length];
                            for (int k = 0; k < idx.length; k++) r[k] = idx[k] >= 0 && idx[k] < b.length ? b[idx[k]] : 0;
                            return r;
                        }
                    };
                } else return e;
            }
        }

        Expr primary() {
            skipWs();
            if (eat("(")) {
                Expr e = expr();
                eat(")");
                return e;
            }
            int st = i;
            if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                if (i < s.length() && (s.charAt(i) == 'f' || s.charAt(i) == 'F')) i++;
                double d = Double.parseDouble(s.substring(st, i).replaceAll("[fF]$", ""));
                double[] cv = v(d);
                return new Expr() {
                    public double[] eval(Ctx c) {
                        return cv;
                    }
                };
            }
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            String name = s.substring(st, i);
            if (name.isEmpty()) throw new ParseException("expected value at " + i + " in " + s);
            skipWs();
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                List<Expr> args = new ArrayList<>();
                skipWs();
                if (!eat(")")) {
                    do args.add(expr());
                    while (eat(","));
                    eat(")");
                }
                return function(name, args);
            }
            if (name.equals("true") || name.equals("false")) {
                double[] cv = v(name.equals("true") ? 1 : 0);
                return new Expr() {
                    public double[] eval(Ctx c) {
                        return cv;
                    }
                };
            }
            if (name.equals("pi")) {
                double[] cv = v(Math.PI);
                return new Expr() {
                    public double[] eval(Ctx c) {
                        return cv;
                    }
                };
            }
            Double k = constants.get(name);
            if (k != null) {
                double[] cv = v(k);
                return new Expr() {
                    public double[] eval(Ctx c) {
                        return cv;
                    }
                };
            }
            return new Expr() {
                public double[] eval(Ctx c) {
                    double[] r = c.variable(name);
                    return r == null ? ZERO : r;
                }
            };
        }

        private static final double[] ZERO = {0};

        Expr function(String name, List<Expr> a) {
            switch (name) {
                case "if" -> {
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            int k = 0;
                            for (; k + 1 < a.size(); k += 2) if (truthy(a.get(k).eval(c))) return a.get(k + 1).eval(c);
                            return k < a.size() ? a.get(k).eval(c) : ZERO;
                        }
                    };
                }
                case "smooth" -> {
                    // smooth([id,] value [, fadeUp [, fadeDown]]): with 4 args the first is the id
                    Expr val, up, down;
                    if (a.size() >= 4) {
                        val = a.get(1);
                        up = a.get(2);
                        down = a.get(3);
                    } else if (a.size() == 3) {
                        val = a.get(0);
                        up = a.get(1);
                        down = a.get(2);
                    } else if (a.size() == 2) {
                        val = a.get(0);
                        up = a.get(1);
                        down = a.get(1);
                    } else {
                        val = a.get(0);
                        up = null;
                        down = null;
                    }
                    return new Expr() {
                        double[] state;

                        public double[] eval(Ctx c) {
                            double[] target = val.eval(c);
                            double fu = up == null ? 1 : up.evalFloat(c), fd = down == null ? 1 : down.evalFloat(c);
                            if (state == null || state.length != target.length) {
                                state = target.clone();
                                return state.clone();
                            }
                            double dt = c.frameTimeSec();
                            for (int k = 0; k < target.length; k++) {
                                double fade = target[k] > state[k] ? fu : fd;
                                if (fade <= 0) state[k] = target[k];
                                else {
                                    double keep = Math.pow(0.01, dt / fade);
                                    state[k] = target[k] + (state[k] - target[k]) * keep;
                                }
                            }
                            return state.clone();
                        }
                    };
                }
                case "in" -> {
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            double x = a.get(0).evalFloat(c);
                            for (int k = 1; k < a.size(); k++) if (a.get(k).evalFloat(c) == x) return v(1);
                            return v(0);
                        }
                    };
                }
                case "between" -> {
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            double x = a.get(0).evalFloat(c);
                            return v(x >= a.get(1).evalFloat(c) && x <= a.get(2).evalFloat(c) ? 1 : 0);
                        }
                    };
                }
                case "equals" -> {
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            double eps = a.size() > 2 ? a.get(2).evalFloat(c) : 0;
                            return v(Math.abs(a.get(0).evalFloat(c) - a.get(1).evalFloat(c)) <= eps ? 1 : 0);
                        }
                    };
                }
                case "vec2", "vec3", "vec4" -> {
                    int n = name.charAt(3) - '0';
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            double[] r = new double[n];
                            int k = 0;
                            for (Expr e : a) for (double d : e.eval(c)) if (k < n) r[k++] = d;
                            if (k == 1) for (int j = 1; j < n; j++) r[j] = r[0];
                            return r;
                        }
                    };
                }
                case "random" -> {
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            return v(Math.random());
                        }
                    };
                }
                default -> {
                    Function<double[], Double> f = math(name, a.size());
                    return new Expr() {
                        public double[] eval(Ctx c) {
                            double[] x = new double[a.size()];
                            for (int k = 0; k < x.length; k++) x[k] = a.get(k).evalFloat(c);
                            return v(f.apply(x));
                        }
                    };
                }
            }
        }

        static Function<double[], Double> math(String name, int n) {
            return switch (name) {
                case "sin" -> x -> Math.sin(x[0]);
                case "cos" -> x -> Math.cos(x[0]);
                case "tan" -> x -> Math.tan(x[0]);
                case "asin" -> x -> Math.asin(x[0]);
                case "acos" -> x -> Math.acos(x[0]);
                case "atan" -> x -> n > 1 ? Math.atan2(x[0], x[1]) : Math.atan(x[0]);
                case "atan2" -> x -> Math.atan2(x[0], x[1]);
                case "torad" -> x -> Math.toRadians(x[0]);
                case "todeg" -> x -> Math.toDegrees(x[0]);
                case "min" -> x -> {
                    double m = x[0];
                    for (double d : x) m = Math.min(m, d);
                    return m;
                };
                case "max" -> x -> {
                    double m = x[0];
                    for (double d : x) m = Math.max(m, d);
                    return m;
                };
                case "clamp" -> x -> Math.max(x[1], Math.min(x[2], x[0]));
                case "abs" -> x -> Math.abs(x[0]);
                case "floor" -> x -> Math.floor(x[0]);
                case "ceil" -> x -> Math.ceil(x[0]);
                case "round" -> x -> (double) Math.round(x[0]);
                case "exp" -> x -> Math.exp(x[0]);
                case "exp2" -> x -> Math.pow(2, x[0]);
                case "log" -> x -> Math.log(x[0]);
                case "log2" -> x -> Math.log(x[0]) / Math.log(2);
                case "pow" -> x -> Math.pow(x[0], x[1]);
                case "sqrt" -> x -> Math.sqrt(x[0]);
                case "frac", "fract" -> x -> x[0] - Math.floor(x[0]);
                case "signum", "sign" -> x -> Math.signum(x[0]);
                case "fmod", "mod" -> x -> x[1] == 0 ? 0 : x[0] - x[1] * Math.floor(x[0] / x[1]);
                case "float", "int" -> x -> name.equals("int") ? (double) (long) x[0] : x[0];
                default -> throw new ParseException("unknown function " + name);
            };
        }
    }
}
