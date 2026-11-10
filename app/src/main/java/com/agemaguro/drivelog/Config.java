package com.agemaguro.drivelog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The settings the engine needs. The web page sends its whole settings object whenever it changes. */
final class Config {
    String device = "";
    String proto = "2";
    String units = "us";
    boolean fast = true, autoConn = true, autoRec = true, bg = true, debug = false;
    double[] gears = {3.06, 1.63, 1.00, 0.70};
    double axle = 3.73, tire = 30.0, kLearned = 0, rpmJump = 500, tpsJump = 15;
    int autoStopSec = 20;
    final Map<String, String> pidMode = new HashMap<>();

    static Config parse(String json) {
        Map<String, Object> m = Json.parseObject(json);
        Config c = new Config();
        if (m == null) return c;
        c.device = Json.str(m, "device", "");
        c.proto = Json.str(m, "proto", "2");
        c.units = "metric".equals(Json.str(m, "units", "us")) ? "metric" : "us";
        c.fast = Json.bool(m, "fast", true);
        c.autoConn = Json.bool(m, "autoConn", true);
        c.autoRec = Json.bool(m, "autoRec", true);
        c.bg = Json.bool(m, "bg", true);
        c.debug = Json.bool(m, "debug", false);
        c.axle = Json.d(m, "axle", 3.73);
        c.tire = Json.d(m, "tire", 30.0);
        c.kLearned = Json.d(m, "kLearned", 0);
        c.rpmJump = Json.d(m, "rpmJump", 500);
        c.tpsJump = Json.d(m, "tpsJump", 15);
        c.autoStopSec = (int) Math.max(5, Json.d(m, "autoStopSec", 20));
        Object g = m.get("gears");
        if (g instanceof List && ((List<?>) g).size() >= 2) {
            List<?> l = (List<?>) g;
            double[] a = new double[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i) instanceof Number ? ((Number) l.get(i)).doubleValue() : 1;
            c.gears = a;
        }
        Object pm = m.get("pidMode");
        if (pm instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) pm).entrySet())
            if (e.getValue() instanceof String) c.pidMode.put(String.valueOf(e.getKey()), (String) e.getValue());
        return c;
    }

    /** RPM per mph in a 1.00 gear. */
    double k() { return kLearned > 0 ? kLearned : 336.13 * axle / tire; }

    String mode(String pid) {
        String m = pidMode.get(pid);
        if (m != null && !m.isEmpty()) return m;
        return Pids.isFastDefault(pid) ? "fast" : "normal";
    }

    boolean demo() { return "demo".equals(device); }
}
