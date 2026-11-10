package com.agemaguro.drivelog;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A pretend ELM327 + 2005 Colorado: drives off, cruises at 65 and hits a hill every ~45 s where the "cruise control"
 * kicks it down from 4th to 2nd. Used by the Demo truck setting and by tests (timeScale speeds the story up,
 * engineOff makes the engine stop, a stopped "truck" can be simulated with present=false by the caller).
 */
final class DemoTransport implements Transport {
    static final String[] SUPPORTED = {"04", "05", "06", "07", "0C", "0D", "0E", "0F", "10", "11", "14", "15", "1F", "2F", "33", "42", "46", "49", "4C"};
    private static final double K = 41.8;
    private static final double[] RATIOS = {3.06, 1.63, 1.00, 0.70};

    volatile boolean engineOff = false;
    volatile double timeScale = 1;
    volatile boolean silent = false;        // adapter answers nothing to 01xx (ignition off)

    private final Random rnd = new Random(42);
    private ScheduledExecutorService ex;
    private Sink sink;
    private volatile boolean open;
    private long t0;
    private boolean echo = true, spaces = true, searched = false;
    private double coolant = 62;

    @Override
    public synchronized void open(String address, Sink s) throws IOException {
        sink = s;
        t0 = System.nanoTime();
        echo = true; spaces = true; searched = false; coolant = 62;
        ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "demo-truck"); t.setDaemon(true); return t; });
        try { Thread.sleep(300); } catch (InterruptedException e) { throw new IOException("interrupted"); }
        open = true;
    }

    /** Test hook: the Bluetooth link drops by itself. */
    void dropLink(String reason) {
        open = false;
        Sink s = sink;
        if (s != null) s.onClosed(reason);
    }

    @Override
    public void close() {
        open = false;
        ScheduledExecutorService e = ex;
        if (e != null) e.shutdownNow();
    }

    @Override
    public synchronized void write(String s) throws IOException {
        if (!open) throw new IOException("closed");
        final String cmd = s.replace("\r", "").trim().toUpperCase();
        final String reply = reply(cmd);
        if (silent && cmd.startsWith("01")) return;   // no answer at all
        long delay = cmd.startsWith("AT") ? 15 : (cmd.equals("0100") && !searched) ? 1500 : 38 + rnd.nextInt(25);
        if (cmd.equals("0100")) searched = true;
        final boolean e = echo;
        try {
            ex.schedule(() -> { if (open) sink.onData((e ? cmd + "\r" : "") + reply + "\r\r>"); }, (long) (delay / Math.max(1, Math.min(timeScale, 4))), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) { }
    }

    private String hex(int... bytes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && spaces) b.append(' ');
            b.append(String.format("%02X", Math.max(0, Math.min(255, bytes[i]))));
        }
        return b.toString();
    }

    private static final class St { double t, mph, rpm, tps, load; }

    private St state() {
        double t = (System.nanoTime() - t0) / 1e9 * timeScale;
        double mph, tps, load;
        int gear;
        if (t < 6) { mph = 0; gear = 0; tps = 0; load = 22; }
        else if (t < 30) {
            double u = (t - 6) / 24;
            mph = 65 * Math.sin(u * Math.PI / 2);
            gear = mph < 16 ? 1 : mph < 30 ? 2 : mph < 44 ? 3 : 4;
            tps = 38 - u * 18; load = 60 - u * 25;
        } else {
            double c = (t - 30) % 45;
            mph = 65; gear = 4; tps = 17; load = 33;
            if (c > 20 && c < 36) { tps = 17 + Math.min(1, (c - 20) / 2) * 22; load = 58; mph = 65 - Math.min(3, (c - 20)); }
            if (c > 23 && c < 30) { gear = 2; tps = 42; load = 72; mph = 62 + (c - 23) * 0.4; }
            else if (c >= 30 && c < 33) { gear = 3; tps = 30; load = 55; }
        }
        double slip = gear >= 3 ? 0.015 : 0.06;
        double rpm = gear == 0 ? 690 + rnd.nextDouble() * 25 : Math.max(800, mph * K * RATIOS[gear - 1] * (1 + slip));
        coolant = Math.min(92, coolant + 0.02);
        St s = new St();
        s.t = t;
        s.mph = mph + (mph > 1 ? rnd.nextDouble() * 0.6 - 0.3 : 0);
        s.rpm = rpm + rnd.nextDouble() * 20 - 10;
        s.tps = tps; s.load = load;
        if (engineOff) { s.mph = 0; s.rpm = 0; s.tps = 0; s.load = 0; }
        return s;
    }

    private String reply(String cmd) {
        switch (cmd) {
            case "ATZ": echo = true; spaces = true; return "\r\rELM327 v1.5";
            case "ATE0": echo = false; return "OK";
            case "ATS0": spaces = false; return "OK";
            case "ATI": return "ELM327 v1.5";
            case "ATDP": return "SAE J1850 VPW";
            case "ATDPN": return "2";
            case "ATRV": return String.format("%.1fV", 13.9 + rnd.nextDouble() * 0.2);
            case "03": return hex(0x43, 0x01, 0x71, 0x04, 0x20, 0, 0);
            case "07": return hex(0x47, 0x03, 0x00, 0, 0, 0, 0);
            case "04": return "44";
            default:
        }
        if (cmd.startsWith("AT")) return "OK";
        if (!cmd.matches("^01[0-9A-F]{2}1?$")) return "?";
        String pid = cmd.substring(2, 4);
        String pre = (cmd.equals("0100") && !searched) ? "SEARCHING...\r" : "";
        if (pid.equals("00") || pid.equals("20") || pid.equals("40")) {
            int base = Integer.parseInt(pid, 16), bits = 0;
            for (String p : SUPPORTED) {
                int n = Integer.parseInt(p, 16);
                if (n > base && n <= base + 32) bits |= 1 << (32 - (n - base));
            }
            if (base == 0) bits |= 1;
            if (base == 0x20) bits |= 1;
            return pre + hex(0x41, base, bits >>> 24 & 255, bits >>> 16 & 255, bits >>> 8 & 255, bits & 255);
        }
        boolean sup = false;
        for (String p : SUPPORTED) if (p.equals(pid)) sup = true;
        if (!sup) return "NO DATA";
        St s = state();
        int P = Integer.parseInt(pid, 16);
        switch (pid) {
            case "0C": return two(P, (int) Math.round(s.rpm * 4));
            case "0D": return one(P, s.mph / 0.621371);
            case "11": return one(P, s.tps * 2.55 + 30);
            case "4C": return one(P, s.tps * 2.55);
            case "49": return one(P, s.t > 30 ? 36 : s.tps * 2.55 + 36);
            case "04": return one(P, s.load * 2.55);
            case "05": return one(P, coolant + 40);
            case "0F": return one(P, 24 + 40);
            case "46": return one(P, 18 + 40);
            case "06": return one(P, 128 + (s.mph < 1 ? 6 : 0) + (rnd.nextDouble() * 8 - 3));
            case "07": return one(P, 128 + (s.mph < 1 ? 13 : 9));
            case "0E": return one(P, (s.load > 55 ? 14 : 26) * 2 + 128);
            case "10": return two(P, (int) Math.round((s.rpm * s.load / 4200) * 100));
            case "14": return hex(0x41, P, (int) (20 + rnd.nextDouble() * 150), 0xFF);
            case "15": return hex(0x41, P, (int) (130 + rnd.nextDouble() * 10), 0xFF);
            case "1F": return two(P, (int) Math.round(s.t));
            case "2F": return one(P, 150);
            case "33": return one(P, 98);
            case "42": return two(P, 13900 + (int) Math.round(rnd.nextDouble() * 200));
            default: return "NO DATA";
        }
    }

    private String one(int p, double v) { return hex(0x41, p, (int) Math.round(v)); }

    private String two(int p, int v) { return hex(0x41, p, v >> 8 & 255, v & 255); }
}
