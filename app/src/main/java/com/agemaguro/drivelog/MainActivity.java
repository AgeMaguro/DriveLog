package com.agemaguro.drivelog;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * DriveLog: a full-screen WebView running assets/index.html (the whole app), plus a small Java bridge
 * ("AndroidDL") for the things a web page can't do: Bluetooth serial to the ELM327 OBD2 adapter, saving
 * drive files, exporting CSV to Downloads, sharing, keeping the screen on, and the in-app updater.
 *
 * Debugging: JavaScript console messages go to logcat (tag "DriveLog"). Android-side crashes are saved and
 * handed to the app's debug log on the next start (URL #...&crash=...).
 */
public class MainActivity extends Activity {
    private static final String TAG = "DriveLog";
    private static final String PREFS = "drivelog_debug";
    private static final String HOME = "file:///android_asset/";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String RELEASE = "https://github.com/AgeMaguro/DriveLog/releases/download/drivelog-latest/";
    private static final int REQ_BT = 41;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,80}");

    private WebView web;
    private final Object btLock = new Object();
    private volatile BluetoothSocket socket;
    private volatile OutputStream btOut;
    private long downloadId = -1;
    private BroadcastReceiver dlReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashSaver();

        int bg = Color.rgb(0x12, 0x14, 0x18);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // settings + debug log
        s.setTextZoom(100);
        s.setAllowFileAccess(false);    // only the app's own built-in files (android_asset still works)
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "AndroidDL");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith(HOME)) return false;
                Log.w(TAG, "Blocked navigation to " + url);
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                saveCrash("WebView renderer gone (crashed=" + detail.didCrash() + ")");
                if (web != null) { web.destroy(); web = null; }
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String line = m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")";
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
                else if (m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, line);
                else Log.d(TAG, line);
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(HOME + "index.html#v=" + versionName() + "&vc=" + versionCode() + takeSavedCrash());
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a line of JavaScript in the page (from any thread). */
    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter btAdapter() {
        BluetoothManager m = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private File storeDir() {
        File d = new File(getFilesDir(), "drives");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File storeFile(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) throw new IOException("bad file name");
        return new File(storeDir(), name);
    }

    // ---------------------------------------------------------------- Bluetooth

    private void doConnect(String address) {
        closeSocket(null, false);
        js("DLNative.onState('connecting','')");
        try {
            BluetoothAdapter ad = btAdapter();
            if (ad == null) throw new IOException("This phone has no Bluetooth");
            if (!ad.isEnabled()) throw new IOException("Bluetooth is turned off");
            if (!hasBtPermission()) throw new IOException("Bluetooth permission not granted");
            BluetoothDevice dev = ad.getRemoteDevice(address);
            try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }

            BluetoothSocket sock = null;
            Exception last = null;
            for (int attempt = 0; attempt < 3 && sock == null; attempt++) {
                BluetoothSocket c = null;
                try {
                    if (attempt == 0) c = dev.createRfcommSocketToServiceRecord(SPP);
                    else if (attempt == 1) c = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                    else c = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                    c.connect();
                    sock = c;
                } catch (Exception e) {
                    last = e;
                    Log.w(TAG, "Connect attempt " + attempt + " failed: " + e);
                    if (c != null) try { c.close(); } catch (IOException ignored) { }
                }
            }
            if (sock == null) throw new IOException(last == null ? "could not connect" : String.valueOf(last.getMessage()));

            final BluetoothSocket mine = sock;
            final InputStream in = sock.getInputStream();
            synchronized (btLock) { socket = sock; btOut = sock.getOutputStream(); }
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[1024];
                try {
                    while (true) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        String chunk = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                        js("DLNative.onData(" + q(chunk) + ")");
                    }
                } catch (IOException ignored) { }
                if (socket == mine) closeSocket("Connection to the adapter was lost", true);
            }, "obd-reader");
            reader.start();
            js("DLNative.onState('connected','')");
        } catch (SecurityException e) {
            js("DLNative.onState('error'," + q("Bluetooth permission not granted") + ")");
        } catch (Exception e) {
            js("DLNative.onState('error'," + q(String.valueOf(e.getMessage())) + ")");
        }
    }

    private void closeSocket(String reason, boolean notify) {
        BluetoothSocket s;
        synchronized (btLock) { s = socket; socket = null; btOut = null; }
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
            if (notify) js("DLNative.onState('disconnected'," + q(reason) + ")");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_BT) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            js("DLNative.onPermission(" + ok + ")");
        }
    }

    // ---------------------------------------------------------------- export

    /** Writes a file to Downloads/DriveLog (no storage permission needed on Android 10+). */
    private Uri writeDownload(String name, String mime, String text) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DriveLog");
        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("Could not create the file");
        try (OutputStream o = getContentResolver().openOutputStream(u)) {
            if (o == null) throw new IOException("Could not open the file");
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return u;
    }

    // ---------------------------------------------------------------- updater

    private void startUpdateDownload() {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                js("DLNative.onUpdateProgress('permission','')");
                return;
            }
            File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DriveLog-update.apk");
            if (f.exists()) f.delete();
            final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(RELEASE + "DriveLog.apk"));
            rq.setTitle("DriveLog update");
            rq.setMimeType("application/vnd.android.package-archive");
            rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "DriveLog-update.apk");
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadId = dm.enqueue(rq);
            if (dlReceiver == null) {
                dlReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (id != downloadId) return;
                        Uri u = dm.getUriForDownloadedFile(id);
                        if (u == null) { js("DLNative.onUpdateProgress('error','Download failed')"); return; }
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(i); js("DLNative.onUpdateProgress('installing','')"); }
                        catch (Exception e) { js("DLNative.onUpdateProgress('error'," + q(e.getMessage()) + ")"); }
                    }
                };
                IntentFilter ff = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(dlReceiver, ff, Context.RECEIVER_EXPORTED);
                else registerReceiver(dlReceiver, ff);
            }
            js("DLNative.onUpdateProgress('downloading','')");
        } catch (Exception e) {
            js("DLNative.onUpdateProgress('error'," + q(e.getMessage()) + ")");
        }
    }

    // ---------------------------------------------------------------- the bridge

    private class Bridge {
        @JavascriptInterface public boolean isNative() { return true; }

        @JavascriptInterface public String btStatus() {
            try {
                JSONObject o = new JSONObject();
                BluetoothAdapter ad = btAdapter();
                o.put("supported", ad != null);
                o.put("enabled", ad != null && ad.isEnabled());
                o.put("permission", hasBtPermission());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void requestBtPermission() {
            if (Build.VERSION.SDK_INT < 31) { js("DLNative.onPermission(true)"); return; }
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT));
        }

        @JavascriptInterface public String pairedDevices() {
            JSONArray arr = new JSONArray();
            try {
                BluetoothAdapter ad = btAdapter();
                if (ad == null || !hasBtPermission()) return "[]";
                Set<BluetoothDevice> set = ad.getBondedDevices();
                if (set != null) for (BluetoothDevice d : set) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? "(no name)" : d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
            } catch (Exception e) { Log.w(TAG, "pairedDevices: " + e); }
            return arr.toString();
        }

        @JavascriptInterface public void connect(final String address) { new Thread(() -> doConnect(address), "obd-connect").start(); }

        @JavascriptInterface public void write(String text) {
            OutputStream o = btOut;
            if (o == null) return;
            try { o.write(text.getBytes(StandardCharsets.ISO_8859_1)); o.flush(); }
            catch (IOException e) { closeSocket("Sending to the adapter failed", true); }
        }

        @JavascriptInterface public void disconnect() { closeSocket("", true); }

        @JavascriptInterface public void openBtSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void keepScreenOn(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        // --- simple private file store for drives (app storage, survives app updates)
        @JavascriptInterface public boolean storeWrite(String name, String text, boolean append) {
            try (FileOutputStream o = new FileOutputStream(storeFile(name), append)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (IOException e) { Log.e(TAG, "storeWrite " + name + ": " + e); return false; }
        }

        @JavascriptInterface public String storeRead(String name) {
            try {
                File f = storeFile(name);
                if (!f.exists()) return null;
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0;
                    while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
                }
                return new String(b, StandardCharsets.UTF_8);
            } catch (IOException e) { Log.e(TAG, "storeRead " + name + ": " + e); return null; }
        }

        @JavascriptInterface public String storeList() {
            JSONArray arr = new JSONArray();
            String[] names = storeDir().list();
            if (names != null) for (String n : names) arr.put(n);
            return arr.toString();
        }

        @JavascriptInterface public boolean storeDelete(String name) {
            try { return storeFile(name).delete(); } catch (IOException e) { return false; }
        }

        // --- export
        @JavascriptInterface public String saveFile(String name, String mime, String text) {
            try { writeDownload(name, mime, text); return "Downloads/DriveLog/" + name; }
            catch (Exception e) { return "ERROR: " + e.getMessage(); }
        }

        @JavascriptInterface public String shareFile(String name, String mime, String text) {
            try {
                Uri u = writeDownload(name, mime, text);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.putExtra(Intent.EXTRA_SUBJECT, name);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Share " + name);
                runOnUiThread(() -> startActivity(chooser));
                return "ok";
            } catch (Exception e) { return "ERROR: " + e.getMessage(); }
        }

        @JavascriptInterface public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("DriveLog", text));
            });
        }

        // --- updater
        @JavascriptInterface public void checkUpdate() {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(RELEASE + "version.txt").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setUseCaches(false);
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    String line;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        line = br.readLine();
                    }
                    js("DLNative.onUpdateInfo(" + q(line == null ? "" : line.trim()) + ",'')");
                } catch (Exception e) {
                    js("DLNative.onUpdateInfo(''," + q(String.valueOf(e.getMessage())) + ")");
                }
            }, "update-check").start();
        }

        @JavascriptInterface public void installUpdate() { runOnUiThread(MainActivity.this::startUpdateDownload); }
    }

    // ---------------------------------------------------------------- crash saver + lifecycle

    private void installCrashSaver() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            saveCrash(sw.toString());
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    private void saveCrash(String text) {
        try {
            Log.e(TAG, "Saving crash for next start: " + text);
            String t = text.length() > 1500 ? text.substring(0, 1500) : text;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("crash", t).commit();
        } catch (Exception ignored) { }
    }

    private String takeSavedCrash() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String c = p.getString("crash", null);
            if (c == null) return "";
            p.edit().remove("crash").apply();
            return "&crash=" + URLEncoder.encode(c, "UTF-8");
        } catch (Exception e) { return ""; }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private long versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return 0; }
    }

    /** Back button: the page handles it first (close a dialog, leave a drive's details); otherwise the app closes. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.onBack ? onBack() : false", handled -> {
            if (!"true".equals(handled)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        // Logging keeps running while the screen is on; this just saves anything buffered.
        if (web != null) web.evaluateJavascript("window.saveNow && saveNow()", null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeSocket(null, false);
        if (dlReceiver != null) { try { unregisterReceiver(dlReceiver); } catch (Exception ignored) { } dlReceiver = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
