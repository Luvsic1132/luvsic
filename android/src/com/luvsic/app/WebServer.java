package com.luvsic.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简本地 HTTP 服务：静态资源（打包在 assets/ui）+ /api + /audio(Range) + /cover。 */
public class WebServer {
    private static final String TAG = "LuvsicSrv";

    private final Context ctx;
    private final Api api;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private int port;
    private volatile boolean running = true;

    public WebServer(Context ctx) {
        this.ctx = ctx;
        this.api = new Api(ctx);
    }

    public int getPort() {
        return port;
    }

    public void start() {
        // 优先固定端口，方便开发期 adb forward 调试；占用则退回随机端口
        try {
            serverSocket = new ServerSocket(18756, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            try {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e2) {
                Log.e(TAG, "bind failed", e2);
                return;
            }
        }
        port = serverSocket.getLocalPort();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket sock = serverSocket.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(sock);
                            }
                        });
                    } catch (IOException e) {
                        if (running) Log.w(TAG, "accept: " + e);
                    }
                }
            }
        }, "luvsic-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ───────────────────────── 请求处理 ─────────────────────────
    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            String head = readHead(in);
            if (head == null) {
                sock.close();
                return;
            }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) {
                sock.close();
                return;
            }
            String method = req[0];
            String rawPath = req[1];
            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                            lines[i].substring(c + 1).trim());
                }
            }
            int q = rawPath.indexOf('?');
            String path = q < 0 ? rawPath : rawPath.substring(0, q);
            String query = q < 0 ? "" : rawPath.substring(q + 1);
            Map<String, String> params = parseQuery(query);

            int len = 0;
            try {
                len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (Exception ignored) {
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n <= 0) break;
                off += n;
            }

            OutputStream out = sock.getOutputStream();
            try {
                route(method, path, params, body, headers, out);
            } finally {
                try {
                    out.flush();
                } catch (Exception ignored) {
                }
            }
            sock.close();
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev2 = -1, prev1 = -1, c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            if (prev1 == '\r' && prev2 == '\n' && c == '\r') { /* wait for \n */ }
            if (prev1 == '\r' && c == '\n' && prev2 == '\n') {
                byte[] b = buf.toByteArray();
                return new String(b, 0, b.length - 4, "UTF-8");
            }
            prev2 = prev1;
            prev1 = c;
            if (buf.size() > 65536) break;
        }
        if (buf.size() == 0) return null;
        return new String(buf.toByteArray(), "UTF-8");
    }

    private void route(String method, String path, Map<String, String> params,
                       byte[] body, Map<String, String> headers, OutputStream out) throws IOException {
        if (path.equals("/ready")) {
            sendText(out, 200, "ok", "text/plain; charset=utf-8");
            return;
        }
        if (path.equals("/report")) {
            if (body.length > 0) Log.i(TAG, "FE-REPORT " + new String(body, "UTF-8"));
            sendText(out, 200, "ok", "text/plain");
            return;
        }
        if (path.equals("/api") && method.equals("POST")) {
            String name = params.get("name");
            String result;
            try {
                JSONArray args = body.length > 0 ? new JSONArray(new String(body, "UTF-8")) : new JSONArray();
                result = api.dispatch(name, args);
            } catch (Exception e) {
                result = "{\"__error\":\"" + esc(String.valueOf(e)) + "\"}";
            }
            sendText(out, 200, result, "application/json; charset=utf-8");
            return;
        }
        if (path.equals("/audio") || path.equals("/music")) {
            String p = params.get("p");
            serveFileRange(out, p, headers);
            return;
        }
        if (path.equals("/cover")) {
            String p = params.get("p");
            byte[] img = api.readCover(p);
            if (img == null) {
                sendStatus(out, 404);
            } else {
                sendBytes(out, 200, img, guessType(p, true));
            }
            return;
        }
        // 自定义壁纸（安卓相册选图后存在 App 私有目录）
        if (path.equals("/wall/custom.jpg")) {
            File wf = new File(ctx.getFilesDir(), "custom_wall.jpg");
            if (!wf.isFile()) {
                sendStatus(out, 404);
            } else {
                serveLocalFile(out, wf, "image/jpeg", method);
            }
            return;
        }
        // 静态资源：assets/ui 下
        serveAsset(out, path);
    }

    /** 返回本地文件；支持 HEAD，避免每次壁纸请求都重传整张图。 */
    private void serveLocalFile(OutputStream out, File f, String type, String method) throws IOException {
        long total = f.length();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(total).append("\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        sb.append("Pragma: no-cache\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if ("HEAD".equalsIgnoreCase(method)) return;
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            fis.close();
        }
    }

    private void serveAsset(OutputStream out, String path) throws IOException {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.isEmpty()) rel = "index.html";
        String asset = "ui/" + rel;
        AssetManager am = ctx.getAssets();
        InputStream is;
        try {
            is = am.open(asset);
        } catch (IOException e) {
            sendText(out, 404, "not found: " + rel, "text/plain; charset=utf-8");
            return;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        sendBytes(out, 200, bos.toByteArray(), guessType(rel, false));
    }

    private void serveFileRange(OutputStream out, String path, Map<String, String> headers) throws IOException {
        if (path == null) {
            sendStatus(out, 400);
            return;
        }
        File f = new File(path);
        if (!f.isFile()) {
            Log.w(TAG, "audio missing: " + path);
            sendStatus(out, 404);
            return;
        }
        long total = f.length();
        long start = 0, end = total - 1;
        String range = headers.get("range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            try {
                String spec = range.substring(6).split(",")[0];
                int dash = spec.indexOf('-');
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                if (!a.isEmpty()) start = Long.parseLong(a);
                if (!b.isEmpty()) end = Long.parseLong(b);
                if (end >= total) end = total - 1;
                if (start > end) {
                    sendStatus(out, 416);
                    return;
                }
                partial = true;
            } catch (Exception ignored) {
            }
        }
        long len = end - start + 1;
        StringBuilder sb = new StringBuilder();
        sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: ").append(guessType(path, false)).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        if (partial) sb.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long left = len;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = raf.read(buf, 0, want);
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } finally {
            raf.close();
        }
    }

    private static void sendText(OutputStream out, int code, String text, String type) throws IOException {
        byte[] b = text.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(b.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(b);
    }

    private static void sendBytes(OutputStream out, int code, byte[] data, String type) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(data);
    }

    private static void sendStatus(OutputStream out, int code) throws IOException {
        String msg = "HTTP/1.1 " + code + " ERR\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(msg.getBytes("UTF-8"));
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> m = new HashMap<String, String>();
        if (qs == null || qs.isEmpty()) return m;
        for (String pair : qs.split("&")) {
            int i = pair.indexOf('=');
            try {
                if (i < 0) m.put(URLDecoder.decode(pair, "UTF-8"), "");
                else m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String guessType(String name, boolean image) {
        String s = name.toLowerCase(Locale.US);
        if (s.endsWith(".html") || s.endsWith(".htm")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".json")) return "application/json; charset=utf-8";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".webp")) return "image/webp";
        if (s.endsWith(".ico")) return "image/x-icon";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".flac")) return "audio/flac";
        if (s.endsWith(".m4a") || s.endsWith(".aac") || s.endsWith(".mp4")) return "audio/mp4";
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".ogg") || s.endsWith(".opus")) return "audio/ogg";
        return image ? "image/jpeg" : "application/octet-stream";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
