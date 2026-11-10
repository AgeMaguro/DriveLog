package com.agemaguro.drivelog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs the logging engine against the simulated truck on a normal JVM (no phone needed).
 *
 *   javac -encoding UTF-8 -d /tmp/jout app/src/main/java/com/agemaguro/drivelog/{Json,Config,Pids,Transport,Elm,FileStore,DemoTransport,Engine}.java tests/EngineSelfTest.java
 *   java -cp /tmp/jout com.agemaguro.drivelog.EngineSelfTest [all|drive|retry|control|silent|detect <samples.json>]
 */
public class EngineSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    static boolean waitFor(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
        }
        return c.getAsBoolean();
    }

    static class TestHost implements Engine.Host {
        Supplier<Transport> factory;
        final AtomicInteger attempts = new AtomicInteger();
        final List<String> notices = Collections.synchronizedList(new ArrayList<>());
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> states = Collections.synchronizedList(new ArrayList<>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final List<String> queries = Collections.synchronizedList(new ArrayList<>());
        volatile boolean ui = true;
        volatile int samples = 0;
        @Override public Transport newTransport(Config cfg) { attempts.incrementAndGet(); return factory.get(); }
        @Override public void onState(String json) { states.add(json); }
        @Override public void onSample(String json) { samples++; }
        @Override public void onEvent(String json) { events.add(json); }
        @Override public void onNotice(String kind, String text) { notices.add(kind + ":" + text); }
        @Override public void onQuery(int id, String json) { queries.add(id + ":" + json); }
        @Override public boolean uiAttached() { return ui; }
        @Override public void log(String line) { logs.add(line); }
        boolean notice(String prefix) { synchronized (notices) { for (String n : notices) if (n.startsWith(prefix)) return true; } return false; }
        boolean event(String text) { synchronized (events) { for (String n : events) if (n.contains(text)) return true; } return false; }
    }

    static File tmp() throws IOException { return Files.createTempDirectory("dltest").toFile(); }

    static Map<String, Object> state(Engine e) { return Json.parseObject(e.stateJson()); }

    // ---------------------------------------------------------------------------------------------

    static void drive() throws Exception {
        System.out.println("drive: auto-connect, auto-record, events, marks, labels, auto-stop");
        File dir = tmp();
        TestHost h = new TestHost();
        final DemoTransport[] cur = new DemoTransport[1];
        h.factory = () -> { DemoTransport d = new DemoTransport(); d.timeScale = 4; cur[0] = d; return d; };
        Engine e = new Engine(new FileStore(dir), h, "test");
        e.setConfig("{\"device\":\"AA:BB\",\"autoRec\":true,\"autoStopSec\":5,\"units\":\"us\",\"proto\":\"2\"}");
        e.start();
        check("auto-connects by itself", waitFor(e::isConnected, 15000));
        check("auto-record starts once the engine runs", waitFor(e::isRecording, 10000));
        check("state says auto recording", Boolean.TRUE.equals(((Map<?, ?>) state(e).get("rec")).get("auto")));
        Map<String, Object> st0 = state(e);
        check("VIN read from the vehicle", "1GCCS196858123456".equals(st0.get("vin")));
        check("samples are pushed while the page is open", waitFor(() -> h.samples > 10, 5000));
        Thread.sleep(3000);
        e.markRequest();
        check("mark creates an event", waitFor(() -> h.event("Marked"), 3000));
        String mk = null;
        synchronized (h.events) { for (String s : h.events) if (s.contains("Marked")) mk = s; }
        long markT = ((Number) Json.parseObject(mk).get("t")).longValue();
        e.labelEvent(markT, "Hesitation");
        Thread.sleep(17000);   // ~70 s of truck time: includes the cruise-control downshift
        check("downshift detected", h.event("Downshift"));
        e.cruiseRequest();
        Thread.sleep(500);
        check("cruise note recorded", h.event("Cruise set ON"));
        cur[0].engineOff = true;
        check("recording auto-saves after the engine-off delay", waitFor(() -> !e.isRecording(), 15000));
        check("toast says engine off", h.notice("toast:Engine off: saved drive"));
        check("still connected after auto-stop", e.isConnected());
        Thread.sleep(1500);
        check("does not restart while engine is off", !e.isRecording());
        cur[0].engineOff = false;
        check("restarts when the engine runs again", waitFor(e::isRecording, 10000));
        e.recStopRequest();
        check("manual stop works", waitFor(() -> !e.isRecording(), 3000));
        Thread.sleep(4000);
        check("manual stop pauses auto-record while the engine runs", !e.isRecording() && Boolean.TRUE.equals(state(e).get("suppressed")));
        e.queryRequest(7, java.util.Arrays.asList("03", "07"), 3000);
        check("trouble-code query answers", waitFor(() -> !h.queries.isEmpty(), 5000) && h.queries.get(0).equals("7:[\"43017104200000\",\"47030000000000\"]"));
        e.shutdown();
        check("engine stopped", !e.isRunning());

        // inspect the files
        String[] names = new FileStore(dir).list();
        List<String> metas = new ArrayList<>();
        for (String n : names) if (n.endsWith(".json")) metas.add(n);
        Collections.sort(metas);
        check("two drives saved", metas.size() == 2);
        FileStore st = new FileStore(dir);
        Map<String, Object> m = Json.parseObject(st.read(metas.get(0)));
        String id = (String) m.get("id");
        String[] rows = st.read(id + ".rows").split("\n");
        check("meta done + auto", Boolean.TRUE.equals(m.get("done")) && Boolean.TRUE.equals(m.get("auto")));
        check("meta row count matches the rows file (" + rows.length + ")", ((Number) m.get("rows")).intValue() == rows.length);
        List<?> cols = (List<?>) m.get("cols");
        Object first = Json.parse(rows[rows.length / 2]);
        check("row has 4 + one value per column", first instanceof List && ((List<?>) first).size() == 4 + cols.size());
        System.out.println("     sample row: " + rows[rows.length / 2]);
        List<?> evs = (List<?>) m.get("events");
        boolean labelled = false, down = false;
        for (Object o : evs) { Map<?, ?> em = (Map<?, ?>) o; if ("Hesitation".equals(em.get("label"))) labelled = true; if (String.valueOf(em.get("text")).startsWith("Downshift")) down = true; }
        check("label saved in meta", labelled);
        check("downshift saved in meta", down);
        boolean markRow = false;
        for (String r : rows) if (r.contains("\"Marked (")) markRow = true;
        check("mark row written", markRow);
        Map<?, ?> vm = (Map<?, ?>) m.get("vehicle");
        check("drive is tagged with the VIN", vm != null && "1GCCS196858123456".equals(vm.get("vin")));
        check("meta units/cols match the page's format", "us".equals(m.get("units")) && ((Map<?, ?>) cols.get(0)).containsKey("label") && ((Map<?, ?>) cols.get(0)).containsKey("unit"));
        System.out.println("     events: " + evs.size() + ", rows: " + rows.length + ", first col: " + cols.get(0));
    }

    static void vehicles() throws Exception {
        System.out.println("vehicles: profile chosen by VIN, own gearing");
        // parser checks (J1850 frames, CAN multi-line, noise)
        check("parseVin J1850", "1GCCS196858123456".equals(Elm.parseVin("49 02 01 00 00 00 31\n49 02 02 47 43 43 53\n49 02 03 31 39 36 38\n49 02 04 35 38 31 32\n49 02 05 33 34 35 36")));
        check("parseVin CAN", "WVWZZZ1JZ3W386752".equals(Elm.parseVin("014\n0: 49 02 01 57 56 57\n1: 5A 5A 5A 31 4A 5A 33\n2: 57 33 38 36 37 35 32")));
        check("parseVin NO DATA", "".equals(Elm.parseVin("NO DATA")));
        TestHost h = new TestHost();
        h.factory = () -> { DemoTransport d = new DemoTransport(); d.timeScale = 4; return d; };
        File dir = tmp();
        Engine e = new Engine(new FileStore(dir), h, "test");
        String cfgJson = "{\"device\":\"AA:BB\",\"autoRec\":true,\"autoStopSec\":5,\"gears\":[3.06,1.63,1.0,0.7],\"axle\":3.73,\"tire\":30,"
            + "\"vehicleId\":\"v1\",\"vehicles\":[{\"id\":\"v1\",\"name\":\"Other car\",\"vin\":\"\",\"gears\":[2.5,1.5,1.0,0.8],\"axle\":3.4,\"tire\":28},"
            + "{\"id\":\"v2\",\"name\":\"My truck\",\"vin\":\"1GCCS196858123456\",\"gears\":[3.06,1.63,1.0,0.7],\"axle\":3.73,\"tire\":30}]}";
        e.setConfig(cfgJson);
        e.start();
        check("connects", waitFor(e::isConnected, 15000));
        check("state names the matching profile", "v2".equals(state(e).get("vehicleId")));
        check("records", waitFor(e::isRecording, 10000));
        e.recStopRequest();
        check("stopped", waitFor(() -> !e.isRecording(), 3000));
        e.shutdown();
        FileStore st = new FileStore(dir);
        String metaName = null;
        for (String n : st.list()) if (n.endsWith(".json")) metaName = n;
        Map<String, Object> m = Json.parseObject(st.read(metaName));
        Map<?, ?> vm = (Map<?, ?>) m.get("vehicle");
        check("drive tagged with profile id and name", vm != null && "v2".equals(vm.get("id")) && "My truck".equals(vm.get("name")));
        check("drive uses that profile's gearing, not the app's chosen one", Math.abs(((Number) m.get("k")).doubleValue() - 336.13 * 3.73 / 30.0) < 1e-6);
        // unknown VIN: tagged by VIN only
        TestHost h2 = new TestHost();
        h2.factory = h.factory;
        File dir2 = tmp();
        Engine e2 = new Engine(new FileStore(dir2), h2, "test");
        e2.setConfig(cfgJson.replace("1GCCS196858123456", "1GCCS196858999999"));
        e2.start();
        check("connects (unknown VIN)", waitFor(e2::isConnected, 15000));
        check("no profile matched", "".equals(state(e2).get("vehicleId")) && "1GCCS196858123456".equals(state(e2).get("vin")));
        e2.shutdown();
    }

    static void retry() throws Exception {
        System.out.println("retry: adapter absent at first, then present");
        TestHost h = new TestHost();
        h.factory = () -> new Transport() {
            DemoTransport d = new DemoTransport();
            @Override public void open(String a, Sink s) throws IOException { if (h.attempts.get() < 2) throw new IOException("could not connect"); d.timeScale = 4; d.open(a, s); }
            @Override public void write(String t) throws IOException { d.write(t); }
            @Override public void close() { d.close(); }
        };
        Engine e = new Engine(new FileStore(tmp()), h, "test");
        e.setConfig("{\"device\":\"AA:BB\",\"autoRec\":false}");
        e.start();
        check("first attempt happens after ~3 s", waitFor(() -> h.attempts.get() == 1, 6000));
        check("quiet failure: no popup notice", waitFor(() -> "off".equals(state(e).get("conn")), 3000) && !h.notice("connect_failed"));
        check("shows waiting state", Boolean.TRUE.equals(state(e).get("waiting")));
        check("retries and connects", waitFor(e::isConnected, 20000));
        check("attempts = 2", h.attempts.get() == 2);
        check("no recording when auto-record is off", !e.isRecording());
        e.shutdown();
    }

    static void control() throws Exception {
        System.out.println("control: disconnect, reconnect, link drop");
        TestHost h = new TestHost();
        final DemoTransport[] cur = new DemoTransport[1];
        h.factory = () -> { DemoTransport d = new DemoTransport(); d.timeScale = 4; cur[0] = d; return d; };
        Engine e = new Engine(new FileStore(tmp()), h, "test");
        e.setConfig("{\"device\":\"AA:BB\",\"autoRec\":true,\"autoStopSec\":5}");
        e.start();
        check("connects", waitFor(e::isConnected, 15000));
        check("records", waitFor(e::isRecording, 10000));
        e.disconnectRequest();
        check("user disconnect", waitFor(() -> !e.isConnected(), 3000));
        check("recording saved on disconnect", !e.isRecording());
        int n = h.attempts.get();
        Thread.sleep(6000);
        check("no auto-reconnect after a user disconnect", !e.isConnected() && h.attempts.get() == n && Boolean.TRUE.equals(state(e).get("userOff")));
        e.connectRequest();
        check("manual connect works", waitFor(e::isConnected, 10000));
        check("records again", waitFor(e::isRecording, 10000));
        cur[0].dropLink("Connection to the adapter was lost");
        check("link drop: disconnected", waitFor(() -> !e.isConnected(), 3000));
        check("link drop: recording saved", !e.isRecording());
        check("link drop: toast", h.notice("toast:Connection to the adapter was lost"));
        check("auto-reconnects after a link drop", waitFor(e::isConnected, 15000));
        check("and records again", waitFor(e::isRecording, 10000));
        e.shutdown();
        check("shutdown saved the drive", !e.isRecording() && !e.isConnected());
    }

    static void silent() throws Exception {
        System.out.println("silent: adapter answers but the truck does not");
        TestHost h = new TestHost();
        h.factory = () -> { DemoTransport d = new DemoTransport(); d.timeScale = 4; d.silent = true; return d; };
        Engine e = new Engine(new FileStore(tmp()), h, "test");
        e.setConfig("{\"device\":\"AA:BB\",\"autoConn\":false,\"proto\":\"0\"}");
        e.start();
        e.connectRequest();
        check("manual connect reports the silent truck", waitFor(() -> h.notice("truck_silent"), 25000));
        check("back to disconnected", waitFor(() -> "off".equals(state(e).get("conn")), 3000));
        e.shutdown();
    }

    // Prints the events the detector finds for a list of samples ([t, rpm, mph, tps, gear|null, slip|null]), for comparing with the web page.
    static void detect(String file) throws Exception {
        Engine e = new Engine(new FileStore(tmp()), new TestHost(), "test");
        e.setConfig("{\"device\":\"x\",\"autoRec\":false}");
        List<?> list = (List<?>) Json.parse(new String(Files.readAllBytes(new File(file).toPath()), "UTF-8"));
        int gearMismatch = 0;
        for (Object o : list) {
            List<?> r = (List<?>) o;
            long t = ((Number) r.get(0)).longValue();
            Double rpm = r.get(1) == null ? null : ((Number) r.get(1)).doubleValue();
            Double mph = r.get(2) == null ? null : ((Number) r.get(2)).doubleValue();
            Double tps = r.get(3) == null ? null : ((Number) r.get(3)).doubleValue();
            Integer gear = r.get(4) == null ? null : ((Number) r.get(4)).intValue();
            Double slip = r.get(5) == null ? null : ((Number) r.get(5)).doubleValue();
            Engine.Gear g = rpm == null ? new Engine.Gear(null, null) : e.gearFor(rpm, mph);
            boolean same = (g.gear == null ? gear == null : g.gear.equals(gear)) && ((g.slip == null) == (slip == null) && (g.slip == null || Math.abs(g.slip - slip) < 1e-9));
            if (!same) gearMismatch++;
            for (String ev : e.debugFeed(new Engine.Sample(t, new java.util.HashMap<>(), rpm, mph, tps, gear, slip))) System.out.println("EV " + t + " " + ev);
        }
        System.out.println("GEARMISMATCH " + gearMismatch);
    }

    public static void main(String[] a) throws Exception {
        String which = a.length > 0 ? a[0] : "all";
        if (which.equals("all") || which.equals("drive")) drive();
        if (which.equals("all") || which.equals("vehicles")) vehicles();
        if (which.equals("all") || which.equals("retry")) retry();
        if (which.equals("all") || which.equals("control")) control();
        if (which.equals("all") || which.equals("silent")) silent();
        if (which.equals("detect")) detect(a[1]);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
