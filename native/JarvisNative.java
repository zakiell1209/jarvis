package com.jarvis.agent;

import android.Manifest;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Нативный плагин Джарвиса: полный доступ к файлам телефона, сеть без CORS, shell.
 * Удаление переносит в корзину, перезапись делает копию (папка Android/data/com.jarvis.agent/files).
 */
@CapacitorPlugin(name = "JarvisNative")
public class JarvisNative extends Plugin {

    // ---------- вспомогательное ----------
    private boolean granted() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void need() throws IOException {
        if (!granted()) throw new IOException("Нет доступа ко всем файлам: разрешите его на вкладке «Файлы»");
    }

    private File root() { return Environment.getExternalStorageDirectory(); }

    private File fileOf(String p) {
        if (p == null || p.isEmpty()) return root();
        if (p.startsWith("/")) return new File(p);
        return new File(root(), p);
    }

    private File stashDir(String kind) {
        File base = getContext().getExternalFilesDir(null);
        if (base == null) base = getContext().getFilesDir();
        File d = new File(base, kind);
        d.mkdirs();
        return d;
    }

    private void fail(PluginCall call, Exception e) {
        String m = e.getMessage();
        call.reject(m == null ? e.toString() : m);
    }

    private JSObject info(File f) throws Exception {
        JSObject o = new JSObject();
        o.put("name", f.getName());
        o.put("path", f.getAbsolutePath());
        o.put("dir", f.isDirectory());
        o.put("size", f.isDirectory() ? 0L : f.length());
        o.put("mtime", f.lastModified());
        return o;
    }

