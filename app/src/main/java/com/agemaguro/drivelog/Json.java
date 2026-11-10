package com.agemaguro.drivelog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer (no Android classes, so the logging engine can be tested on a normal JVM). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** Parses JSON into Map / List / Double / String / Boolean / null. Returns null if the text is not valid JSON. */
    static Object parse(String text) {
        if (text == null) return null;
        try {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            return p.i == text.length() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char d = s.charAt(i++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalStateException("bad object");
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char d = s.charAt(i++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalStateException("bad array");
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalStateException("bad value");
        return Double.valueOf(s.substring(st, i));
    }

    private void expect(char c) { if (s.charAt(i++) != c) throw new IllegalStateException("expected " + c); }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    // ---------------------------------------------------------------- writing

    static String stringify(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    static void write(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) quote(b, (String) o);
        else if (o instanceof Boolean) b.append(o.toString());
        else if (o instanceof Number) b.append(num(((Number) o).doubleValue()));
        else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object x : (Iterable<Object>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, x);
            }
            b.append(']');
        } else if (o instanceof double[]) {
            b.append('[');
            double[] a = (double[]) o;
            for (int k = 0; k < a.length; k++) { if (k > 0) b.append(','); b.append(num(a[k])); }
            b.append(']');
        } else quote(b, o.toString());
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder();
        quote(b, s);
        return b.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    /** Number as JSON: whole numbers without ".0", others with up to 6 decimals, NaN/Infinity as null. */
    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Like JavaScript's +v.toFixed(dec): rounds to dec decimals and drops trailing zeros. */
    static String fixed(double v, int dec) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        String r = new BigDecimal(v).setScale(dec, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return r.equals("-0") ? "0" : r;
    }

    // ---------------------------------------------------------------- small helpers for reading maps

    static double d(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }

    static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
