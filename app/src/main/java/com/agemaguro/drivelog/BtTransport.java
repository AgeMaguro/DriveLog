package com.agemaguro.drivelog;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Classic Bluetooth serial (SPP) link to the ELM327 adapter. Used by the engine, in the service or the app. */
final class BtTransport implements Transport {
    private static final String TAG = "DriveLog";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context ctx;
    private final Object lock = new Object();
    private BluetoothSocket socket;      // connected socket
    private BluetoothSocket attempting;  // socket being connected right now (so close() can abort it)
    private OutputStream out;
    private volatile boolean closed;
    private Sink sink;
    private boolean reported;

    BtTransport(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    @Override
    public void open(String address, Sink s) throws IOException {
        this.sink = s;
        try {
            BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = m == null ? null : m.getAdapter();
            if (ad == null) throw new IOException("This phone has no Bluetooth");
            if (!ad.isEnabled()) throw new IOException("Bluetooth is turned off");
            BluetoothDevice dev = ad.getRemoteDevice(address);
            try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }

            BluetoothSocket sock = null;
            Exception last = null;
            for (int attempt = 0; attempt < 3 && sock == null && !closed; attempt++) {
                BluetoothSocket c = null;
                try {
                    if (attempt == 0) c = dev.createRfcommSocketToServiceRecord(SPP);
                    else if (attempt == 1) c = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                    else c = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                    synchronized (lock) { attempting = c; }
                    c.connect();
                    sock = c;
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    last = e;
                    Log.w(TAG, "Connect attempt " + attempt + " failed: " + e);
                    if (c != null) try { c.close(); } catch (IOException ignored) { }
                } finally {
                    synchronized (lock) { attempting = null; }
                }
            }
            if (closed) { if (sock != null) try { sock.close(); } catch (IOException ignored) { } throw new IOException("Cancelled"); }
            if (sock == null) throw new IOException(last == null ? "could not connect" : String.valueOf(last.getMessage()));

            final BluetoothSocket mine = sock;
            final InputStream in = sock.getInputStream();
            synchronized (lock) { socket = sock; out = sock.getOutputStream(); }
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[1024];
                try {
                    while (true) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        Sink k = sink;
                        if (k != null) k.onData(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                    }
                } catch (IOException ignored) { }
                dropped("Connection to the adapter was lost");
            }, "obd-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission not granted");
        }
    }

    private void dropped(String why) {
        if (closed) return;
        synchronized (lock) { if (reported) return; reported = true; }
        close();
        Sink k = sink;
        if (k != null) k.onClosed(why);
    }

    @Override
    public void write(String text) throws IOException {
        OutputStream o;
        synchronized (lock) { o = out; }
        if (o == null) throw new IOException("not connected");
        try {
            o.write(text.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
        } catch (IOException e) {
            dropped("Sending to the adapter failed");
            throw e;
        }
    }

    @Override
    public void close() {
        closed = true;
        BluetoothSocket a, s;
        synchronized (lock) { a = attempting; s = socket; socket = null; out = null; }
        if (a != null) try { a.close(); } catch (IOException ignored) { }
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