    private void delRec(File f) throws IOException {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) delRec(x);
        }
        if (!f.delete() && f.exists()) throw new IOException("Не удалось удалить: " + f);
    }

    private void copyRec(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] c = src.listFiles();
            if (c != null) for (File x : c) copyRec(x, new File(dst, x.getName()));
        } else {
            File p = dst.getParentFile();
            if (p != null) p.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        }
    }

    // kind = "trash" (переместить) или "backups" (скопировать). Возвращает путь копии.
    private String stash(File f, String kind, boolean move) throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dst = new File(stashDir(kind), ts + "_" + f.getName());
        int i = 1;
        while (dst.exists()) dst = new File(stashDir(kind), ts + "_" + (i++) + "_" + f.getName());
        if (move) {
            if (!f.renameTo(dst)) {
                copyRec(f, dst);
                delRec(f);
            }
        } else {
            copyRec(f, dst);
        }
        try (FileOutputStream os = new FileOutputStream(new File(dst.getParentFile(), dst.getName() + ".origin.txt"))) {
            os.write(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        }
        return dst.getAbsolutePath();
    }

    private byte[] readLimited(InputStream is, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = is.read(b)) > 0) {
            int room = max - bo.size();
            if (room <= 0) break;
            bo.write(b, 0, Math.min(n, room));
        }
        return bo.toByteArray();
    }


    // ---------- защита: подтверждение владельца, обратная связь и журнал ----------
    // Опасные действия ставятся в ожидание, пока владелец не нажмёт на экране телефона.
    // «Разрешить» — действие выполняется само, и результат возвращается в тот же вызов.
    // «Отказать» или 5 минут без ответа — вызов завершается ошибкой с объяснением.
    // Аварийная остановка: пустой файл KILL в Android/data/com.jarvis.agent/files.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> APPROVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private String levelOf(String cat) {
        if (cat.equals("read") || cat.equals("net")) return "auto";
        return "ask";
    }

    private boolean killed() {
        File base = getContext().getExternalFilesDir(null);
        return base != null && new File(base, "KILL").exists();
    }

    private void log(String cat, String what, String lv) {
        try {
            File f = new File(stashDir("log"), "actions.log");
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " | " + cat + " | " + lv + " | " + what + "\n";
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** true — можно выполнять сейчас. false — вызов уже завершён или ждёт ответа владельца. */
    private boolean gate(PluginCall call, String method, String cat, String what) {
        try {
            String lv = killed() ? "deny" : levelOf(cat);
            log(cat, what, lv);
            if (lv.equals("auto")) return true;
            if (lv.equals("deny")) { call.reject("Аварийная остановка активна (файл KILL)"); return false; }

            String key = cat + "|" + what;
            Long until = APPROVED.remove(key);
            if (until != null && until > System.currentTimeMillis()) return true;

            call.setKeepAlive(true);
            final boolean[] decided = {false};
            Handler main = new Handler(Looper.getMainLooper());
            main.postDelayed(() -> {
                if (!decided[0]) { decided[0] = true; call.reject("Владелец не ответил за 5 минут: " + what); }
            }, 300000L);

            getActivity().runOnUiThread(() -> new AlertDialog.Builder(getActivity())
                    .setTitle("Джарвис: " + cat)
                    .setMessage(what)
                    .setCancelable(false)
                    .setPositiveButton("Разрешить", (d, w) -> {
                        if (decided[0]) return;
                        decided[0] = true;
                        APPROVED.put(key, System.currentTimeMillis() + 120000L);
                        new Thread(() -> {
                            try {
                                getClass().getMethod(method, PluginCall.class).invoke(this, call);
                            } catch (Exception e) {
                                call.reject(String.valueOf(e.getMessage()));
                            }
                        }).start();
                    })
                    .setNegativeButton("Отказать", (d, w) -> {
                        if (decided[0]) return;
                        decided[0] = true;
                        call.reject("Владелец отказал: " + what);
                    })
                    .show());
            return false;
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return false;
        }
    }

    // ---------- доступ ----------
    @PluginMethod
    public void access(PluginCall call) {
        try {
            JSObject o = new JSObject();
            o.put("granted", granted());
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("root", root().getAbsolutePath());
            o.put("trash", stashDir("trash").getAbsolutePath());
            o.put("backups", stashDir("backups").getAbsolutePath());
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getContext().getPackageName()));
                    getActivity().startActivity(i);
                } catch (Exception e) {
                    Intent i2 = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    getActivity().startActivity(i2);
                }
            } else {
                ActivityCompat.requestPermissions(getActivity(), new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4242);
            }
            call.resolve();
        } catch (Exception e) { fail(call, e); }
    }

    // ---------- файлы ----------
    @PluginMethod
    public void list(PluginCall call) {
        try {
            need();
            File d = fileOf(call.getString("path"));
            File[] fs = d.listFiles();
            if (fs == null) throw new IOException("Нет доступа или это не папка: " + d);
            Arrays.sort(fs, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            int lim = call.getInt("limit", 3000);
            JSArray arr = new JSArray();
            for (int i = 0; i < fs.length && i < lim; i++) arr.put(info(fs[i]));
            JSObject o = new JSObject();
            o.put("path", d.getAbsolutePath());
            o.put("entries", arr);
            o.put("total", fs.length);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void stat(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!f.exists()) {
                JSObject o = new JSObject();
                o.put("exists", false);
                o.put("path", f.getAbsolutePath());
                call.resolve(o);
                return;
            }
            JSObject o = info(f);
            o.put("exists", true);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void readText(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!f.isFile()) throw new IOException("Файл не найден: " + f);
            long off = call.getData().optLong("offset", 0);
            int max = Math.min(call.getInt("maxBytes", 2000000), 8000000);
            byte[] buf = new byte[max];
            int n = 0;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(off);
                while (n < max) {
                    int k = r.read(buf, n, max - n);
                    if (k < 0) break;
                    n += k;
                }
            }
            boolean bin = false;
            for (int i = 0; i < Math.min(n, 4096); i++) if (buf[i] == 0) { bin = true; break; }
            JSObject o = new JSObject();
            o.put("size", f.length());
            o.put("binary", bin);
            o.put("text", bin ? "" : new String(buf, 0, n, StandardCharsets.UTF_8));
            o.put("truncated", off + n < f.length());
            o.put("offset", off);
            o.put("read", n);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!gate(call, "writeText", "write", f.getAbsolutePath())) return;
            String text = call.getString("text", "");
            boolean append = call.getBoolean("append", false);
            String bk = null;
            if (f.exists() && !append) bk = stash(f, "backups", false);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream os = new FileOutputStream(f, append)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            JSObject o = new JSObject();
            o.put("path", f.getAbsolutePath());
            o.put("size", f.length());
            if (bk != null) o.put("backup", bk);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void replaceInFile(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!gate(call, "replaceInFile", "write", f.getAbsolutePath())) return;
            String find = call.getString("find", "");
            String rep = call.getString("replace", "");
            boolean all = call.getBoolean("all", true);
            if (find.isEmpty()) throw new IOException("Пустой фрагмент для поиска");
            if (!f.isFile()) throw new IOException("Файл не найден: " + f);
            if (f.length() > 30000000L) throw new IOException("Файл слишком большой для замены (>30 МБ)");
            byte[] data;
            try (FileInputStream in = new FileInputStream(f)) { data = readLimited(in, 30000000); }
            String s = new String(data, StandardCharsets.UTF_8);
            int count = 0;
            StringBuilder sb = new StringBuilder();
            int from = 0;
            while (true) {
                int i = s.indexOf(find, from);
                if (i < 0) break;
                sb.append(s, from, i).append(rep);
                from = i + find.length();
                count++;
                if (!all) break;
            }
            JSObject o = new JSObject();
            o.put("replaced", count);
            if (count > 0) {
                sb.append(s, from, s.length());
                String bk = stash(f, "backups", false);
                try (FileOutputStream os = new FileOutputStream(f, false)) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                o.put("backup", bk);
            }
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void delete(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!gate(call, "delete", "delete", f.getAbsolutePath())) return;
            if (!f.exists()) throw new IOException("Не найдено: " + f);
            String t = stash(f, "trash", true);
            JSObject o = new JSObject();
            o.put("trash", t);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void move(PluginCall call) {
        try {
            need();
            File a = fileOf(call.getString("from"));
            File b = fileOf(call.getString("to"));
            if (!gate(call, "move", "move", a.getAbsolutePath() + " -> " + b.getAbsolutePath())) return;
            if (!a.exists()) throw new IOException("Не найдено: " + a);
            if (b.isDirectory() && !a.getName().equals(b.getName())) b = new File(b, a.getName());
            String bk = null;
            if (b.exists()) bk = stash(b, "backups", true);
            File p = b.getParentFile();
            if (p != null) p.mkdirs();
            if (!a.renameTo(b)) {
                copyRec(a, b);
                delRec(a);
            }
            JSObject o = new JSObject();
            o.put("path", b.getAbsolutePath());
            if (bk != null) o.put("backup", bk);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void copy(PluginCall call) {
        try {
            need();
            File a = fileOf(call.getString("from"));
            File b = fileOf(call.getString("to"));
            if (!gate(call, "copy", "copy", a.getAbsolutePath() + " -> " + b.getAbsolutePath())) return;
            if (!a.exists()) throw new IOException("Не найдено: " + a);
            if (b.isDirectory() && !a.getName().equals(b.getName())) b = new File(b, a.getName());
            String bk = null;
            if (b.exists()) bk = stash(b, "backups", true);
            copyRec(a, b);
            JSObject o = new JSObject();
            o.put("path", b.getAbsolutePath());
            if (bk != null) o.put("backup", bk);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        try {
            need();
            File d = fileOf(call.getString("path"));
            if (!gate(call, "mkdir", "write", d.getAbsolutePath())) return;
            if (!d.exists() && !d.mkdirs()) throw new IOException("Не удалось создать папку: " + d);
            JSObject o = new JSObject();
            o.put("path", d.getAbsolutePath());
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void readBase64(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!f.isFile()) throw new IOException("Файл не найден: " + f);
            long off = call.getData().optLong("offset", 0);
            int len = Math.min(call.getInt("length", 2000000), 3000000);
            byte[] buf = new byte[len];
            int n = 0;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(off);
                while (n < len) {
                    int k = r.read(buf, n, len - n);
                    if (k < 0) break;
                    n += k;
                }
            }
            JSObject o = new JSObject();
            o.put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
            o.put("read", n);
            o.put("size", f.length());
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    @PluginMethod
    public void writeBase64(PluginCall call) {
        try {
            need();
            File f = fileOf(call.getString("path"));
            if (!gate(call, "writeBase64", "write", f.getAbsolutePath())) return;
            boolean append = call.getBoolean("append", false);
            byte[] data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            String bk = null;
            if (f.exists() && !append) bk = stash(f, "backups", false);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream os = new FileOutputStream(f, append)) { os.write(data); }
            JSObject o = new JSObject();
            o.put("size", f.length());
            if (bk != null) o.put("backup", bk);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    // ---------- поиск ----------
    private void walk(File d, String name, String content, int max, long maxBytes, long deadline,
                      JSArray res, int[] cnt, int depth, int maxDepth) throws Exception {
        if (cnt[0] >= max || System.currentTimeMillis() > deadline || depth > maxDepth) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (cnt[0] >= max || System.currentTimeMillis() > deadline) return;
            cnt[1]++;
            boolean nameOk = name == null || f.getName().toLowerCase(Locale.ROOT).contains(name);
            if (f.isDirectory()) {
                if (content == null && nameOk && name != null) {
                    res.put(info(f));
                    cnt[0]++;
                }
                walk(f, name, content, max, maxBytes, deadline, res, cnt, depth + 1, maxDepth);
                continue;
            }
            if (!nameOk) continue;
            if (content == null) {
                res.put(info(f));
                cnt[0]++;
                continue;
            }
            if (f.length() > maxBytes || f.length() == 0) continue;
            JSArray matches = new JSArray();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                int ln = 0;
                String needle = content.toLowerCase(Locale.ROOT);
                while ((line = br.readLine()) != null) {
                    ln++;
                    if (ln == 1 && line.indexOf('\u0000') >= 0) break;
                    if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                        JSObject m = new JSObject();
                        m.put("line", ln);
                        m.put("text", line.length() > 300 ? line.substring(0, 300) : line);
                        matches.put(m);
                        if (matches.length() >= 3) break;
                    }
                }
            } catch (IOException e) {
                continue;
            }
            if (matches.length() > 0) {
                JSObject o = info(f);
                o.put("matches", matches);
                res.put(o);
                cnt[0]++;
            }
        }
    }

    @PluginMethod
    public void search(PluginCall call) {
        try {
            need();
            File r = fileOf(call.getString("root"));
            String name = call.getString("name");
            if (name != null) name = name.toLowerCase(Locale.ROOT);
            String content = call.getString("content");
            if (content != null && content.isEmpty()) content = null;
            int max = call.getInt("maxResults", 100);
            long maxBytes = call.getInt("maxFileBytes", 2000000);
            long deadline = System.currentTimeMillis() + call.getInt("timeoutMs", 60000);
            JSArray res = new JSArray();
            int[] cnt = new int[]{0, 0};
            walk(r, name, content, max, maxBytes, deadline, res, cnt, 0, call.getInt("maxDepth", 15));
            JSObject o = new JSObject();
            o.put("results", res);
            o.put("scanned", cnt[1]);
            o.put("timedOut", System.currentTimeMillis() > deadline);
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    // ---------- интернет (без CORS) ----------
    @PluginMethod
    public void http(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url == null) throw new IOException("Нет url");
            String method = call.getString("method", "GET");
            int timeout = call.getInt("timeoutMs", 30000);
            String saveTo = call.getString("saveTo");
            if (saveTo != null && !gate(call, "http", "download", url + " -> " + saveTo)) return;
            if (!gate(call, "http", "net", url)) return;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Jarvis/1.0");
            JSObject h = call.getObject("headers");
            if (h != null) {
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.getString(k));
                }
            }
            String body = call.getString("body");
            if (body != null && !"GET".equalsIgnoreCase(method)) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            JSObject o = new JSObject();
            o.put("status", code);
            String ct = c.getContentType();
            o.put("contentType", ct == null ? "" : ct);
            o.put("url", c.getURL().toString());
            if (is == null) {
                o.put("text", "");
            } else if (saveTo != null) {
                File f = fileOf(saveTo);
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                long total = 0;
                try (OutputStream os = new FileOutputStream(f, false); InputStream in = is) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) { os.write(b, 0, n); total += n; }
                }
                o.put("saved", f.getAbsolutePath());
                o.put("size", total);
            } else {
                int max = Math.min(call.getInt("maxBytes", 3000000), 8000000);
                byte[] data = readLimited(is, max);
                o.put("text", new String(data, StandardCharsets.UTF_8));
            }
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }

    // ---------- shell ----------
    @PluginMethod
    public void shell(PluginCall call) {
        try {
            String cmd = call.getString("cmd");
            if (cmd == null) throw new IOException("Нет команды");
            if (!gate(call, "shell", "shell", cmd)) return;
            int timeout = call.getInt("timeoutMs", 60000);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            pb.directory(root());
            Process p = pb.start();
            final byte[][] out = new byte[1][];
            Thread t = new Thread(() -> {
                try { out[0] = readLimited(p.getInputStream(), 200000); } catch (IOException ignored) { }
            });
            t.start();
            long end = System.currentTimeMillis() + timeout;
            boolean done = false;
            while (System.currentTimeMillis() < end) {
                try { p.exitValue(); done = true; break; } catch (IllegalThreadStateException ex) { Thread.sleep(100); }
            }
            if (!done) p.destroy();
            t.join(2000);
            JSObject o = new JSObject();
            o.put("output", out[0] == null ? "" : new String(out[0], StandardCharsets.UTF_8));
            o.put("timedOut", !done);
            if (done) o.put("exit", p.exitValue());
            call.resolve(o);
        } catch (Exception e) { fail(call, e); }
    }
}
