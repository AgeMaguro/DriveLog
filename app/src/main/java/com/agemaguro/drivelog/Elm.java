package com.agemaguro.drivelog;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-command-at-a-time conversation with an ELM327: send a command, wait for the answer that ends with '>'. */
final class Elm {
    private final Object lock = new Object();
    private final StringBuilder buf = new StringBuilder();
    private String pendingCmd;
    private String result;
    private boolean waiting;
    private volatile Transport transport;

    void setTransport(Transport t) {
        synchronized (lock) { buf.setLength(0); }
        transport = t;
    }

    /** Called with whatever the adapter sent (any thread). */
    void onData(String s) {
        synchronized (lock) {
            buf.append(s);
            int i = buf.indexOf(">");
            if (i < 0) return;
            if (!waiting) { buf.delete(0, i + 1); return; }
            result = clean(buf.substring(0, i), pendingCmd);
            buf.delete(0, i + 1);
            waiting = false;
            lock.notifyAll();
        }
    }

    /** Wakes a send() that is waiting (used when the connection is being closed). Its result becomes "STOPPED". */
    void abort() {
        synchronized (lock) {
            if (waiting) { waiting = false; result = "STOPPED"; lock.notifyAll(); }
        }
    }

    /** Sends one command and returns the cleaned answer, or "TIMEOUT" / "ERROR" / "STOPPED" / "NOT CONNECTED". */
    String send(String cmd, long timeoutMs) {
        Transport t = transport;
        if (t == null) return "NOT CONNECTED";
        synchronized (lock) {
            buf.setLength(0);
            pendingCmd = cmd;
            waiting = true;
            result = null;
        }
        try {
            t.write(cmd + "\r");
        } catch (IOException e) {
            synchronized (lock) { waiting = false; }
            return "ERROR";
        }
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (lock) {
            while (waiting) {
                long leftMs = (end - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0) { waiting = false; return "TIMEOUT"; }
                try { lock.wait(leftMs); } catch (InterruptedException e) { waiting = false; Thread.currentThread().interrupt(); return "STOPPED"; }
            }
            return result == null ? "STOPPED" : result;
        }
    }

    /** Drops the echo, blank lines and "SEARCHING..." / "BUS INIT" noise. */
    static String clean(String raw, String cmd) {
        StringBuilder out = new StringBuilder();
        String up = cmd == null ? "" : cmd.toUpperCase();
        for (String line : raw.replace('\r', '\n').split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.toUpperCase().equals(up)) continue;
            if (l.toUpperCase().startsWith("SEARCHING") || l.toUpperCase().matches("^BUS ?INIT.*")) continue;
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return out.toString();
    }

    private static final Pattern HEX = Pattern.compile("^[0-9A-F]+$");
    private static final Pattern PREFIX = Pattern.compile("^[0-9A-F]+:");

    /** Finds "41 0C ..." in an answer and returns the data bytes after it, or null. */
    static int[] bytesFor(String resp, String mode, String pid) {
        String want = (mode + pid).toUpperCase();
        for (String raw : resp.split("\n")) {
            String line = raw.replaceAll("\\s", "").toUpperCase();
            Matcher m = PREFIX.matcher(line);
            if (m.find()) line = line.substring(m.end());
            if (line.startsWith(want) && HEX.matcher(line).matches()) {
                String hex = line.substring(want.length());
                int[] out = new int[hex.length() / 2];
                for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                return out;
            }
        }
        return null;
    }
}
