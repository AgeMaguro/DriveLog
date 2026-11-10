package com.agemaguro.drivelog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** The private drive files (<id>.json and <id>.rows). Same folder and rules as the web page's store. */
final class FileStore {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private final File dir;

    FileStore(File dir) { this.dir = dir; if (!dir.exists()) dir.mkdirs(); }

    private File file(String name) throws IOException {
        if (name == null || !SAFE.matcher(name).matches()) throw new IOException("bad file name");
        return new File(dir, name);
    }

    boolean write(String name, String text, boolean append) {
        try (FileOutputStream o = new FileOutputStream(file(name), append)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) { return false; }
    }

    String read(String name) {
        try {
            File f = file(name);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) { return null; }
    }

    String[] list() {
        String[] n = dir.list();
        return n == null ? new String[0] : n;
    }

    boolean delete(String name) {
        try { return file(name).delete(); } catch (IOException e) { return false; }
    }
}
