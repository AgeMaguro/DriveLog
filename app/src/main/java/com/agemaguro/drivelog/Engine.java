package com.agemaguro.drivelog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The logging engine: connects to the ELM327 adapter, polls the sensors, detects events, records drives, and does
 * auto-connect / auto-record. It runs on its own thread and has no Android classes in it, so it keeps working with the
 * app closed (inside LogService) and can be tested on a normal JVM. The web page is just a window onto it.
 *
 * Everything the page needs is pushed through Host (state, samples, events, notices); the page sends commands back
 * through the public methods (connectRequest, recStartRequest, markRequest, ...), which are safe from any thread.
 */
final class Engine {
    interface Host {
        /** A new transport for the configured device (null if none is possible). */
        Transport newTransport(Config cfg);
        void onState(String json);
        void onSample(String json);
        void onEvent(String json);
        /** kind: "toast", "connect_failed" (text = reason), "truck_silent" (text = what the adapter said). */
        void onNotice(String kind, String text);
        void onQuery(int id, String jsonArray);
        /** True while the web page is on screen (samples are only built when somebody is looking). */
        boolean uiAttached();
        void log(String line);
    }

    static final class Sample {
        final long t;
        final Map<String, Double> raw;
        final Double rpm, mph, tps, slip;
        final Integer gear;
        Sample(long t, Map<String, Double> raw, Double rpm, Double mph, Double tps, Integer gear, Double slip) {
            this.t = t; this.raw = raw; this.rpm = rpm; this.mph = mph; this.tps = tps; this.gear = gear; this.slip = slip;
        }
    }

    static final class Gear { final Integer gear; final Double slip; Gear(Integer g, Double s) { gear = g; slip = s; } }
    private static final class PendingJump { long t; long from; double peak; Integer g1; }

    private final FileStore store;
    private final Host host;
    private final String appVersion;
    private final Object lock = new Object();
    private final Object cmdLock = new Object();
    private final ConcurrentLinkedQueue<Runnable> cmds = new ConcurrentLinkedQueue<>();
    private final Elm elm = new Elm();

    private volatile Config cfg = new Config();
    private volatile String vin = "";
    private volatile Config effCfg;   // cfg with the gearing of the vehicle that is connected (cached)
    private volatile boolean stop = true;
    private Thread thread;

    // --- connection
    private volatile String conn = "off", status = "Not connected", statusCls = "";
    private String protoName = "", elmVersion = "";
    private volatile Set<String> supported = new HashSet<>();
    private Transport transport, pending;
    private int gen = 0;
    private volatile String linkLost = null;
    private volatile boolean manualConnect = false, cancelConnect = false, userOff = false;
    private int timeouts = 0, autoFails = 0;
    private long nextAuto = 0;
    private boolean fastOk = true;

    // --- live data
    private final Map<String, Double> liveRaw = new HashMap<>();
    private final List<Sample> hist = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private volatile boolean cruise = false;
    private long lastJumpT = 0, lastThrT = 0;
    private PendingJump pj = null;
    private int slowIdx = 0;

    // --- auto-record
    private volatile boolean suppressed = false;
    private long runSince = 0, offSince = 0;

    // --- recording
    private volatile boolean recOn = false;
    private Map<String, Object> meta;
    private List<Object> metaEvents;
    private final StringBuilder rowBuf = new StringBuilder();
    private List<Pids.Pid> recCols = new ArrayList<>();
    private String recUnits = "us";
    private long lastFlush = 0, recRows = 0;
    private volatile boolean engineSeen = false;
    private volatile String recId = "";
    private volatile long recStartT = 0, recEndT = 0;

    Engine(FileStore store, Host host, String appVersion) {
        this.store = store;
        this.host = host;
        this.appVersion = appVersion;
    }

    // ================================================================= lifecycle

    synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stop = false;
        thread = new Thread(this::run, "drivelog-engine");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops everything: disconnects and saves a recording in progress. Blocks briefly while the thread finishes. */
    void shutdown() {
        Thread t;
        synchronized (this) { t = thread; stop = true; }
        cancelConnect = true;
        Transport p = pending;
        if (p != null) p.close();
        elm.abort();
        wake();
        if (t != null && t != Thread.currentThread()) {
            try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    boolean isRunning() { Thread t = thread; return t != null && t.isAlive() && !stop; }

    void setConfig(String json) {
        Config old = cfg;
        Config n = Config.parse(json);
        cfg = n; effCfg = null;
        if ((!old.autoConn && n.autoConn) || !old.device.equals(n.device)) { autoFails = 0; nextAuto = 0; }
        if (old.autoRec != n.autoRec) resetAuto();
        pushState();
        wake();
    }

    Config config() { return cfg; }

    /** Settings with the connected vehicle's own gears, axle and tires. */
    private Config effective() {
        Config e = effCfg;
        if (e != null) return e;
        Config c = cfg;
        Config.Veh v = c.vehicleFor(vin);
        e = v == null ? c : c.withVehicle(v);
        effCfg = e;
        return e;
    }

    // ================================================================= commands from the page (any thread)

    void connectRequest() { userOff = false; autoFails = 0; nextAuto = 0; manualConnect = true; pushState(); wake(); }

    void disconnectRequest() {
        userOff = true; manualConnect = false; cancelConnect = true;
        Transport p = pending;
        if (p != null) p.close();
        elm.abort();
        cmds.add(() -> { if (!conn.equals("off")) stopConn(""); });
        pushState();
        wake();
    }

    void recStartRequest() { cmds.add(() -> { suppressed = false; recStart(false); }); wake(); }

    void recStopRequest() { cmds.add(() -> { if (recOn) { suppressed = true; recStop(null); } pushState(); }); wake(); }

    void markRequest() { cmds.add(this::mark); wake(); }

    void cruiseRequest() { cmds.add(this::toggleCruise); wake(); }

    /** Runs commands (like "03" for trouble codes) between polling cycles and returns the answers through Host.onQuery. */
    void queryRequest(final int id, final List<String> commands, final int timeoutMs) {
        cmds.add(() -> {
            List<String> out = new ArrayList<>();
            for (String c : commands) out.add(conn.equals("on") ? tx(c, timeoutMs) : "NOT CONNECTED");
            host.onQuery(id, Json.stringify(out));
        });
        wake();
    }

    /** Sets or clears (empty) the label on one of your marks. */
    void labelEvent(long t, String label) {
        synchronized (lock) {
            List<Map<String, Object>> all = new ArrayList<>(events);
            if (metaEvents != null) for (Object o : metaEvents) { @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; all.add(m); }
            for (Map<String, Object> e : all) {
                if (((Number) e.get("t")).longValue() == t && String.valueOf(e.get("text")).startsWith("Marked")) {
                    if (label == null || label.trim().isEmpty()) e.remove("label"); else e.put("label", label.trim());
                }
            }
        }
        cmds.add(() -> { if (recOn) saveMeta(); });
        wake();
    }

    private void wake() { synchronized (cmdLock) { cmdLock.notifyAll(); } }

    private void waitCmd(long ms) {
        synchronized (cmdLock) {
            if (!cmds.isEmpty() || stop) return;
            try { cmdLock.wait(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ================================================================= snapshot for the page / notification

    String stateJson() {
        synchronized (lock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conn", conn);
            m.put("status", status);
            m.put("cls", statusCls);
            m.put("proto", protoName);
            m.put("elm", elmVersion);
            m.put("supported", new ArrayList<>(supported));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("on", recOn);
            r.put("id", recId);
            r.put("auto", meta != null && Boolean.TRUE.equals(meta.get("auto")));
            r.put("engineSeen", engineSeen);
            r.put("start", recStartT);
            r.put("rows", recRows);
            m.put("rec", r);
            m.put("suppressed", suppressed);
            m.put("userOff", userOff);
            m.put("cruise", cruise);
            m.put("vin", vin);
            Config.Veh sv = cfg.vehicleFor(vin);
            m.put("vehicleId", sv == null ? "" : sv.id);
            m.put("waiting", conn.equals("off") && eligibleAuto(cfg));
            m.put("running", isRunning());
            return Json.stringify(m);
        }
    }

    /** Everything a freshly opened page needs: state, recent events (newest first) and the last two minutes of samples. */
    String snapshotJson() {
        synchronized (lock) {
            StringBuilder b = new StringBuilder("{\"state\":");
            b.append(stateJson()).append(",\"events\":");
            Json.write(b, events);
            b.append(",\"hist\":[");
            boolean first = true;
            for (Sample s : hist) { if (!first) b.append(','); first = false; b.append(sampleJson(s)); }
            return b.append("]}").toString();
        }
    }

    /** One line for the notification. */
    String summary() {
        String c = conn;
        if (recOn) return "Recording · " + fmtDur(Math.max(0, recEndT - recStartT)) + " · " + recRows + " rows";
        if (c.equals("on")) return cfg.autoRec ? "Connected · recording starts when the engine runs" : "Connected";
        if (c.equals("connecting") || c.equals("init")) return status;
        if (eligibleAuto(cfg)) return "Waiting for your adapter";
        return userOff ? "Not connected" : status;
    }

    boolean isConnected() { return conn.equals("on"); }

    boolean isRecording() { return recOn; }

    /** True while the engine has a reason to keep the service alive (connected, connecting, recording or waiting for the truck). */
    boolean wantsService() { return isRunning() && (!conn.equals("off") || recOn || eligibleAuto(cfg)); }

    private static String fmtDur(long ms) {
        long s = Math.round(ms / 1000.0), h = s / 3600, m = s % 3600 / 60, ss = s % 60;
        return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m " + ss + "s" : ss + "s";
    }

    // ================================================================= main loop

    private void run() {
        host.log("engine started");
        interruptedCleanup();
        nextAuto = System.currentTimeMillis() + 3000;
        pushState();
        try {
            while (!stop) {
                runCmds();
                if (stop) break;
                String lost = linkLost;
                if (lost != null) { linkLost = null; lost(lost); continue; }
                if (conn.equals("on")) { pollOnce(); flushIfDue(false); continue; }
                if (manualConnect) { manualConnect = false; connect(false); continue; }
                if (eligibleAuto(cfg) && System.currentTimeMillis() >= nextAuto) { connect(true); continue; }
                waitCmd(300);
            }
        } catch (RuntimeException e) {
            host.log("engine crashed: " + e);
            e.printStackTrace();
        }
        if (recOn) recStop("Stopped");
        if (!conn.equals("off")) stopConn("");
        host.log("engine stopped");
        pushState();
    }

    private void runCmds() {
        Runnable r;
        while ((r = cmds.poll()) != null) {
            try { r.run(); } catch (RuntimeException e) { host.log("command failed: " + e); }
        }
    }

    private boolean eligibleAuto(Config c) { return c.autoConn && !c.device.isEmpty() && !c.demo() && !userOff; }

    /** Drives left "open" by a crash or a killed process get closed so the page doesn't wait for them. */
    private void interruptedCleanup() {
        for (String n : store.list()) {
            if (!n.endsWith(".json")) continue;
            Map<String, Object> m = Json.parseObject(store.read(n));
            if (m != null && !Boolean.TRUE.equals(m.get("done"))) {
                m.put("done", true);
                m.put("interrupted", true);
                store.write(n, Json.stringify(m), false);
            }
        }
    }

    // ================================================================= connect / init

    private void setConn(String c, String text, String cls) {
        synchronized (lock) { conn = c; status = text; statusCls = cls; }
        pushState();
    }

    private void pushState() { host.onState(stateJson()); }

    private void autoFailed() { autoFails++; nextAuto = System.currentTimeMillis() + Math.min(60000L, 8000L * autoFails); pushState(); }

    private void autoOk() { autoFails = 0; nextAuto = 0; }

    private void connect(boolean auto) {
        final Config c = cfg;
        cancelConnect = false;
        if (!c.demo() && c.device.isEmpty()) { if (!auto) host.onNotice("toast", "Pick your adapter first"); return; }
        final int myGen = ++gen;
        linkLost = null;
        setConn("connecting", c.demo() ? "Starting demo…" : auto ? "Looking for adapter…" : "Connecting to adapter…", "busy");
        host.log("connecting " + (c.demo() ? "demo" : c.device) + (auto ? " (auto)" : ""));
        Transport tr = host.newTransport(c);
        if (tr == null) { setConn("off", "Not connected", ""); autoFailed(); return; }
        pending = tr;
        String err = null;
        try {
            tr.open(c.device, new Transport.Sink() {
                @Override public void onData(String text) { if (myGen == gen) elm.onData(text); }
                @Override public void onClosed(String reason) {
                    if (myGen != gen || conn.equals("off")) return;
                    linkLost = reason;
                    elm.abort();
                    wake();
                }
            });
        } catch (IOException e) {
            err = e.getMessage() == null ? "could not connect" : e.getMessage();
        }
        pending = null;
        if (cancelConnect || stop) { tr.close(); setConn("off", "Not connected", ""); return; }
        if (err != null) {
            tr.close();
            host.log("connect failed: " + err);
            if (auto) { setConn("off", "Not connected", ""); autoFailed(); }
            else { setConn("off", "Not connected", "bad"); host.onNotice("connect_failed", err); }
            return;
        }
        transport = tr;
        elm.setTransport(tr);
        setConn("init", "Setting up adapter…", "busy");
        String[] resp = new String[1];
        int res = init(c, resp);
        if (res == 2 || cancelConnect || stop || linkLost != null) {
            if (linkLost == null) { closeTransport(); setConn("off", "Not connected", ""); }
            return;   // a dropped link is cleaned up by the main loop
        }
        if (res == 1) {
            closeTransport();
            if (auto) { setConn("off", "Truck not answering", ""); autoFailed(); host.log("adapter found but the truck did not answer"); }
            else { setConn("off", "Not connected", ""); host.onNotice("truck_silent", resp[0]); }
            return;
        }
        autoOk();
        resetAuto();
        timeouts = 0; fastOk = true;
        setConn("on", (tr instanceof DemoTransport ? "Demo · " : "") + (protoName.isEmpty() ? "Connected" : protoName), "ok");
    }

    /** 0 = ready, 1 = the truck didn't answer, 2 = aborted. */
    private int init(Config c, String[] respOut) {
        String r = tx("ATZ", 4000);
        Matcher m = Pattern.compile("ELM327[^\\n]*", Pattern.CASE_INSENSITIVE).matcher(r);
        if (m.find()) elmVersion = m.group();
        else { String[] ls = r.split("\n"); elmVersion = ls[ls.length - 1]; }
        for (String cmd : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP" + c.proto}) tx(cmd, 1500);
        setConn("init", "Talking to the truck…", "busy");
        r = tx("0100", 15000);
        int[] b = Elm.bytesFor(r, "41", "00");
        if (b == null && !c.proto.equals("0") && !aborted()) {
            host.log("no answer on protocol " + c.proto + " (" + r + "), trying auto");
            tx("ATSP0", 1500);
            r = tx("0100", 15000);
            b = Elm.bytesFor(r, "41", "00");
        }
        if (aborted()) return 2;
        if (b == null) { respOut[0] = r.isEmpty() ? "nothing" : r; return 1; }
        Set<String> sup = new HashSet<>();
        sup.add("RV");
        addMask(sup, 0, b);
        for (int base : new int[]{0x20, 0x40}) {
            String key = String.format("%02X", base);
            if (!sup.contains(key)) break;
            int[] bb = Elm.bytesFor(tx("01" + key, 3000), "41", key);
            if (bb != null) addMask(sup, base, bb); else break;
        }
        if (aborted()) return 2;
        protoName = tx("ATDP", 1500).replaceFirst("(?i)^AUTO,\\s*", "");
        if (!aborted()) {   // which vehicle is this? (mode 09 PID 02; many older vehicles don't answer, which is fine)
            vin = Elm.parseVin(tx("0902", 5000));
            effCfg = null;
            host.log("VIN: " + (vin.isEmpty() ? "(not reported)" : vin));
        }
        supported = sup;
        host.log("supported: " + new ArrayList<>(sup));
        return 0;
    }

    private boolean aborted() { return cancelConnect || stop || linkLost != null; }

    private static void addMask(Set<String> sup, int base, int[] bytes) {
        for (int i = 0; i < 32; i++) {
            int by = i >> 3;
            if (by < bytes.length && (bytes[by] & (0x80 >> (i & 7))) != 0) sup.add(String.format("%02X", base + i + 1));
        }
    }

    private void closeTransport() {
        Transport t = transport;
        transport = null;
        gen++;
        elm.setTransport(null);
        if (t != null) t.close();
    }

    /** Ends the connection (statusText "" = a normal disconnect). A recording in progress is saved. */
    private void stopConn(String statusText) {
        if (recOn) recStop(null);
        elm.abort();
        closeTransport();
        vin = ""; effCfg = null;
        setConn("off", statusText.isEmpty() ? "Not connected" : statusText, statusText.isEmpty() ? "" : "bad");
    }

    private void lost(String msg) {
        if (conn.equals("off")) return;
        host.log("connection lost: " + msg);
        stopConn("Connection lost");
        host.onNotice("toast", msg);
    }

    private String tx(String cmd, long timeoutMs) {
        Config c = cfg;
        if (c.debug) host.log("> " + cmd);
        String r = elm.send(cmd, timeoutMs);
        if (c.debug) host.log("< " + r.replace("\n", " | "));
        return r;
    }

    // ================================================================= polling

    private List<Pids.Pid> plan(boolean fast) {
        Config c = cfg;
        Set<String> sup = supported;
        List<Pids.Pid> out = new ArrayList<>();
        for (Pids.Pid p : Pids.ORDER) {
            if (!sup.contains(p.key)) continue;
            String mode = c.mode(p.key);
            if (mode.equals("off")) continue;
            if (mode.equals("fast") == fast) out.add(p);
        }
        return out;
    }

    private static final Pattern RV = Pattern.compile("([\\d.]+)\\s*V", Pattern.CASE_INSENSITIVE);
    private boolean readSkipped;

    /** Value, or null if there is none. Sets readSkipped when the read didn't happen properly (timeout, link closing). */
    private Double readPid(Pids.Pid p) {
        readSkipped = false;
        if (p.adapter) {
            Matcher m = RV.matcher(tx("ATRV", 1500));
            if (!m.find()) return null;
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return null; }
        }
        boolean fast = cfg.fast && fastOk;
        String r = tx("01" + p.key + (fast ? "1" : ""), 1500);
        if (r.equals("TIMEOUT")) { timeouts++; readSkipped = true; return null; }
        if (r.equals("STOPPED") || r.equals("NOT CONNECTED") || r.equals("ERROR")) { readSkipped = true; return null; }
        timeouts = 0;
        if (fast && r.trim().equals("?")) { fastOk = false; host.log("adapter does not support fast replies; turned them off"); return readPid(p); }
        int[] b = Elm.bytesFor(r, "41", p.key);
        if (b == null || b.length == 0) return null;
        double v = p.decode(b);
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private void pollOnce() {
        long t0 = System.currentTimeMillis();
        List<Pids.Pid> fast = plan(true), slow = plan(false);
        List<Pids.Pid> list = new ArrayList<>(fast);
        if (!slow.isEmpty()) list.add(slow.get(slowIdx++ % slow.size()));
        if (list.isEmpty()) { waitCmd(500); return; }
        for (Pids.Pid p : list) {
            if (!conn.equals("on") || stop || linkLost != null) break;
            Double v = readPid(p);
            if (!readSkipped) { if (v == null) liveRaw.remove(p.key); else liveRaw.put(p.key, v); }
        }
        if (!conn.equals("on") || stop || linkLost != null) return;
        if (timeouts >= 6) { lost("The adapter stopped answering"); return; }
        cycle();
        long el = System.currentTimeMillis() - t0;
        if (el < 120) waitCmd(120 - el);
    }

    // ================================================================= samples, gear, events

    Gear gearFor(double rpm, Double mph) {
        if (mph == null || mph < 8 || rpm < 500) return new Gear(null, null);
        Config c = effective();
        double r = rpm / mph, K = c.k();
        int pick = -1;
        for (int i = 0; i < c.gears.length; i++) {
            if (r >= K * c.gears[i] * 0.94) { if (pick < 0 || c.gears[i] > c.gears[pick]) pick = i; }
        }
        if (pick < 0) return new Gear(null, null);
        double slip = r / (K * c.gears[pick]) - 1;
        if (slip > 0.45) return new Gear(null, slip);
        return new Gear(pick + 1, slip);
    }

    private Sample sampleNow() {
        Map<String, Double> raw = new HashMap<>(liveRaw);
        Double rpm = raw.get("0C"), kmh = raw.get("0D"), tps = raw.get("11");
        Double mph = kmh == null ? null : kmh * 0.621371;
        Gear g = rpm == null ? new Gear(null, null) : gearFor(rpm, mph);
        return new Sample(System.currentTimeMillis(), raw, rpm, mph, tps, g.gear, g.slip);
    }

    private void cycle() {
        Sample s = sampleNow();
        synchronized (lock) {
            hist.add(s);
            int cut = 0;
            while (cut < hist.size() && s.t - hist.get(cut).t > 125000) cut++;
            if (cut > 0) hist.subList(0, cut).clear();
        }
        List<String> evs = detect(s);
        String ev = String.join("; ", evs);
        for (String e : evs) addEvent(e, s.t);
        autoTick(s);
        if (recOn) recAdd(s, ev);
        if (host.uiAttached()) host.onSample(sampleJson(s));
    }

    static String sampleJson(Sample s) {
        StringBuilder b = new StringBuilder("{\"t\":").append(s.t).append(",\"raw\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : s.raw.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(e.getKey()).append("\":").append(Json.num(e.getValue()));
        }
        b.append("},\"gear\":").append(s.gear == null ? "null" : s.gear.toString());
        b.append(",\"slip\":").append(s.slip == null ? "null" : Json.num(s.slip)).append('}');
        return b.toString();
    }

    private Map<String, Object> addEvent(String text, long t) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("t", t);
        e.put("text", text + (cruise ? " [cruise]" : ""));
        synchronized (lock) {
            events.add(0, e);
            while (events.size() > 30) events.remove(events.size() - 1);
        }
        if (recOn) recEvent(e);
        host.onEvent(Json.stringify(e));
        host.log("event: " + e.get("text"));
        return e;
    }

    private void mark() {
        if (!conn.equals("on")) return;
        Sample s = sampleNow();
        String text = "Marked" + (s.rpm != null ? " (" + Math.round(s.rpm) + " rpm, " + (s.mph == null ? "?" : String.valueOf(Math.round(s.mph))) + " mph)" : "");
        Map<String, Object> e = addEvent(text, s.t);
        if (recOn) recAdd(s, String.valueOf(e.get("text")));
    }

    private void toggleCruise() {
        cruise = !cruise;
        if (conn.equals("on")) {
            Sample s = sampleNow();
            Map<String, Object> e = addEvent(cruise ? "Cruise set ON (by you)" : "Cruise set off (by you)", s.t);
            if (recOn) recAdd(s, String.valueOf(e.get("text")));
        }
        pushState();
    }

    private List<Sample> filter(long now, long minAge, long maxAge, java.util.function.Predicate<Sample> extra) {
        List<Sample> out = new ArrayList<>();
        for (int i = hist.size() - 1; i >= 0; i--) {
            Sample x = hist.get(i);
            long age = now - x.t;
            if (age > maxAge) break;
            if (age >= minAge && extra.test(x)) out.add(x);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Event texts for this sample (usually none): RPM jumps / downshifts / flares, and throttle jumps. Same rules as the page's detect(). */
    List<String> detect(final Sample s) {
        List<String> out = new ArrayList<>();
        Config c = cfg;
        if (pj != null && s.t - pj.t >= 1200) {
            PendingJump p = pj;
            pj = null;
            List<Sample> recent = filter(s.t, Long.MIN_VALUE, 600, x -> x.rpm != null && x.mph != null && x.mph > 0);
            double[] rs = new double[recent.size()];
            for (int i = 0; i < rs.length; i++) rs[i] = recent.get(i).rpm / recent.get(i).mph;
            Arrays.sort(rs);
            Gear g = rs.length == 0 ? new Gear(null, null) : gearFor(rs[rs.length >> 1] * 50, 50.0);
            double peak = p.peak;
            for (Sample x : recent) peak = Math.max(peak, x.rpm);
            String span = p.from + "→" + Math.round(peak) + " rpm";
            if (p.g1 != null && g.gear != null && g.gear < p.g1)
                out.add("Downshift " + p.g1 + "→" + g.gear + " (" + span + (g.slip != null && g.slip > 0.04 ? ", converter unlocked" : "") + ")");
            else if (g.gear == null) out.add("RPM flare, no gear matches (" + span + ") — possible slip");
            else out.add("RPM jump (" + span + ", gear " + g.gear + ")");
        }
        if (pj != null && s.rpm != null) pj.peak = Math.max(pj.peak, s.rpm);
        if (s.rpm == null || s.mph == null) return out;
        if (pj == null && s.t - lastJumpT >= 4000 && s.mph >= 20) {
            List<Sample> past = filter(s.t, 250, 2000, x -> x.rpm != null && x.mph != null);
            if (!past.isEmpty()) {
                Sample base = past.get(0);
                for (Sample x : past) if (x.rpm < base.rpm) base = x;
                if (s.rpm - base.rpm >= c.rpmJump && Math.abs(s.mph - base.mph) <= 4) {
                    final long bt = base.t;
                    List<Sample> before = new ArrayList<>();
                    for (Sample x : hist) if (bt - x.t >= 0 && bt - x.t <= 1500 && x.gear != null) before.add(x);
                    pj = new PendingJump();
                    pj.t = s.t; pj.from = Math.round(base.rpm); pj.peak = s.rpm;
                    pj.g1 = before.isEmpty() ? base.gear : before.get(before.size() - 1).gear;
                    lastJumpT = s.t;
                }
            }
        }
        if (s.tps != null && s.mph >= 20 && s.t - lastThrT >= 4000) {
            List<Sample> prior = filter(s.t, 1000, 3000, x -> x.tps != null);
            if (prior.size() >= 2) {
                double ref = -Double.MAX_VALUE;
                for (Sample x : prior) ref = Math.max(ref, x.tps);
                if (s.tps - ref >= c.tpsJump && s.tps >= 40) {
                    lastThrT = s.t;
                    out.add("Throttle jump (" + Math.round(ref) + "%→" + Math.round(s.tps) + "%)");
                }
            }
        }
        return out;
    }

    /** Test hook: pushes a sample through the history and the event detector like a polling cycle would. */
    List<String> debugFeed(Sample s) {
        synchronized (lock) {
            hist.add(s);
            int cut = 0;
            while (cut < hist.size() && s.t - hist.get(cut).t > 125000) cut++;
            if (cut > 0) hist.subList(0, cut).clear();
        }
        return detect(s);
    }

    // ================================================================= auto-record

    private void resetAuto() { suppressed = false; runSince = 0; offSince = 0; }

    /** Starts recording once the engine has been running for 2 s; saves the drive after RPM has read zero for the engine-off delay. */
    private void autoTick(Sample s) {
        Config c = cfg;
        if (!c.autoRec || !conn.equals("on") || s.rpm == null) { runSince = 0; offSince = 0; return; }
        if (s.rpm >= 400) {
            offSince = 0;
            if (runSince == 0) runSince = s.t;
            if (recOn) engineSeen = true;
            if (!recOn && !suppressed && s.t - runSince >= 2000) recStart(true);
        } else if (s.rpm < 150) {
            runSince = 0;
            if (offSince == 0) offSince = s.t;
            if (s.t - offSince >= c.autoStopSec * 1000L) {
                boolean was = suppressed;
                suppressed = false;
                if (recOn && engineSeen) recStop("Engine off");
                else if (was) pushState();
            }
        }
    }

    // ================================================================= recording

    private void recStart(boolean auto) {
        if (!conn.equals("on")) { if (!auto) host.onNotice("toast", "Connect first"); return; }
        if (recOn) return;
        Config c = effective();
        List<Pids.Pid> cols = new ArrayList<>(plan(true));
        cols.addAll(plan(false));
        String id = "d" + System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        m.put("id", id);
        m.put("start", now);
        m.put("end", now);
        m.put("rows", 0L);
        List<Object> colList = new ArrayList<>();
        for (Pids.Pid p : cols) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("k", p.key); cm.put("label", p.label); cm.put("unit", Pids.unit(p, c.units));
            colList.add(cm);
        }
        m.put("cols", colList);
        m.put("units", c.units);
        metaEvents = new ArrayList<>();
        m.put("events", metaEvents);
        m.put("demo", transport instanceof DemoTransport);
        m.put("proto", protoName);
        m.put("k", c.k());
        m.put("gears", c.gears.clone());
        Config.Veh rv = cfg.vehicleFor(vin);
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("id", rv == null ? "" : rv.id);
        vm.put("name", rv == null ? "" : rv.name);
        vm.put("vin", vin);
        m.put("vehicle", vm);
        m.put("app", appVersion);
        m.put("auto", auto);
        synchronized (lock) {
            meta = m; recCols = cols; recUnits = c.units; recId = id; recRows = 0; recStartT = now; recEndT = now;
            rowBuf.setLength(0);
            engineSeen = liveRaw.get("0C") != null && liveRaw.get("0C") >= 400;
            recOn = true;
        }
        store.write(id + ".rows", "", false);
        saveMeta();
        lastFlush = now;
        host.log("recording " + id + (auto ? " (auto)" : ""));
        host.onNotice("toast", auto ? "Engine running: recording started" : "Recording");
        pushState();
    }

    private void recAdd(Sample s, String ev) {
        synchronized (lock) {
            if (!recOn) return;
            StringBuilder r = rowBuf;
            r.append('[').append(s.t).append(',').append(s.gear == null ? "null" : s.gear.toString()).append(',').append(cruise ? 1 : 0).append(',');
            Json.quote(r, ev == null ? "" : ev);
            for (Pids.Pid p : recCols) {
                Double v = s.raw.get(p.key);
                r.append(',').append(v == null ? "null" : Json.fixed(Pids.toDisp(p, v, recUnits), Math.min(3, p.decimals + 1)));
            }
            r.append("]\n");
            recRows++;
            recEndT = s.t;
            meta.put("rows", recRows);
            meta.put("end", s.t);
        }
    }

    @SuppressWarnings("unchecked")
    private void recEvent(Map<String, Object> e) {
        synchronized (lock) { if (metaEvents != null) metaEvents.add(e); }
        saveMeta();
    }

    private void saveMeta() {
        String id, json;
        synchronized (lock) {
            if (meta == null) return;
            id = (String) meta.get("id");
            json = Json.stringify(meta);
        }
        store.write(id + ".json", json, false);
    }

    private void flushIfDue(boolean force) {
        if (!recOn) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < 2000) return;
        lastFlush = now;
        String text;
        String id;
        synchronized (lock) {
            if (meta == null) return;
            id = (String) meta.get("id");
            text = rowBuf.toString();
            rowBuf.setLength(0);
        }
        if (!text.isEmpty()) store.write(id + ".rows", text, true);
        saveMeta();
    }

    private void recStop(String why) {
        if (!recOn) return;
        flushIfDue(true);
        long dur, rows;
        synchronized (lock) {
            meta.put("done", true);
            dur = recEndT - recStartT;
            rows = recRows;
        }
        saveMeta();
        synchronized (lock) { recOn = false; meta = null; metaEvents = null; recId = ""; }
        host.log("stopped recording" + (why == null ? "" : " (" + why + ")"));
        host.onNotice("toast", (why == null ? "" : why + ": ") + "saved drive, " + fmtDur(dur) + ", " + rows + " rows");
        pushState();
    }
}
