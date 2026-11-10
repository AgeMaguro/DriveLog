package com.agemaguro.drivelog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OBD2 mode 01 sensors (same table as the web page). Values are decoded to metric base units. */
final class Pids {
    interface F { double f(int[] a); }

    static final class Pid {
        final String key, name, label, unit, kind;
        final int decimals, bytes;
        final boolean adapter;
        final F f;

        Pid(String key, String name, String label, String unit, String kind, int decimals, int bytes, boolean adapter, F f) {
            this.key = key; this.name = name; this.label = label; this.unit = unit; this.kind = kind;
            this.decimals = decimals; this.bytes = bytes; this.adapter = adapter; this.f = f;
        }

        /** Decoded value, or NaN if the answer was too short. */
        double decode(int[] a) { return f == null || a == null || a.length < bytes ? Double.NaN : f.f(a); }
    }

    static final List<Pid> ORDER = new ArrayList<>();
    static final Map<String, Pid> MAP = new HashMap<>();
    private static final Set<String> FAST = new HashSet<>(Arrays.asList("0C", "0D", "11", "04", "49", "4C"));

    private static void add(String key, String name, String label, String unit, String kind, int d, int bytes, F f) {
        Pid p = new Pid(key, name, label, unit, kind, d, bytes, false, f);
        ORDER.add(p);
        MAP.put(key, p);
    }

    static {
        add("0C", "Engine RPM", "RPM", "rpm", null, 0, 2, a -> (a[0] * 256 + a[1]) / 4.0);
        add("0D", "Vehicle speed", "Speed", null, "speed", 0, 1, a -> a[0]);
        add("11", "Throttle position", "Throttle", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("04", "Engine load", "Load", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("49", "Accelerator pedal", "Pedal", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("4C", "Commanded throttle", "Cmd throttle", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("05", "Coolant temp", "Coolant", null, "temp", 0, 1, a -> a[0] - 40);
        add("0F", "Intake air temp", "Intake air", null, "temp", 0, 1, a -> a[0] - 40);
        add("0B", "Intake manifold pressure", "MAP", null, "press", 1, 1, a -> a[0]);
        add("10", "Mass air flow", "MAF", "g/s", null, 1, 2, a -> (a[0] * 256 + a[1]) / 100.0);
        add("0E", "Ignition timing advance", "Timing", "°", null, 1, 1, a -> a[0] / 2.0 - 64);
        add("06", "Short-term fuel trim (bank 1)", "STFT", "%", null, 1, 1, a -> (a[0] - 128) * 100 / 128.0);
        add("07", "Long-term fuel trim (bank 1)", "LTFT", "%", null, 1, 1, a -> (a[0] - 128) * 100 / 128.0);
        add("08", "Short-term fuel trim (bank 2)", "STFT B2", "%", null, 1, 1, a -> (a[0] - 128) * 100 / 128.0);
        add("09", "Long-term fuel trim (bank 2)", "LTFT B2", "%", null, 1, 1, a -> (a[0] - 128) * 100 / 128.0);
        add("14", "O2 sensor, bank 1 sensor 1 (upstream)", "O2 up", "V", null, 2, 1, a -> a[0] / 200.0);
        add("15", "O2 sensor, bank 1 sensor 2 (downstream)", "O2 down", "V", null, 2, 1, a -> a[0] / 200.0);
        add("0A", "Fuel pressure", "Fuel press", null, "press", 1, 1, a -> a[0] * 3);
        add("2F", "Fuel level", "Fuel", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("33", "Barometric pressure", "Baro", null, "press", 1, 1, a -> a[0]);
        add("42", "Computer (ECU) voltage", "ECU volts", "V", null, 1, 2, a -> (a[0] * 256 + a[1]) / 1000.0);
        add("43", "Absolute load", "Abs load", "%", null, 0, 2, a -> (a[0] * 256 + a[1]) * 100 / 255.0);
        add("44", "Commanded air-fuel ratio (lambda)", "Cmd λ", "λ", null, 3, 2, a -> (a[0] * 256 + a[1]) / 32768.0);
        add("45", "Relative throttle", "Rel throttle", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("46", "Outside air temp", "Outside", null, "temp", 0, 1, a -> a[0] - 40);
        add("47", "Throttle position B", "Throttle B", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("4A", "Accelerator pedal E", "Pedal E", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("2C", "Commanded EGR", "EGR", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("2E", "Commanded evap purge", "Evap purge", "%", null, 0, 1, a -> a[0] * 100 / 255.0);
        add("3C", "Catalyst temp (bank 1)", "Cat temp", null, "temp", 0, 2, a -> (a[0] * 256 + a[1]) / 10.0 - 40);
        add("5C", "Engine oil temp", "Oil temp", null, "temp", 0, 1, a -> a[0] - 40);
        add("1F", "Time since engine start", "Run time", "s", null, 0, 2, a -> a[0] * 256 + a[1]);
        add("21", "Distance with check-engine light on", "MIL dist", null, "dist", 0, 2, a -> a[0] * 256 + a[1]);
        Pid rv = new Pid("RV", "Battery voltage (from adapter)", "Battery", "V", null, 1, 0, true, null);
        ORDER.add(rv);
        MAP.put("RV", rv);
    }

    static boolean isFastDefault(String pid) { return FAST.contains(pid); }

    static Pid get(String key) { return MAP.get(key); }

    /** Unit text for a sensor in "us" or "metric" units. */
    static String unit(Pid p, String units) {
        if (p.kind == null) return p.unit;
        boolean us = "us".equals(units);
        switch (p.kind) {
            case "temp": return us ? "°F" : "°C";
            case "speed": return us ? "mph" : "km/h";
            case "press": return us ? "psi" : "kPa";
            default: return us ? "mi" : "km";
        }
    }

    /** Converts a metric base value to the chosen units. */
    static double toDisp(Pid p, double v, String units) {
        if (p.kind == null || !"us".equals(units)) return v;
        switch (p.kind) {
            case "temp": return v * 9 / 5 + 32;
            case "speed": return v * 0.621371;
            case "press": return v * 0.145038;
            default: return v * 0.621371;
        }
    }
}
