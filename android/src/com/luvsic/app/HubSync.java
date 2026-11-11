package com.luvsic.app;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 手机版：GitHub 单仓库同步 + 8 位账号ID 登录（与桌面版共用同一套云端） */
public class HubSync {
    private static final String TAG = "LuvsicHub";
    public static final String DEFAULT_INVITE = "";   // 开源版不内置仓库，用户自行配置
    /** 管理员账号ID：只有这个 ID 能看到「账号管理」面板，可开关别人的登录权限 */
    public static final String ADMIN_ID = "";   // 开源版不预设管理员

    /** 是否配置了管理员 ID（开源版默认没有，此时跳过所有管理员校验） */
    private static boolean hasAdmin() {
        return ADMIN_ID != null && !ADMIN_ID.isEmpty();
    }

    /** 判断某个账号是不是管理员；没配置管理员时返回 true（放行） */
    private static boolean isAdminId(String aid) {
        if (!hasAdmin()) return true;
        return isAdminId(aid);
    }

    /** 管理员校验：没配置管理员时直接放行 */
    private static boolean requireAdmin(String me) {
        if (!hasAdmin()) return true;
        return isAdminId(me);
    }
    private static final String SYNC_FILE = "sync.json";
    private static final String USER_FILE = "user.json";
    private static final String STATS_FILE = "stats.json";
    private static final String TOMB_FILE = "tombstones.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static class Creds {
        String provider = "github";
        String repo;
        String token;
    }

    // ── 文件工具 ──
    static JSONObject readJson(File f, String dflt) {
        try {
            if (!f.isFile()) return new JSONObject(dflt);
            byte[] b = readAll(new FileInputStream(f));
            return new JSONObject(new String(b, UTF8));
        } catch (Exception e) {
            try {
                return new JSONObject(dflt);
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
    }

    static void writeJson(File f, JSONObject o) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(o.toString().getBytes(UTF8));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "writeJson", e);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ── 邀请码解析 ──
    static Creds creds(File dir) {
        Creds c = new Creds();
        JSONObject s = readJson(new File(dir, SYNC_FILE), "{}");
        String repo = s.optString("repo", "");
        String token = s.optString("token", "");
        if (!repo.isEmpty() && !token.isEmpty()) {
            c.provider = s.optString("provider", "github");
            c.repo = repo;
            c.token = token;
            return c;
        }
        // 开源版不内置仓库：DEFAULT_INVITE 为空时返回空 Creds，由用户自行配置
        if (DEFAULT_INVITE == null || DEFAULT_INVITE.isEmpty()) {
            return c;
        }
        try {
            String raw = DEFAULT_INVITE.substring("LUVSIC1.".length())
                    .replace('-', '+').replace('_', '/');
            while (raw.length() % 4 != 0) raw += "=";
            JSONObject d = new JSONObject(new String(Base64.decode(raw, Base64.DEFAULT), UTF8));
            c.provider = d.optString("p", "github");
            c.repo = d.optString("r", "");
            c.token = d.optString("t", "");
        } catch (Exception e) {
            Log.w(TAG, "creds", e);
        }
        return c;
    }

    // ── HTTP ──
    private static String base(Creds c) {
        return "https://api.github.com";
    }

    private static HttpURLConnection open(Creds c, String method, String path) throws Exception {
        String p = path.replace(" ", "%20");
        StringBuilder sb = new StringBuilder(base(c));
        sb.append("/repos/").append(c.repo).append("/contents/");
        for (String seg : p.split("/")) sb.append(URLEncoder.encode(seg, "UTF-8")).append("/");
        String url = sb.substring(0, sb.length() - 1);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "Luvsic-Android");
        con.setRequestProperty("Authorization", "Bearer " + c.token);
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
        }
        return con;
    }

    private static JSONObject body(HttpURLConnection con) {
        try {
            InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream();
            if (in == null) return new JSONObject();
            String s = new String(readAll(in), UTF8);
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject getJson(Creds c, String path) throws Exception {
        HttpURLConnection con = open(c, "GET", path);
        int code = con.getResponseCode();
        if (code == 404) return null;
        if (code != 200) throw new Exception("HTTP " + code);
        return body(con);
    }

    static JSONArray getArray(Creds c, String path) throws Exception {
        HttpURLConnection con = open(c, "GET", path);
        int code = con.getResponseCode();
        if (code == 404) return null;
        if (code != 200) throw new Exception("HTTP " + code);
        String s = new String(readAll(con.getInputStream()), UTF8);
        return new JSONArray(s);
    }

    static String[] getFile(Creds c, String path) throws Exception {
        JSONObject o = getJson(c, path);
        if (o == null) return null;
        String b64 = o.optString("content", "");
        if (b64.isEmpty()) return null;
        byte[] b = Base64.decode(b64.replace("\n", ""), Base64.DEFAULT);
        return new String[]{new String(b, UTF8), o.optString("sha", "")};
    }

    static void putFile(Creds c, String path, String content, String sha, String message) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("content", Base64.encodeToString(content.getBytes(UTF8), Base64.NO_WRAP));
        payload.put("message", message);
        if (sha != null && !sha.isEmpty()) payload.put("sha", sha);
        // GitHub Contents API：新建和更新都用 PUT
        String method = "PUT";
        HttpURLConnection con = open(c, method, path);
        OutputStream os = con.getOutputStream();
        os.write(payload.toString().getBytes(UTF8));
        os.close();
        int code = con.getResponseCode();
        if (code != 200 && code != 201) {
            throw new Exception("写入失败 HTTP " + code + " " + body(con).optString("message", ""));
        }
    }

    // ── 本地路径 ──
    private static String uid(File dir) {
        JSONObject u = readJson(new File(dir, USER_FILE), "{}");
        String id = u.optString("id", "");
        if (!id.matches("\\d{8}")) {
            // 自愈：user.json 的 id 意外丢失时，从 sync.json 里冗余的 userId 恢复
            JSONObject s = readJson(new File(dir, SYNC_FILE), "{}");
            String sid = s.optString("userId", "");
            if (sid.matches("\\d{8}")) {
                try {
                    u.put("id", sid);
                    writeJson(new File(dir, USER_FILE), u);
                } catch (Exception ignored) {
                }
                id = sid;
            }
        }
        return id;
    }

    private static void setUid(File dir, String id) {
        JSONObject u = readJson(new File(dir, USER_FILE), "{}");
        try {
            u.put("id", id);
        } catch (Exception ignored) {
        }
        writeJson(new File(dir, USER_FILE), u);
    }

    private static long localStamp(File dir) {
        long t = 0;
        for (String n : new String[]{"library.json", "playlists.json"}) {
            File f = new File(dir, n);
            if (f.isFile()) t = Math.max(t, f.lastModified() / 1000L);
        }
        return t;
    }

    // ── 删除墓碑：同步时广播删除，避免删掉的歌被合并回来 ──
    private static JSONArray loadTombstones(File dir) {
        JSONObject d = readJson(new File(dir, TOMB_FILE), "{\"tombstones\":[]}");
        JSONArray a = d.optJSONArray("tombstones");
        return a == null ? new JSONArray() : a;
    }

    private static void saveTombstones(File dir, JSONArray tombs) {
        JSONObject o = new JSONObject();
        try {
            o.put("tombstones", tombs == null ? new JSONArray() : tombs);
        } catch (Exception ignored) {
        }
        writeJson(new File(dir, TOMB_FILE), o);
    }

    static String normText(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\-_·・,，.。;；:：'\"`~!！?？()（）\\[\\]【】{}<>《》/\\\\|+&]", "");
    }

    /** 曲目「身份证」：歌名归一化|歌手归一化|时长取整（秒），与电脑端规则一致 */
    static String stableKey(JSONObject t) {
        if (t == null) return "";
        String title = normText(t.optString("title", ""));
        if (title.isEmpty()) return "";
        String artist = normText(t.optString("artist", ""));
        long dur = Math.round(t.optDouble("duration", 0));
        return title + "|" + artist + "|" + dur;
    }

    /** 取 basename 并去掉扩展名（用于文件名匹配） */
    static String stripExt(String pathOrName) {
        if (pathOrName == null) return "";
        String s = pathOrName;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s;
    }

    /** 标题+歌手 key（与电脑端 _track_key 一致） */
    static String trackKey(JSONObject t) {
        if (t == null) return "";
        String a = normText(t.optString("title", ""));
        if (a.isEmpty()) return "";
        String b = normText(t.optString("artist", ""));
        return a + "|" + b;
    }

    static void addTombstones(File dir, JSONArray tracks) {
        JSONArray tombs = loadTombstones(dir);
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (int i = 0; i < tombs.length(); i++) {
            JSONObject x = tombs.optJSONObject(i);
            if (x != null) have.add(x.optString("key", ""));
        }
        long now = System.currentTimeMillis() / 1000L;
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            String k = stableKey(t);
            if (k.isEmpty() || have.contains(k)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("key", k);
                o.put("deletedAt", now);
                o.put("title", t.optString("title", ""));
                tombs.put(o);
                have.add(k);
            } catch (Exception ignored) {
            }
        }
        saveTombstones(dir, tombs);
    }

    static void clearTombstones(File dir, JSONArray tracks) {
        JSONArray tombs = loadTombstones(dir);
        if (tombs.length() == 0) return;
        java.util.Set<String> drop = new java.util.HashSet<String>();
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t != null) drop.add(stableKey(t));
        }
        JSONArray keep = new JSONArray();
        for (int i = 0; i < tombs.length(); i++) {
            JSONObject x = tombs.optJSONObject(i);
            if (x != null && !drop.contains(x.optString("key", ""))) keep.put(x);
        }
        saveTombstones(dir, keep);
    }

    private static JSONArray mergeTombstones(JSONArray local, JSONArray remote) {
        java.util.LinkedHashMap<String, JSONObject> out = new java.util.LinkedHashMap<String, JSONObject>();
        JSONArray[] arrs = new JSONArray[]{local, remote};
        for (JSONArray arr : arrs) {
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                String k = x.optString("key", "");
                if (k.isEmpty()) continue;
                long ts = x.optLong("deletedAt", 0);
                JSONObject old = out.get(k);
                if (old == null || ts > old.optLong("deletedAt", 0)) out.put(k, x);
            }
        }
        JSONArray res = new JSONArray();
        for (JSONObject v : out.values()) res.put(v);
        return res;
    }

    private static JSONArray filterTombstoned(JSONArray tracks, JSONArray tombs) {
        if (tracks == null) return new JSONArray();
        if (tombs == null || tombs.length() == 0) return tracks;
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (int i = 0; i < tombs.length(); i++) {
            JSONObject x = tombs.optJSONObject(i);
            if (x != null) keys.add(x.optString("key", ""));
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            if (!keys.contains(stableKey(t))) out.put(t);
        }
        return out;
    }

    private static JSONObject payload(File dir) {
        JSONObject lib = readJson(new File(dir, "library.json"), "{\"folder\":\"\",\"tracks\":[]}");
        JSONObject pl = readJson(new File(dir, "playlists.json"), "{\"playlists\":[]}");
        JSONObject out = new JSONObject();
        try {
            out.put("v", 1);
            out.put("updatedAt", System.currentTimeMillis() / 1000L);
            JSONObject l = new JSONObject();
            l.put("folder", lib.optString("folder", ""));
            l.put("tracks", lib.optJSONArray("tracks") == null ? new JSONArray() : lib.optJSONArray("tracks"));
            out.put("library", l);
            out.put("playlists", pl.optJSONArray("playlists") == null ? new JSONArray() : pl.optJSONArray("playlists"));
            out.put("tombstones", loadTombstones(dir));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static java.util.Set<String> loadSnapshot(File dir) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            JSONObject d = readJson(new File(dir, "sync_snapshot.json"), "{}");
            JSONArray a = d.optJSONArray("paths");
            for (int i = 0; a != null && i < a.length(); i++) {
                String p = a.optString(i, "");
                if (!p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveSnapshot(File dir) {
        try {
            JSONObject lib = readJson(new File(dir, "library.json"), "{\"tracks\":[]}");
            JSONArray tracks = lib.optJSONArray("tracks");
            java.util.TreeSet<String> paths = new java.util.TreeSet<String>();
            for (int i = 0; tracks != null && i < tracks.length(); i++) {
                JSONObject t = tracks.optJSONObject(i);
                if (t != null) {
                    String p = t.optString("path", "");
                    if (!p.isEmpty()) paths.add(p);
                }
            }
            JSONArray a = new JSONArray();
            for (String p : paths) a.put(p);
            JSONObject o = new JSONObject();
            o.put("paths", a);
            o.put("at", System.currentTimeMillis() / 1000L);
            writeJson(new File(dir, "sync_snapshot.json"), o);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject applyPayload(File dir, JSONObject data) {
        try {
            JSONArray tombs = mergeTombstones(loadTombstones(dir), data.optJSONArray("tombstones"));
            saveTombstones(dir, tombs);
            JSONObject lib = data.optJSONObject("library");
            JSONObject local = readJson(new File(dir, "library.json"), "{\"folder\":\"\",\"tracks\":[]}");
            JSONArray localRaw = local.optJSONArray("tracks");
            java.util.Set<String> beforePaths = new java.util.HashSet<String>();
            for (int i = 0; localRaw != null && i < localRaw.length(); i++) {
                JSONObject tt = localRaw.optJSONObject(i);
                if (tt != null && !tt.optString("path", "").isEmpty()) beforePaths.add(tt.optString("path", ""));
            }
            JSONArray localTracks = filterTombstoned(localRaw, tombs);
            JSONArray remTracks = lib == null ? null : filterTombstoned(lib.optJSONArray("tracks"), tombs);

            // 本机索引：文件名（去扩展名归一化）-> 曲目；标题+歌手 -> 曲目
            java.util.HashMap<String, JSONObject> byName = new java.util.HashMap<String, JSONObject>();
            java.util.HashMap<String, JSONObject> byKey = new java.util.HashMap<String, JSONObject>();
            if (localTracks != null) {
                for (int i = 0; i < localTracks.length(); i++) {
                    JSONObject t = localTracks.optJSONObject(i);
                    if (t == null) continue;
                    String p = t.optString("path", "");
                    String fn = p.isEmpty() ? t.optString("name", "") : p;
                    String nm = normText(stripExt(fn));
                    if (!nm.isEmpty() && !byName.containsKey(nm)) byName.put(nm, t);
                    String k = trackKey(t);
                    if (!k.isEmpty() && !byKey.containsKey(k)) byKey.put(k, t);
                }
            }

            // 合并：本机曲目全保留
            JSONArray merged = new JSONArray();
            java.util.HashMap<String, JSONObject> mergedByPath = new java.util.HashMap<String, JSONObject>();
            if (localTracks != null) {
                for (int i = 0; i < localTracks.length(); i++) {
                    JSONObject t = localTracks.optJSONObject(i);
                    if (t == null) continue;
                    String p = t.optString("path", "");
                    if (!p.isEmpty()) {
                        if (mergedByPath.containsKey(p)) continue;
                        mergedByPath.put(p, t);
                    }
                    merged.put(t);
                }
            }

            int matched = 0;
            int missing = 0;
            java.util.Set<String> missingKeys = new java.util.HashSet<String>();
            if (remTracks != null) {
                for (int i = 0; i < remTracks.length(); i++) {
                    JSONObject rt = remTracks.optJSONObject(i);
                    if (rt == null) continue;
                    String p = rt.optString("path", "");
                    // 1) 云端 path 在本机真实存在：直接使用
                    if (!p.isEmpty() && new File(p).isFile()) {
                        if (!mergedByPath.containsKey(p)) {
                            mergedByPath.put(p, rt);
                            merged.put(rt);
                        }
                        continue;
                    }
                    // 2) 按文件名匹配本机曲目
                    String fn = rt.optString("name", "");
                    if (fn.isEmpty()) fn = p;
                    String nm = normText(stripExt(fn));
                    JSONObject hit = nm.isEmpty() ? null : byName.get(nm);
                    // 3) 按标题+歌手匹配
                    if (hit == null) hit = byKey.get(trackKey(rt));
                    if (hit != null) {
                        double a = rt.optDouble("duration", 0);
                        double b = hit.optDouble("duration", 0);
                        if (a > 0 && b > 0 && Math.abs(a - b) > 3) hit = null;
                    }
                    if (hit != null) {
                        String hp = hit.optString("path", "");
                        JSONObject exist = hp.isEmpty() ? null : mergedByPath.get(hp);
                        if (exist != null) {
                            // 本机已有这首歌：用云端干净的标题/歌手覆盖，去掉「未下载」
                            if (!rt.optString("title", "").isEmpty()) exist.put("title", rt.optString("title", ""));
                            if (!rt.optString("artist", "").isEmpty()) exist.put("artist", rt.optString("artist", ""));
                            if (!rt.optString("album", "").isEmpty()) exist.put("album", rt.optString("album", ""));
                            if (rt.optBoolean("hasCover", false)) exist.put("hasCover", true);
                            exist.remove("offline");
                            matched++;
                        } else {
                            JSONObject nt = new JSONObject(hit.toString());
                            if (!rt.optString("title", "").isEmpty()) nt.put("title", rt.optString("title", ""));
                            if (!rt.optString("artist", "").isEmpty()) nt.put("artist", rt.optString("artist", ""));
                            nt.remove("offline");
                            if (!hp.isEmpty() && !mergedByPath.containsKey(hp)) mergedByPath.put(hp, nt);
                            merged.put(nt);
                            matched++;
                        }
                    } else {
                        // 4) 匹配不到：标「未下载」，不塞坏路径
                        String key = trackKey(rt);
                        if (key.isEmpty()) key = "path:" + p;
                        if (missingKeys.contains(key)) continue;
                        missingKeys.add(key);
                        JSONObject u = new JSONObject(rt.toString());
                        u.put("offline", true);
                        merged.put(u);
                        missing++;
                    }
                }
            }

            JSONObject outLib = new JSONObject();
            outLib.put("folder", lib == null ? (local.optString("folder", "")) : lib.optString("folder", ""));
            outLib.put("tracks", merged);
            writeJson(new File(dir, "library.json"), outLib);
            Log.i(TAG, "applyPayload merged=" + merged.length()
                    + " matched=" + matched + " offline=" + missing);

            // 歌单同样合并：云端歌单为准，但本机独有的歌单保留
            JSONArray localPl = readJson(new File(dir, "playlists.json"), "{\"playlists\":[]}").optJSONArray("playlists");
            JSONArray remPl = data.optJSONArray("playlists");
            JSONArray outPlaylists = new JSONArray();
            java.util.Set<String> names = new java.util.HashSet<String>();
            if (remPl != null) {
                for (int i = 0; i < remPl.length(); i++) {
                    JSONObject p = remPl.optJSONObject(i);
                    if (p == null) continue;
                    names.add(p.optString("name", ""));
                    outPlaylists.put(p);
                }
            }
            if (localPl != null) {
                for (int i = 0; i < localPl.length(); i++) {
                    JSONObject p = localPl.optJSONObject(i);
                    if (p == null) continue;
                    if (names.contains(p.optString("name", ""))) continue;
                    outPlaylists.put(p);
                }
            }
            JSONObject outPl = new JSONObject();
            outPl.put("playlists", outPlaylists);
            writeJson(new File(dir, "playlists.json"), outPl);

            java.util.Set<String> afterPaths = new java.util.HashSet<String>();
            for (int i = 0; i < merged.length(); i++) {
                JSONObject mt = merged.optJSONObject(i);
                if (mt != null && !mt.optString("path", "").isEmpty()) afterPaths.add(mt.optString("path", ""));
            }
            int added = 0, removed = 0;
            for (String pp : afterPaths) if (!beforePaths.contains(pp)) added++;
            for (String pp : beforePaths) if (!afterPaths.contains(pp)) removed++;
            JSONObject stat = new JSONObject();
            stat.put("total", merged.length());
            stat.put("added", added);
            stat.put("removed", removed);
            stat.put("matched", matched);
            stat.put("offline", missing);
            return stat;
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    private static void pushSocial(Creds c, File dir) {
        try {
            JSONObject u = readJson(new File(dir, USER_FILE), "{}");
            String name = u.optString("name", "");
            if (name.isEmpty()) {
                try {
                    String[] oldP = getFile(c, "users/" + u.optString("id") + "/profile.json");
                    if (oldP != null) name = new JSONObject(oldP[0]).optString("name", "");
                } catch (Exception ignored) {
                }
            }
            JSONObject p = new JSONObject();
            p.put("id", u.optString("id", ""));
            p.put("name", name);
            p.put("avatar", u.optString("avatar", ""));
            p.put("updatedAt", System.currentTimeMillis() / 1000L);
            String[] old = getFile(c, "users/" + u.optString("id") + "/profile.json");
            putFile(c, "users/" + u.optString("id") + "/profile.json", p.toString(),
                    old == null ? null : old[1], "更新资料");
            mergeRemoteStats(c, dir, u.optString("id", ""));
        } catch (Exception e) {
            Log.w(TAG, "pushSocial", e);
        }
    }

    /** 每次打开 App 更新账号表里的最近登录时间/设备（管理员界面直接可见） */
    public static JSONObject touchLogin(File dir) {
        JSONObject out = new JSONObject();
        try {
            String sid = uid(dir);
            if (sid.isEmpty()) {
                out.put("ok", false);
                out.put("msg", "未登录");
                return out;
            }
            Creds c = creds(dir);
            if (c == null) {
                out.put("ok", false);
                out.put("msg", "未登录");
                return out;
            }
            String dev = deviceName();
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) {
                out.put("ok", false);
                out.put("msg", "账号表不存在");
                return out;
            }
            JSONObject wh = new JSONObject(acc[0]);
            JSONArray list = wh.optJSONArray("accounts");
            boolean found = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a == null) continue;
                if (sid.equals(a.optString("id", ""))) {
                    a.put("lastLoginAt", System.currentTimeMillis() / 1000L);
                    a.put("lastDevice", dev);
                    a.put("lastPlatform", "android");
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.put("ok", false);
                out.put("msg", "账号不在白名单");
                return out;
            }
            wh.put("updatedAt", System.currentTimeMillis() / 1000L);
            putFile(c, "accounts.json", wh.toString(), acc[1], "更新最近登录时间");
            out.put("ok", true);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "更新失败");
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 按设备累加听歌时长并写回云端（电脑端同构）。
     *  云端结构：{"devices": {"android:<设备ID>": 秒, "desktop:<设备ID>": 秒}, "total": 秒之和}
     *  同一设备反复上报只取自己的最大值；不同设备相加。
     *  返回合并后的 total；读云端失败/数据损坏返回 -1（调用方跳过本次上报，避免误覆盖）。 */
    /** 好友榜「正在听」：当前播放的歌曲（只存歌名，不存歌手） */
    private static JSONObject sNowPlaying = null;
    private static long sNowPushedAt = 0;

    private static String fmtDate(java.util.Calendar cal) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
    }

    public static JSONObject setNow(File dir, String title, boolean playing) {
        JSONObject out = new JSONObject();
        try {
            JSONObject now = new JSONObject();
            String t = title == null ? "" : title;
            if (t.length() > 80) t = t.substring(0, 80);
            now.put("title", t);
            now.put("playing", playing);
            now.put("updatedAt", System.currentTimeMillis() / 1000L);
            sNowPlaying = now;
            final File d = dir;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        pushNow(d);
                    } catch (Exception ignored) {
                    }
                }
            }).start();
            out.put("ok", true);
        } catch (Exception e) {
            try {
                out.put("ok", false);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void pushNow(File dir) {
        try {
            JSONObject now = sNowPlaying;
            if (now == null || now.optString("title", "").isEmpty()) return;
            long ts = now.optLong("updatedAt", 0);
            if (ts - sNowPushedAt < 10) return;
            Creds c = creds(dir);
            String sid = uid(dir);
            if (c == null || sid.isEmpty()) return;
            String path = "users/" + sid + "/stats.json";
            String[] old = getFile(c, path);
            JSONObject data;
            if (old == null) {
                data = new JSONObject();
            } else {
                try {
                    data = new JSONObject(old[0]);
                } catch (Exception e) {
                    return;
                }
            }
            data.put("now", now);
            data.put("id", sid);
            data.put("updatedAt", System.currentTimeMillis() / 1000L);
            putFile(c, path, data.toString(), old == null ? null : old[1], "更新正在听");
            sNowPushedAt = ts;
        } catch (Exception ignored) {
        }
    }

    private static int mergeRemoteStats(Creds c, File dir, String sid) {
        try {
            JSONObject st = readJson(new File(dir, STATS_FILE), "{\"days\":{},\"total\":0}");
            int localTotal = (int) st.optDouble("total", 0);
            String key = "android:" + deviceId(dir);
            String[] oldS = getFile(c, "users/" + sid + "/stats.json");
            JSONObject devices = new JSONObject();
            String sha = null;
            if (oldS != null) {
                sha = oldS[1];
                try {
                    JSONObject old = new JSONObject(oldS[0]);
                    JSONObject dv = old.optJSONObject("devices");
                    if (dv != null) devices = dv;
                } catch (Exception e) {
                    return -1;
                }
            }
            int prev = devices.optInt(key, 0);
            devices.put(key, Math.max(prev, localTotal));
            int total = 0;
            java.util.Iterator<String> it = devices.keys();
            while (it.hasNext()) total += devices.optInt(it.next(), 0);
            JSONObject s = new JSONObject();
            s.put("id", sid);
            s.put("name", "");
            s.put("devices", devices);
            s.put("total", total);
            // 周榜/月榜：最近 30 天每日时长（同一天取大值）
            JSONObject localDays = st.optJSONObject("days");
            JSONObject daysOut = new JSONObject();
            if (localDays != null) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_YEAR, -30);
                String cutoff = fmtDate(cal);
                java.util.Iterator<String> itd = localDays.keys();
                while (itd.hasNext()) {
                    String dd = itd.next();
                    if (dd.compareTo(cutoff) < 0) continue;
                    daysOut.put(dd, (int) localDays.optDouble(dd, 0));
                }
            }
            s.put("days", daysOut);
            if (sNowPlaying != null && !sNowPlaying.optString("title", "").isEmpty()) {
                s.put("now", sNowPlaying);
            }
            s.put("updatedAt", System.currentTimeMillis() / 1000L);
            putFile(c, "users/" + sid + "/stats.json", s.toString(), sha, "更新听歌时长");
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 把本机累计听歌时长推到 Hub（播放中定时调用，排行榜就能及时更新） */
    public static JSONObject statsFlush(File dir) {
        JSONObject out = new JSONObject();
        try {
            String sid = uid(dir);
            if (sid.isEmpty()) {
                out.put("ok", false);
                out.put("msg", "未登录");
                return out;
            }
            Creds c = creds(dir);
            if (c == null) {
                out.put("ok", false);
                out.put("msg", "未登录");
                return out;
            }
            int total = mergeRemoteStats(c, dir, sid);
            if (total < 0) {
                out.put("ok", false);
                out.put("msg", "上报失败");
                return out;
            }
            out.put("ok", true);
            out.put("total", total);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "上报失败");
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---- 设备识别（新设备登录需要管理员批准）----
    public static String deviceId(File dir) {
        try {
            File f = new File(dir, "device.json");
            JSONObject d = readJson(f, "{}");
            String id = d.optString("id", "");
            if (!id.matches("[A-Za-z0-9_-]{8,40}")) {
                id = "d" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                d.put("id", id);
                d.put("name", deviceName());
                writeJson(f, d);
            }
            return id;
        } catch (Exception e) {
            return "dunknown";
        }
    }

    public static String deviceName() {
        String m = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String mo = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
        String name = (m + " " + mo).trim();
        return name.isEmpty() ? "Android" : name;
    }

    public static JSONObject deviceInfo(File dir) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("deviceId", deviceId(dir));
            out.put("device", deviceName());
            out.put("platform", "android");
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject adminPendingDevices(File dir) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            out.put("ok", true);
            out.put("list", new JSONArray());
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以查看");
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            JSONArray rows = new JSONArray();
            if (acc != null) {
                JSONArray listA = new JSONObject(acc[0]).optJSONArray("accounts");
                for (int i = 0; listA != null && i < listA.length(); i++) {
                    JSONObject a = listA.optJSONObject(i);
                    if (a == null) continue;
                    JSONArray pend = a.optJSONArray("pendingDevices");
                    for (int j = 0; pend != null && j < pend.length(); j++) {
                        JSONObject d = pend.optJSONObject(j);
                        if (d == null) continue;
                        JSONObject row = new JSONObject();
                        row.put("account", a.optString("id", ""));
                        row.put("accountName", a.optString("name", ""));
                        row.put("deviceId", d.optString("id", ""));
                        row.put("device", d.optString("name", ""));
                        row.put("at", d.optLong("at", 0));
                        rows.put(row);
                    }
                }
            }
            out.put("list", rows);
            out.put("total", rows.length());
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "读取失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject adminApproveDevice(File dir, String account, String deviceId) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以操作");
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) {
                out.put("ok", false);
                out.put("msg", "读取白名单失败");
                return out;
            }
            JSONObject data = new JSONObject(acc[0]);
            JSONArray listA = data.optJSONArray("accounts");
            for (int i = 0; listA != null && i < listA.length(); i++) {
                JSONObject a = listA.optJSONObject(i);
                if (a == null || !account.equals(a.optString("id", ""))) continue;
                JSONArray pend = a.optJSONArray("pendingDevices");
                JSONArray keep = new JSONArray();
                String devName = "";
                for (int j = 0; pend != null && j < pend.length(); j++) {
                    JSONObject d = pend.optJSONObject(j);
                    if (d == null) continue;
                    if (deviceId.equals(d.optString("id", ""))) {
                        devName = d.optString("name", "");
                        continue;
                    }
                    keep.put(d);
                }
                a.put("pendingDevices", keep);
                JSONArray devices = a.optJSONArray("devices");
                if (devices == null) devices = new JSONArray();
                boolean has = false;
                for (int j = 0; j < devices.length(); j++) {
                    JSONObject d = devices.optJSONObject(j);
                    if (d != null && deviceId.equals(d.optString("id", ""))) { has = true; break; }
                }
                if (!has) {
                    JSONObject nd = new JSONObject();
                    nd.put("id", deviceId);
                    nd.put("name", devName);
                    nd.put("at", System.currentTimeMillis() / 1000L);
                    nd.put("approved", true);
                    devices.put(nd);
                }
                a.put("devices", devices);
                break;
            }
            data.put("updatedAt", System.currentTimeMillis() / 1000L);
            putFile(c, "accounts.json", data.toString(), acc[1], "批准新设备登录");
            out.put("ok", true);
            out.put("msg", "已允许这个设备登录");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "保存失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject adminDenyDevice(File dir, String account, String deviceId) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以操作");
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) {
                out.put("ok", false);
                out.put("msg", "读取白名单失败");
                return out;
            }
            JSONObject data = new JSONObject(acc[0]);
            JSONArray listA = data.optJSONArray("accounts");
            for (int i = 0; listA != null && i < listA.length(); i++) {
                JSONObject a = listA.optJSONObject(i);
                if (a == null || !account.equals(a.optString("id", ""))) continue;
                JSONArray pend = a.optJSONArray("pendingDevices");
                JSONArray keep = new JSONArray();
                for (int j = 0; pend != null && j < pend.length(); j++) {
                    JSONObject d = pend.optJSONObject(j);
                    if (d == null) continue;
                    if (deviceId.equals(d.optString("id", ""))) continue;
                    keep.put(d);
                }
                a.put("pendingDevices", keep);
                break;
            }
            data.put("updatedAt", System.currentTimeMillis() / 1000L);
            putFile(c, "accounts.json", data.toString(), acc[1], "拒绝新设备登录");
            out.put("ok", true);
            out.put("msg", "已拒绝这个设备");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "保存失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ── 对外 API ──
    public static JSONObject loginWithId(File dir, String accountId, String device, String password) {
        JSONObject out = new JSONObject();
        try {
            String aid = accountId == null ? "" : accountId.replaceAll("[^0-9]", "");
            if (aid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "请输入 8 位数字账号ID");
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) throw new Exception("服务器上没有账号白名单");
            JSONObject wh = new JSONObject(acc[0]);
            JSONArray list = wh.optJSONArray("accounts");
            if (list == null) {
                list = new JSONArray();
                wh.put("accounts", list);
            }
            boolean okId = false;
            String whName = "";
            JSONObject hitAcc0 = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a != null && aid.equals(a.optString("id"))) {
                    hitAcc0 = a;
                    whName = a.optString("name", "");
                    break;
                }
            }
            boolean isAdminAid = isAdminId(aid);
            boolean newPending = false;
            if (hitAcc0 == null) {
                // 新账号第一次登录：自动生成申请记录（管理员账号直接授权，避免锁死自己）
                JSONObject na = new JSONObject();
                na.put("id", aid);
                na.put("name", "");
                na.put("createdAt", System.currentTimeMillis() / 1000L);
                na.put("enabled", isAdminAid);
                na.put("admin", isAdminAid);
                na.put("devices", new JSONArray());
                na.put("note", "");
                if (!isAdminAid) na.put("pending", true);
                list.put(na);
                wh.put("updatedAt", System.currentTimeMillis() / 1000L);
                hitAcc0 = na;
                newPending = !isAdminAid;
            }
            if (!hitAcc0.optBoolean("enabled", true)) {
                if (newPending) {
                    try {
                        putFile(c, "accounts.json", wh.toString(), acc[1], "新账号登录申请");
                    } catch (Exception ignored) {
                    }
                }
                out.put("ok", false);
                out.put("needApprove", true);
                out.put("pendingAccount", true);
                out.put("msg", "新账号已提交登录申请，等待管理员批准");
                return out;
            }
            okId = true;
            // ---- 新设备登录检查（管理员账号跳过；首次登录自动授权本机）----
            String devId = deviceId(dir);
            String devName = deviceName();
            JSONObject hitAcc = null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a != null && aid.equals(a.optString("id", ""))) { hitAcc = a; break; }
            }
            JSONArray devices = hitAcc == null ? null : hitAcc.optJSONArray("devices");
            boolean approved = false;
            for (int i = 0; devices != null && i < devices.length(); i++) {
                JSONObject d = devices.optJSONObject(i);
                if (d != null && devId.equals(d.optString("id", ""))) { approved = true; break; }
            }
            if (!approved && hitAcc != null) {
                if (isAdminId(aid)) {
                    // 管理员账号在新设备登录：必须输入管理员密码（防别人知道账号ID直接登录）
                    String pwdHash = hitAcc.optString("loginPasswordHash", "");
                    String got = sha256Hex("luvsic:" + (password == null ? "" : password));
                    if (pwdHash.isEmpty() || !pwdHash.equals(got)) {
                        out.put("ok", false);
                        out.put("needPassword", true);
                        out.put("msg", (password == null || password.isEmpty())
                                ? "管理员账号在新设备登录需要密码" : "管理员密码错误");
                        return out;
                    }
                    JSONArray nd = devices == null ? new JSONArray() : devices;
                    JSONObject d = new JSONObject();
                    d.put("id", devId);
                    d.put("name", devName);
                    d.put("at", System.currentTimeMillis() / 1000L);
                    d.put("approved", true);
                    nd.put(d);
                    hitAcc.put("devices", nd);
                    try {
                        wh.put("updatedAt", System.currentTimeMillis() / 1000L);
                        putFile(c, "accounts.json", wh.toString(), acc[1], "管理员新设备登录（密码验证）");
                    } catch (Exception ignored) {
                    }
                    approved = true;
                } else if (devices == null || devices.length() == 0) {
                    JSONArray nd = new JSONArray();
                    JSONObject d = new JSONObject();
                    d.put("id", devId);
                    d.put("name", devName);
                    d.put("at", System.currentTimeMillis() / 1000L);
                    d.put("approved", true);
                    nd.put(d);
                    hitAcc.put("devices", nd);
                    try {
                        wh.put("updatedAt", System.currentTimeMillis() / 1000L);
                        putFile(c, "accounts.json", wh.toString(), acc[1], "首次登录授权本机");
                    } catch (Exception ignored) {
                    }
                } else {
                    JSONArray pend = hitAcc.optJSONArray("pendingDevices");
                    if (pend == null) pend = new JSONArray();
                    boolean has = false;
                    for (int i = 0; i < pend.length(); i++) {
                        JSONObject d = pend.optJSONObject(i);
                        if (d != null && devId.equals(d.optString("id", ""))) { has = true; break; }
                    }
                    if (!has) {
                        JSONObject d = new JSONObject();
                        d.put("id", devId);
                        d.put("name", devName);
                        d.put("at", System.currentTimeMillis() / 1000L);
                        pend.put(d);
                        hitAcc.put("pendingDevices", pend);
                        try {
                            wh.put("updatedAt", System.currentTimeMillis() / 1000L);
                            putFile(c, "accounts.json", wh.toString(), acc[1], "新设备登录申请");
                        } catch (Exception ignored) {
                        }
                    }
                    out.put("ok", false);
                    out.put("needApprove", true);
                    out.put("device", devName);
                    out.put("msg", "这是新设备，登录需要管理员批准。请把设备名「" + devName + "」发给管理员");
                    return out;
                }
            }
            String oldId = uid(dir);
            setUid(dir, aid);
            // 换账号不继承旧昵称；默认昵称 = 账号ID（白名单或云端 profile 有名字才用名字）
            JSONObject u = readJson(new File(dir, USER_FILE), "{}");
            // 本机昵称缓存：换号再换回也能恢复用户设置过的昵称
            JSONObject nicks = readJson(new File(dir, "nicknames.json"), "{}");
            if (!oldId.equals(aid)) {
                u.put("name", nicks.optString(aid, ""));
            }
            String cur = u.optString("name", "").trim();
            boolean needName = cur.isEmpty() || (cur.matches("\\d{8}") && !cur.equals(aid));
            if (needName) {
                String cached = nicks.optString(aid, "").trim();
                if (!cached.isEmpty()) {
                    u.put("name", cached);
                } else if (!whName.trim().isEmpty()) {
                    u.put("name", whName.trim());
                } else {
                    String remoteName = "";
                    try {
                        String[] pr = getFile(c, "users/" + aid + "/profile.json");
                        if (pr != null) {
                            remoteName = new JSONObject(pr[0]).optString("name", "").trim();
                        }
                    } catch (Exception ignored) {
                    }
                    if (!remoteName.isEmpty()) {
                        u.put("name", remoteName);
                    } else {
                        u.put("name", aid);
                    }
                }
            }
            writeJson(new File(dir, USER_FILE), u);
            try {
                nicks.put(aid, u.optString("name", ""));
                writeJson(new File(dir, "nicknames.json"), nicks);
            } catch (Exception ignored) {
            }
            // 记录最近登录（供管理员查看）
            try {
                JSONObject log = new JSONObject();
                log.put("id", aid);
                log.put("name", "");
                log.put("platform", "android");
                log.put("device", device == null ? "Android" : device);
                log.put("lastLoginAt", System.currentTimeMillis() / 1000L);
                String lp = "users/" + aid + "/login.json";
                String[] oldL = getFile(c, lp);
                putFile(c, lp, log.toString(), oldL == null ? null : oldL[1], "记录登录");
                String[] ac2 = getFile(c, "accounts.json");
                if (ac2 != null) {
                    JSONObject wh2 = new JSONObject(ac2[0]);
                    JSONArray ls2 = wh2.optJSONArray("accounts");
                    for (int i2 = 0; ls2 != null && i2 < ls2.length(); i2++) {
                        JSONObject a2 = ls2.optJSONObject(i2);
                        if (a2 != null && aid.equals(a2.optString("id", ""))) {
                            a2.put("lastLoginAt", System.currentTimeMillis() / 1000L);
                            a2.put("lastDevice", device == null ? "Android" : device);
                            a2.put("lastPlatform", "android");
                            break;
                        }
                    }
                    putFile(c, "accounts.json", wh2.toString(), ac2[1], "记录登录设备");
                }
            } catch (Exception ignored) {
            }
            JSONObject conf = new JSONObject();
            conf.put("provider", c.provider);
            conf.put("repo", c.repo);
            conf.put("token", c.token);
            conf.put("name", "Luvsic Hub");
            conf.put("at", System.currentTimeMillis() / 1000L);
            conf.put("baseRemoteTs", 0);
            conf.put("baseLocalTs", 0);
            conf.put("userId", aid);
            writeJson(new File(dir, SYNC_FILE), conf);
            syncNow(dir);
            out.put("ok", true);
            out.put("msg", "登录成功，账号ID：" + aid);
            out.put("accountId", aid);
            out.put("profile", profileGet(dir));
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "登录失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject logout(File dir) {
        JSONObject out = new JSONObject();
        try {
            File f = new File(dir, SYNC_FILE);
            if (f.isFile()) f.delete();
            // 保留曲库/歌单/昵称等本地数据，只清登录态：下次打开会重新弹登录门禁
            out.put("ok", true);
            out.put("msg", "已退出登录（本地曲库保留）");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "退出失败：" + e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject status(File dir) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            JSONObject s = readJson(new File(dir, SYNC_FILE), "{}");
            boolean logged = s.optString("repo", "").length() > 0;
            out.put("ok", true);
            out.put("loggedIn", logged);
            out.put("needsLogin", !logged);
            out.put("provider", c.provider);
            out.put("repo", c.repo);
            out.put("name", s.optString("name", ""));
            String uid = uid(dir);
            out.put("userId", uid);
            out.put("isAdmin", isAdminId(uid));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 管理员：账号管理列表（白名单 + 各账号登录记录） */
    public static JSONObject accounts(File dir) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以查看");
                out.put("list", new JSONArray());
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            JSONArray rows = new JSONArray();
            if (acc != null) {
                JSONObject wh = new JSONObject(acc[0]);
                JSONArray list = wh.optJSONArray("accounts");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject a = list.optJSONObject(i);
                    if (a == null) continue;
                    String aid = a.optString("id", "");
                    if (!aid.matches("\\d{8}")) continue;
                    JSONObject row = new JSONObject();
                    row.put("id", aid);
                    row.put("name", a.optString("name", ""));
                    row.put("enabled", a.optBoolean("enabled", true));
                    row.put("note", a.optString("note", ""));
                    row.put("admin", a.optBoolean("admin", false) || isAdminId(aid));
                    row.put("pending", a.optBoolean("pending", false));
                    row.put("platform", a.optString("lastPlatform", ""));
                    String dv = a.optString("lastDevice", "");
                    if (dv.isEmpty()) {
                        JSONArray ds = a.optJSONArray("devices");
                        if (ds != null && ds.length() > 0) {
                            JSONObject dl = ds.optJSONObject(ds.length() - 1);
                            if (dl != null) dv = dl.optString("name", "");
                        }
                    }
                    row.put("device", dv);
                    long lastAt = a.optLong("lastLoginAt", 0);
                    if (lastAt <= 0) {
                        // 老版本登录没写 lastLoginAt：用设备登记时间兜底（和电脑端一致）
                        JSONArray ds2 = a.optJSONArray("devices");
                        for (int j = 0; ds2 != null && j < ds2.length(); j++) {
                            JSONObject d2 = ds2.optJSONObject(j);
                            if (d2 != null) lastAt = Math.max(lastAt, d2.optLong("at", 0));
                        }
                    }
                    row.put("lastLoginAt", lastAt);
                    row.put("loginCount", a.optInt("loginCount", 0));
                    rows.put(row);
                }
            }
            // 按最近登录时间倒序
            java.util.List<JSONObject> arr = new java.util.ArrayList<JSONObject>();
            for (int i = 0; i < rows.length(); i++) arr.add(rows.optJSONObject(i));
            Collections.sort(arr, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject x, JSONObject y) {
                    return Long.compare(y.optLong("lastLoginAt", 0), x.optLong("lastLoginAt", 0));
                }
            });
            JSONArray sorted = new JSONArray();
            for (JSONObject o : arr) sorted.put(o);
            out.put("ok", true);
            out.put("list", sorted);
            out.put("total", sorted.length());
            out.put("adminIds", hasAdmin() ? new JSONArray().put(ADMIN_ID) : new JSONArray());
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "读取失败：" + e.getMessage());
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ── 自动更新 ──
    private static boolean verNewer(String a, String b) {
        String[] pa = a == null ? new String[0] : a.split("\\.");
        String[] pb = b == null ? new String[0] : b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = 0, y = 0;
            try {
                x = i < pa.length ? Integer.parseInt(pa[i].replaceAll("[^0-9]", "")) : 0;
            } catch (Exception ignored) {
            }
            try {
                y = i < pb.length ? Integer.parseInt(pb[i].replaceAll("[^0-9]", "")) : 0;
            } catch (Exception ignored) {
            }
            if (x != y) return x > y;
        }
        return false;
    }

    private static String rawUrl(Creds c, String rel) {
        // 私有仓库只有 contents API 可用（raw 直链 403）
        if (c == null || c.repo == null || c.repo.isEmpty() || rel == null) return "";
        rel = rel.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return "";
        // GitHub Contents API：不带 ref 时用仓库默认分支
        return "https://api.github.com/repos/" + c.repo + "/contents/"
                + android.net.Uri.encode(rel);
    }

    /** 检查更新：读 Hub 根目录 updates.json 的 android 段 */
    public static JSONObject checkUpdate(File dir, android.content.Context ctx) {
        JSONObject out = new JSONObject();
        try {
            String cur = "";
            int curCode = 0;
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                cur = pi.versionName == null ? "" : pi.versionName;
                curCode = pi.versionCode;
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("current", cur);
            out.put("currentCode", curCode);
            out.put("hasUpdate", false);
            Creds c = creds(dir);
            String[] raw = getFile(c, "updates.json");
            if (raw == null) {
                out.put("msg", "已是最新版本");
                return out;
            }
            JSONObject info = new JSONObject(raw[0]).optJSONObject("android");
            if (info == null) {
                out.put("msg", "已是最新版本");
                return out;
            }
            String latest = info.optString("version", "").trim();
            int latestCode = info.optInt("versionCode", 0);
            boolean need = (latestCode > 0 && latestCode > curCode) || verNewer(latest, cur);
            if (!need) {
                out.put("msg", "已是最新版本");
                return out;
            }
            out.put("hasUpdate", true);
            out.put("latest", latest);
            out.put("latestCode", latestCode);
            out.put("notes", info.optString("notes", ""));
            out.put("file", info.optString("file", ""));
            out.put("url", rawUrl(c, info.optString("file", "")));
            out.put("msg", "发现新版本 " + latest);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "检查更新失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ── 好友（8 位好友码互加，列表存 users/<id>/friends.json）──
    // ---- 歌单分享：只发歌名/歌手/时长，写入对方 share_inbox.json ----
    public static JSONObject sharePlaylist(File dir, String playlistId, String friendId) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            if (c == null) {
                out.put("ok", false);
                out.put("msg", "还没有登录");
                return out;
            }
            String me = uid(dir);
            final String fid = friendId == null ? "" : friendId.replaceAll("[^0-9]", "");
            if (!fid.matches("\\d{8}")) {
                out.put("ok", false);
                out.put("msg", "好友账号格式不对");
                return out;
            }
            if (fid.equals(me)) {
                out.put("ok", false);
                out.put("msg", "不能分享给自己");
                return out;
            }
            boolean isFriend = false;
            try {
                JSONObject myfd = readRemoteJson(c, friendsPath(dir));
                JSONArray myFriends = myfd == null ? null : myfd.optJSONArray("friends");
                for (int i = 0; myFriends != null && i < myFriends.length(); i++) {
                    Object ro = myFriends.get(i);
                    String id = ro instanceof JSONObject
                            ? ((JSONObject) ro).optString("id", "") : String.valueOf(ro);
                    if (fid.equals(id)) {
                        isFriend = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            if (!isFriend) {
                out.put("ok", false);
                out.put("msg", "对方不是你的好友");
                return out;
            }
            JSONObject pls = readJson(new File(dir, "playlists.json"), "{\"playlists\":[]}");
            JSONArray list = pls.optJSONArray("playlists");
            JSONObject pl = null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p != null && playlistId.equals(p.optString("id", ""))) {
                    pl = p;
                    break;
                }
            }
            if (pl == null) {
                out.put("ok", false);
                out.put("msg", "歌单不存在");
                return out;
            }
            JSONArray items = pl.optJSONArray("items");
            JSONArray tracks = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                Object raw = items.get(i);
                JSONObject o;
                if (raw instanceof JSONObject) {
                    o = (JSONObject) raw;
                } else {
                    String sp = String.valueOf(raw);
                    o = new JSONObject();
                    o.put("path", sp);
                    o.put("name", sp.substring(sp.lastIndexOf('/') + 1));
                }
                String ttl = o.optString("title", o.optString("name", "")).trim();
                if (ttl.isEmpty()) continue;
                JSONObject one = new JSONObject();
                one.put("title", ttl);
                one.put("artist", o.optString("artist", "").trim());
                one.put("duration", (int) o.optDouble("duration", 0));
                tracks.put(one);
                if (tracks.length() >= 300) break;
            }
            if (tracks.length() == 0) {
                out.put("ok", false);
                out.put("msg", "这个歌单是空的");
                return out;
            }
            JSONObject prof = readJson(new File(dir, USER_FILE), "{}");
            String fromName = prof.optString("name", me);
            JSONObject share = new JSONObject();
            share.put("id", "sp" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            share.put("name", pl.optString("name", "歌单"));
            share.put("from", me);
            share.put("fromName", fromName);
            share.put("at", System.currentTimeMillis() / 1000L);
            share.put("tracks", tracks);
            String path = "users/" + fid + "/share_inbox.json";
            String[] old = getFile(c, path);
            JSONObject in = new JSONObject();
            in.put("shares", new JSONArray());
            if (old != null) {
                try {
                    in = new JSONObject(old[0]);
                } catch (Exception ignored) {
                }
            }
            JSONArray shares = in.optJSONArray("shares");
            JSONArray now = new JSONArray();
            long cut = System.currentTimeMillis() / 1000L - 30 * 86400L;
            for (int i = 0; shares != null && i < shares.length(); i++) {
                JSONObject x = shares.optJSONObject(i);
                if (x != null && x.optLong("at", 0) > cut) now.put(x);
            }
            now.put(share);
            in.put("shares", now);
            putFile(c, path, in.toString(), old == null ? null : old[1], "分享歌单");
            out.put("ok", true);
            out.put("msg", "已分享给 " + fromName + "（" + tracks.length() + " 首）");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "分享失败");
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject shareInbox(File dir) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            if (c == null) {
                out.put("ok", false);
                out.put("msg", "还没有登录");
                out.put("list", new JSONArray());
                return out;
            }
            String[] raw = getFile(c, "users/" + uid(dir) + "/share_inbox.json");
            JSONArray list = new JSONArray();
            if (raw != null) {
                JSONObject d = new JSONObject(raw[0]);
                JSONArray arr = d.optJSONArray("shares");
                long cut = System.currentTimeMillis() / 1000L - 30 * 86400L;
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null || s.optLong("at", 0) <= cut) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", s.optString("id", ""));
                    o.put("name", s.optString("name", ""));
                    o.put("from", s.optString("from", ""));
                    o.put("fromName", s.optString("fromName", ""));
                    o.put("at", s.optLong("at", 0));
                    JSONArray tr = s.optJSONArray("tracks");
                    o.put("count", tr == null ? 0 : tr.length());
                    list.put(o);
                }
            }
            out.put("ok", true);
            out.put("list", list);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "读取失败");
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject importShare(File dir, String shareId) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            if (c == null) {
                out.put("ok", false);
                out.put("msg", "还没有登录");
                return out;
            }
            String me = uid(dir);
            String path = "users/" + me + "/share_inbox.json";
            String[] raw = getFile(c, path);
            if (raw == null) {
                out.put("ok", false);
                out.put("msg", "分享不存在或已过期");
                return out;
            }
            JSONObject d = new JSONObject(raw[0]);
            JSONArray arr = d.optJSONArray("shares");
            JSONObject target = null;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && shareId.equals(s.optString("id", ""))) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                out.put("ok", false);
                out.put("msg", "分享不存在或已过期");
                return out;
            }
            JSONObject lib = readJson(new File(dir, "library.json"), "{\"tracks\":[]}");
            JSONArray tracks = lib.optJSONArray("tracks");
            java.util.HashMap<String, JSONObject> byKey = new java.util.HashMap<String, JSONObject>();
            for (int i = 0; tracks != null && i < tracks.length(); i++) {
                JSONObject t = tracks.optJSONObject(i);
                if (t == null) continue;
                String k = normText(t.optString("title", "")) + "|" + normText(t.optString("artist", ""));
                if (!k.equals("|") && !byKey.containsKey(k)) byKey.put(k, t);
            }
            JSONArray items = new JSONArray();
            int matched = 0, missing = 0;
            JSONArray sts = target.optJSONArray("tracks");
            for (int i = 0; sts != null && i < sts.length(); i++) {
                JSONObject st = sts.optJSONObject(i);
                if (st == null) continue;
                String ttl = st.optString("title", "").trim();
                if (ttl.isEmpty()) continue;
                String art = st.optString("artist", "").trim();
                String k = normText(ttl) + "|" + normText(art);
                JSONObject hit = byKey.get(k);
                JSONObject it = new JSONObject();
                if (hit != null) {
                    it.put("path", hit.optString("path", ""));
                    it.put("name", hit.optString("name", ""));
                    it.put("title", hit.optString("title", ttl));
                    it.put("artist", hit.optString("artist", art));
                    it.put("duration", hit.optDouble("duration", 0));
                    matched++;
                } else {
                    it.put("path", "");
                    it.put("name", ttl);
                    it.put("title", ttl);
                    it.put("artist", art);
                    it.put("duration", (int) st.optDouble("duration", 0));
                    it.put("offline", true);
                    missing++;
                }
                items.put(it);
            }
            String name = target.optString("fromName", target.optString("from", "好友"))
                    + " 的分享 · " + target.optString("name", "歌单");
            JSONObject pls = readJson(new File(dir, "playlists.json"), "{\"playlists\":[]}");
            JSONArray list = pls.optJSONArray("playlists");
            if (list == null) {
                list = new JSONArray();
                pls.put("playlists", list);
            }
            String newId = "p" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            JSONObject np = new JSONObject();
            np.put("id", newId);
            np.put("name", name);
            np.put("items", items);
            list.put(np);
            writeJson(new File(dir, "playlists.json"), pls);
            JSONArray keep = new JSONArray();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && !shareId.equals(s.optString("id", ""))) keep.put(s);
            }
            d.put("shares", keep);
            putFile(c, path, d.toString(), raw[1], "导入分享歌单");
            out.put("ok", true);
            out.put("playlistId", newId);
            out.put("name", name);
            out.put("matched", matched);
            out.put("missing", missing);
            out.put("msg", "已导入「" + name + "」：" + matched + " 首本地可播、" + missing + " 首未下载");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "导入失败");
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String friendsPath(File dir) {
        String me = uid(dir);
        if (!me.matches("\\d{8}")) return null;
        return "users/" + me + "/friends.json";
    }

    // ---- 好友码（与登录账号ID分离：加好友只暴露好友码，拿到好友码也登不进别人账号）----
    private static String[] ensureFriendCodes(File dir) {
        try {
            Creds c = creds(dir);
            String[] f = getFile(c, "accounts.json");
            JSONObject data = f != null ? new JSONObject(f[0]) : new JSONObject();
            JSONArray accs = data.optJSONArray("accounts");
            if (accs == null) accs = new JSONArray();
            java.util.HashSet<String> used = new java.util.HashSet<String>();
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null) {
                    String fc = a.optString("friendCode", "").trim();
                    if (fc.matches("\\d{8}")) used.add(fc);
                }
            }
            boolean changed = false;
            java.util.Random rnd = new java.util.Random();
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a == null) continue;
                String fc = a.optString("friendCode", "").trim();
                if (fc.matches("\\d{8}")) continue;
                String cand = "";
                for (int k = 0; k < 300; k++) {
                    String x = String.valueOf(10000000 + rnd.nextInt(90000000));
                    if (used.contains(x) || x.equals(a.optString("id", ""))) continue;
                    cand = x;
                    break;
                }
                if (cand.isEmpty()) continue;
                a.put("friendCode", cand);
                used.add(cand);
                changed = true;
            }
            if (changed) {
                try {
                    data.put("updatedAt", System.currentTimeMillis() / 1000L);
                    putFile(c, "accounts.json", data.toString(), f == null ? null : f[1], "生成好友码");
                } catch (Exception ignored) {
                }
            }
            JSONObject byCode = new JSONObject();
            JSONObject byId = new JSONObject();
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a == null) continue;
                String fc = a.optString("friendCode", "").trim();
                if (fc.matches("\\d{8}")) byCode.put(fc, a);
                byId.put(a.optString("id", ""), a);
            }
            return new String[] {byCode.toString(), byId.toString()};
        } catch (Exception e) {
            return new String[] {"{}", "{}"};
        }
    }

    // ── 好友申请：inbox（收到的）/ sent（发出的）──
    private static JSONObject readRemoteJson(Creds c, String path) {
        try {
            String[] f = getFile(c, path);
            if (f != null) return new JSONObject(f[0]);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readRemoteSha(Creds c, String path) {
        try {
            String[] f = getFile(c, path);
            if (f != null) return f[1];
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void ensureUserDir(Creds c, String uid) {
        try {
            if (getFile(c, "users/" + uid + "/.keep") != null) return;
            putFile(c, "users/" + uid + "/.keep", "1", null, "初始化用户目录");
        } catch (Exception ignored) {
        }
    }

    private static JSONObject friendMeta(Creds c) {
        JSONObject meta = new JSONObject();
        try {
            String[] acc = getFile(c, "accounts.json");
            if (acc != null) {
                JSONArray list = new JSONObject(acc[0]).optJSONArray("accounts");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject a = list.optJSONObject(i);
                    if (a != null) meta.put(a.optString("id", ""), a);
                }
            }
        } catch (Exception ignored) {
        }
        return meta;
    }

    /** 我收到的好友申请 */
    public static JSONObject friendsRequests(File dir) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            out.put("ok", true);
            out.put("list", new JSONArray());
            if (!me.matches("\\d{8}")) {
                out.put("msg", "账号ID未绑定");
                return out;
            }
            Creds c = creds(dir);
            JSONObject inbox = readRemoteJson(c, "users/" + me + "/inbox.json");
            JSONArray reqs = inbox == null ? null : inbox.optJSONArray("requests");
            JSONObject meta = friendMeta(c);
            JSONObject byId = new JSONObject(ensureFriendCodes(dir)[1]);
            JSONArray rows = new JSONArray();
            for (int i = 0; reqs != null && i < reqs.length(); i++) {
                JSONObject r = reqs.optJSONObject(i);
                if (r == null) continue;
                String fid = r.optString("from", "");
                if (!fid.matches("\\d{8}")) continue;
                JSONObject a = meta.optJSONObject(fid);
                JSONObject accRow = byId.optJSONObject(fid);
                JSONObject row = new JSONObject();
                row.put("from", fid);
                row.put("code", accRow == null ? "" : accRow.optString("friendCode", ""));
                row.put("name", a == null ? "" : a.optString("name", ""));
                row.put("at", r.optLong("at", 0));
                rows.put(row);
            }
            out.put("list", rows);
            out.put("total", rows.length());
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "读取申请失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 我发出的、还没被同意的好友申请 */
    public static JSONObject friendsSent(File dir) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            out.put("ok", true);
            out.put("list", new JSONArray());
            if (!me.matches("\\d{8}")) return out;
            Creds c = creds(dir);
            JSONObject sent = readRemoteJson(c, "users/" + me + "/sent.json");
            JSONArray items = sent == null ? null : sent.optJSONArray("sent");
            JSONObject meta = friendMeta(c);
            JSONObject byId = new JSONObject(ensureFriendCodes(dir)[1]);
            JSONArray rows = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject x = items.optJSONObject(i);
                if (x == null) continue;
                String to = x.optString("to", "");
                if (!to.matches("\\d{8}")) continue;
                JSONObject a = meta.optJSONObject(to);
                JSONObject accRow = byId.optJSONObject(to);
                JSONObject row = new JSONObject();
                row.put("to", to);
                row.put("code", accRow == null ? "" : accRow.optString("friendCode", ""));
                row.put("name", a == null ? "" : a.optString("name", ""));
                row.put("at", x.optLong("at", 0));
                rows.put(row);
            }
            out.put("list", rows);
            out.put("total", rows.length());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 同意好友申请：双方互为好友 */
    public static JSONObject acceptFriend(File dir, String code) {
        JSONObject out = new JSONObject();
        try {
            String fid = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (fid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "好友码格式不对");
                return out;
            }
            String me = uid(dir);
            if (!me.matches("\\d{8}")) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定");
                return out;
            }
            Creds c = creds(dir);
            // 1) 我的好友列表加对方
            String myPath = friendsPath(dir);
            JSONObject myfd = readRemoteJson(c, myPath);
            String msha = readRemoteSha(c, myPath);
            JSONArray myFriends = myfd == null ? new JSONArray() : myfd.optJSONArray("friends");
            if (myFriends == null) myFriends = new JSONArray();
            boolean has = false;
            for (int i = 0; i < myFriends.length(); i++) {
                JSONObject f = myFriends.optJSONObject(i);
                if (f != null && fid.equals(f.optString("id", ""))) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                JSONObject nf = new JSONObject();
                nf.put("id", fid);
                nf.put("addedAt", System.currentTimeMillis() / 1000L);
                myFriends.put(nf);
                JSONObject fd = new JSONObject();
                fd.put("friends", myFriends);
                putFile(c, myPath, fd.toString(), msha, "同意好友");
            }
            // 2) 对方好友列表加我
            String hisPath = "users/" + fid + "/friends.json";
            JSONObject hisfd = readRemoteJson(c, hisPath);
            String hsha = readRemoteSha(c, hisPath);
            JSONArray hisFriends = hisfd == null ? new JSONArray() : hisfd.optJSONArray("friends");
            if (hisFriends == null) hisFriends = new JSONArray();
            boolean hisHas = false;
            for (int i = 0; i < hisFriends.length(); i++) {
                JSONObject f = hisFriends.optJSONObject(i);
                if (f != null && me.equals(f.optString("id", ""))) {
                    hisHas = true;
                    break;
                }
            }
            if (!hisHas) {
                JSONObject nf = new JSONObject();
                nf.put("id", me);
                nf.put("addedAt", System.currentTimeMillis() / 1000L);
                hisFriends.put(nf);
                if (hisFriends.length() == 1) {
                    // 原先没有好友时，也要把已有条目保留：构造完整对象
                }
                JSONObject fd = new JSONObject();
                fd.put("friends", hisFriends);
                ensureUserDir(c, fid);
                putFile(c, hisPath, fd.toString(), hsha, "互相添加好友");
            }
            // 3) 移除我 inbox 里这条
            String inboxPath = "users/" + me + "/inbox.json";
            JSONObject inbox = readRemoteJson(c, inboxPath);
            String isha = readRemoteSha(c, inboxPath);
            JSONArray reqs = inbox == null ? null : inbox.optJSONArray("requests");
            JSONArray keep = new JSONArray();
            for (int i = 0; reqs != null && i < reqs.length(); i++) {
                JSONObject r = reqs.optJSONObject(i);
                if (r != null && fid.equals(r.optString("from", ""))) continue;
                if (r != null) keep.put(r);
            }
            JSONObject ib = new JSONObject();
            ib.put("requests", keep);
            try {
                putFile(c, inboxPath, ib.toString(), isha, "处理好友申请");
            } catch (Exception ignored) {
            }
            // 4) 移除对方 sent 里这条
            String sentPath = "users/" + fid + "/sent.json";
            JSONObject sent = readRemoteJson(c, sentPath);
            String ssha = readRemoteSha(c, sentPath);
            JSONArray items = sent == null ? null : sent.optJSONArray("sent");
            JSONArray keep2 = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject x = items.optJSONObject(i);
                if (x != null && me.equals(x.optString("to", ""))) continue;
                if (x != null) keep2.put(x);
            }
            try {
                JSONObject sb = new JSONObject();
                sb.put("sent", keep2);
                putFile(c, sentPath, sb.toString(), ssha, "处理好友申请");
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("msg", "已同意，你们现在是好友了");
            out.put("id", fid);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "操作失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 拒绝好友申请 */
    public static JSONObject rejectFriend(File dir, String code) {
        JSONObject out = new JSONObject();
        try {
            String fid = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (fid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "好友码格式不对");
                return out;
            }
            String me = uid(dir);
            if (!me.matches("\\d{8}")) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定");
                return out;
            }
            Creds c = creds(dir);
            String inboxPath = "users/" + me + "/inbox.json";
            JSONObject inbox = readRemoteJson(c, inboxPath);
            String isha = readRemoteSha(c, inboxPath);
            JSONArray reqs = inbox == null ? null : inbox.optJSONArray("requests");
            JSONArray keep = new JSONArray();
            for (int i = 0; reqs != null && i < reqs.length(); i++) {
                JSONObject r = reqs.optJSONObject(i);
                if (r != null && fid.equals(r.optString("from", ""))) continue;
                if (r != null) keep.put(r);
            }
            JSONObject ib = new JSONObject();
            ib.put("requests", keep);
            putFile(c, inboxPath, ib.toString(), isha, "拒绝好友申请");
            String sentPath = "users/" + fid + "/sent.json";
            JSONObject sent = readRemoteJson(c, sentPath);
            String ssha = readRemoteSha(c, sentPath);
            JSONArray items = sent == null ? null : sent.optJSONArray("sent");
            JSONArray keep2 = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject x = items.optJSONObject(i);
                if (x != null && me.equals(x.optString("to", ""))) continue;
                if (x != null) keep2.put(x);
            }
            try {
                JSONObject sb = new JSONObject();
                sb.put("sent", keep2);
                putFile(c, sentPath, sb.toString(), ssha, "好友申请被拒绝");
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("msg", "已拒绝好友申请");
            out.put("id", fid);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "操作失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject friendsList(File dir) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            out.put("myCode", me);
            Creds c = creds(dir);
            if (c.repo == null || c.repo.isEmpty()) {
                out.put("ok", false);
                out.put("msg", "还没有登录");
                out.put("list", new JSONArray());
                return out;
            }
            // 好友码映射（白名单里可能有 friendCode）
            String[] maps = ensureFriendCodes(dir);
            JSONObject byId = new JSONObject(maps[1]);
            // 白名单信息一次取回，给好友补昵称/最近登录
            JSONObject meta = new JSONObject();
            try {
                String[] acc = getFile(c, "accounts.json");
                if (acc != null) {
                    JSONArray listA = new JSONObject(acc[0]).optJSONArray("accounts");
                    for (int i = 0; listA != null && i < listA.length(); i++) {
                        JSONObject a = listA.optJSONObject(i);
                        if (a != null) meta.put(a.optString("id", ""), a);
                    }
                }
            } catch (Exception ignored) {
            }
            String fpath = friendsPath(dir);
            if (fpath == null) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定，请退出登录后重新输入账号ID");
                out.put("list", new JSONArray());
                return out;
            }
            String[] fr = getFile(c, fpath);
            JSONArray list = new JSONArray();
            if (fr != null) {
                JSONObject f = new JSONObject(fr[0]);
                JSONArray arr = f.optJSONArray("friends");
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    Object rawObj = arr.get(i);
                    String fid = rawObj instanceof JSONObject
                            ? ((JSONObject) rawObj).optString("id", "") : String.valueOf(rawObj);
                    if (!fid.matches("\\d{8}") || !seen.add(fid)) continue;
                    JSONObject a = meta.optJSONObject(fid);
                    String nick = rawObj instanceof JSONObject
                            ? ((JSONObject) rawObj).optString("nick", "") : "";
                    // 昵称优先用对方在 App 里设置的
                    String pname = "";
                    try {
                        String[] pr = getFile(c, "users/" + fid + "/profile.json");
                        if (pr != null) pname = new JSONObject(pr[0]).optString("name", "").trim();
                    } catch (Exception ignored) {
                    }
                    if (pname.isEmpty()) pname = a == null ? "" : a.optString("name", "");
                    // 最近登录时间：以对方目录里的 login.json 为准
                    long loginAt = a == null ? 0 : a.optLong("lastLoginAt", 0);
                    String lastDev = a == null ? "" : a.optString("lastDevice", "");
                    try {
                        String[] lg = getFile(c, "users/" + fid + "/login.json");
                        if (lg != null) {
                            JSONObject lj = new JSONObject(lg[0]);
                            loginAt = Math.max(loginAt, lj.optLong("lastLoginAt", 0));
                            if (lastDev.isEmpty()) lastDev = lj.optString("device", "");
                        }
                    } catch (Exception ignored) {
                    }
                    JSONObject accRow = byId.optJSONObject(fid);
                    JSONObject row = new JSONObject();
                    row.put("id", fid);
                    row.put("code", accRow == null ? "" : accRow.optString("friendCode", ""));
                    row.put("nick", nick);
                    row.put("name", pname);
                    row.put("enabled", a == null ? true : a.optBoolean("enabled", true));
                    row.put("lastLoginAt", loginAt);
                    row.put("lastDevice", lastDev);
                    list.put(row);
                }
            }
            JSONObject meAcc = byId.optJSONObject(me);
            out.put("ok", true);
            out.put("list", list);
            out.put("myCode", meAcc == null ? "" : meAcc.optString("friendCode", ""));
            out.put("total", list.length());
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "读取好友失败：" + e.getMessage());
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject addFriend(File dir, String code) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            String fc = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (fc.length() != 8) {
                out.put("ok", false);
                out.put("msg", "请输入 8 位好友码");
                return out;
            }
            if (!me.matches("\\d{8}")) {
                out.put("ok", false);
                out.put("msg", "本机账号未绑定，请先退出登录重新登录");
                return out;
            }
            Creds c = creds(dir);
            String[] maps = ensureFriendCodes(dir);
            JSONObject byCode = new JSONObject(maps[0]);
            JSONObject hit = byCode.optJSONObject(fc);
            if (hit == null) {
                out.put("ok", false);
                out.put("msg", "好友码不存在，让对方确认一下");
                return out;
            }
            final String aid = hit.optString("id", "");
            if (aid.isEmpty()) {
                out.put("ok", false);
                out.put("msg", "好友码异常，让对方重新拿一下");
                return out;
            }
            if (aid.equals(me)) {
                out.put("ok", false);
                out.put("msg", "不能添加自己为好友");
                return out;
            }
            if (!hit.optBoolean("enabled", true)) {
                out.put("ok", false);
                out.put("msg", "对方账号已停用");
                return out;
            }
            // 已经是好友？
            String myPath = friendsPath(dir);
            if (myPath == null) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定，请退出登录后重新输入账号ID");
                return out;
            }
            JSONObject myfd = readRemoteJson(c, myPath);
            JSONArray myFriends = myfd == null ? null : myfd.optJSONArray("friends");
            for (int i = 0; myFriends != null && i < myFriends.length(); i++) {
                JSONObject f = myFriends.optJSONObject(i);
                if (f != null && aid.equals(f.optString("id", ""))) {
                    out.put("ok", true);
                    out.put("msg", "已经是好友了");
                    out.put("id", aid);
                    return out;
                }
            }
            // 已经发过申请？
            String sentPath = "users/" + me + "/sent.json";
            JSONObject sentObj = readRemoteJson(c, sentPath);
            String ssha = readRemoteSha(c, sentPath);
            JSONArray sent = sentObj == null ? null : sentObj.optJSONArray("sent");
            JSONArray sentKeep = new JSONArray();
            for (int i = 0; sent != null && i < sent.length(); i++) {
                JSONObject x = sent.optJSONObject(i);
                if (x == null) continue;
                if (aid.equals(x.optString("to", ""))) {
                    out.put("ok", true);
                    out.put("msg", "已经发过申请了，等对方同意");
                    out.put("id", aid);
                    return out;
                }
                sentKeep.put(x);
            }
            // 写对方 inbox
            ensureUserDir(c, aid);
            String inboxPath = "users/" + aid + "/inbox.json";
            JSONObject inbox = readRemoteJson(c, inboxPath);
            String isha = readRemoteSha(c, inboxPath);
            JSONArray reqs = inbox == null ? null : inbox.optJSONArray("requests");
            JSONArray reqKeep = new JSONArray();
            for (int i = 0; reqs != null && i < reqs.length(); i++) {
                JSONObject r = reqs.optJSONObject(i);
                if (r == null) continue;
                if (me.equals(r.optString("from", ""))) continue;
                reqKeep.put(r);
            }
            JSONObject nr = new JSONObject();
            nr.put("from", me);
            nr.put("at", System.currentTimeMillis() / 1000L);
            reqKeep.put(nr);
            JSONObject ib = new JSONObject();
            ib.put("requests", reqKeep);
            putFile(c, inboxPath, ib.toString(), isha, "好友申请");
            // 记录自己的已发申请
            JSONObject nx = new JSONObject();
            nx.put("to", aid);
            nx.put("at", System.currentTimeMillis() / 1000L);
            sentKeep.put(nx);
            JSONObject sb = new JSONObject();
            sb.put("sent", sentKeep);
            try {
                putFile(c, sentPath, sb.toString(), ssha, "记录已发申请");
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("msg", "已发送好友申请，等对方同意");
            out.put("id", aid);
            out.put("name", hit.optString("name", ""));
            out.put("pending", true);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "添加失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject removeFriend(File dir, String code) {
        JSONObject out = new JSONObject();
        try {
            String aid = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (aid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "好友码格式不对");
                return out;
            }
            Creds c = creds(dir);
            String path = friendsPath(dir);
            if (path == null) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定，请退出登录后重新输入账号ID");
                return out;
            }
            String[] fr = getFile(c, path);
            if (fr == null) {
                out.put("ok", true);
                out.put("msg", "好友列表本来就是空的");
                return out;
            }
            JSONObject fd = new JSONObject(fr[0]);
            JSONArray old = fd.optJSONArray("friends");
            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; old != null && i < old.length(); i++) {
                Object rawObj = old.get(i);
                String fid = rawObj instanceof JSONObject
                        ? ((JSONObject) rawObj).optString("id", "") : String.valueOf(rawObj);
                if (aid.equals(fid)) {
                    removed = true;
                    continue;
                }
                next.put(rawObj);
            }
            if (!removed) {
                out.put("ok", true);
                out.put("msg", "这个好友不在列表里");
                return out;
            }
            fd.put("friends", next);
            putFile(c, path, fd.toString(), fr[1], "删除好友");
            out.put("ok", true);
            out.put("msg", "已删除好友 " + aid);
            out.put("id", aid);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "删除失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 给好友设置备注名（只存在自己的好友列表里，仅自己可见） */
    public static JSONObject setFriendNote(File dir, String code, String note) {
        JSONObject out = new JSONObject();
        try {
            String aid = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (aid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "好友码格式不对");
                return out;
            }
            if (!uid(dir).matches("\\d{8}")) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定，请退出登录后重新输入账号ID");
                return out;
            }
            Creds c = creds(dir);
            String path = friendsPath(dir);
            if (path == null) {
                out.put("ok", false);
                out.put("msg", "账号ID未绑定");
                return out;
            }
            String[] fr = getFile(c, path);
            if (fr == null) {
                out.put("ok", false);
                out.put("msg", "好友列表还是空的");
                return out;
            }
            JSONObject fd = new JSONObject(fr[0]);
            JSONArray friends = fd.optJSONArray("friends");
            String nick = note == null ? "" : note.trim();
            if (nick.length() > 20) nick = nick.substring(0, 20);
            boolean found = false;
            for (int i = 0; friends != null && i < friends.length(); i++) {
                JSONObject f = friends.optJSONObject(i);
                if (f == null) continue;
                if (aid.equals(f.optString("id", ""))) {
                    if (nick.isEmpty()) f.remove("nick");
                    else f.put("nick", nick);
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.put("ok", false);
                out.put("msg", "这个好友不在列表里");
                return out;
            }
            putFile(c, path, fd.toString(), fr[1], "好友备注");
            out.put("ok", true);
            out.put("id", aid);
            out.put("nick", nick);
            out.put("msg", nick.isEmpty() ? "备注已清除" : "备注已保存");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "保存失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 管理员：给账号写备注（存在 Hub 白名单里，普通用户看不到） */
    public static JSONObject setNote(File dir, String accountId, String note) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以操作");
                return out;
            }
            String aid = accountId == null ? "" : accountId.replaceAll("[^0-9]", "");
            if (aid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "账号ID 格式不对");
                return out;
            }
            String nt = note == null ? "" : note.trim();
            if (nt.length() > 40) nt = nt.substring(0, 40);
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) {
                out.put("ok", false);
                out.put("msg", "读取白名单失败");
                return out;
            }
            JSONObject wh = new JSONObject(acc[0]);
            JSONArray list = wh.optJSONArray("accounts");
            boolean found = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a != null && aid.equals(a.optString("id", ""))) {
                    if (nt.isEmpty()) a.remove("note");
                    else a.put("note", nt);
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.put("ok", false);
                out.put("msg", "白名单里找不到这个账号ID");
                return out;
            }
            putFile(c, "accounts.json", wh.toString(), acc[1], "更新账号备注");
            out.put("ok", true);
            out.put("id", aid);
            out.put("note", nt);
            out.put("msg", nt.isEmpty() ? "备注已清除" : "备注已保存");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "操作失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 管理员：允许 / 禁止某个账号ID登录（写回 accounts.json 的 enabled） */
    public static JSONObject setAccount(File dir, String accountId, boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            String me = uid(dir);
            if (!requireAdmin(me)) {
                out.put("ok", false);
                out.put("msg", "只有管理员可以操作");
                return out;
            }
            String aid = accountId == null ? "" : accountId.replaceAll("[^0-9]", "");
            if (aid.length() != 8) {
                out.put("ok", false);
                out.put("msg", "账号ID 格式不对");
                return out;
            }
            if (isAdminId(aid) && !enabled) {
                out.put("ok", false);
                out.put("msg", "不能禁止管理员自己登录");
                return out;
            }
            Creds c = creds(dir);
            String[] acc = getFile(c, "accounts.json");
            if (acc == null) {
                out.put("ok", false);
                out.put("msg", "读取白名单失败");
                return out;
            }
            JSONObject wh = new JSONObject(acc[0]);
            JSONArray list = wh.optJSONArray("accounts");
            boolean found = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a != null && aid.equals(a.optString("id", ""))) {
                    a.put("enabled", enabled);
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.put("ok", false);
                out.put("msg", "白名单里找不到这个账号ID");
                return out;
            }
            putFile(c, "accounts.json", wh.toString(), acc[1], "更新账号权限");
            out.put("ok", true);
            out.put("id", aid);
            out.put("enabled", enabled);
            out.put("msg", (enabled ? "已允许 " : "已禁止 ") + aid + " 登录");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "操作失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject syncNow(File dir) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            JSONObject conf = readJson(new File(dir, SYNC_FILE), "{}");
            if (conf.optString("repo", "").isEmpty()) {
                out.put("ok", false);
                out.put("msg", "还没有登录");
                return out;
            }
            String id = uid(dir);
            if (id == null || !id.matches("\\d{8}")) {
                // 账号ID 没绑定时同步会写到 users//data.json，直接拦下来
                out.put("ok", false);
                out.put("msg", "账号ID未绑定，请退出登录后重新输入 8 位账号ID");
                return out;
            }
            String path = "users/" + id + "/data.json";
            String[] remote = getFile(c, path);
            long lts = localStamp(dir);
            String action;
            String msg;
            if (remote == null) {
                JSONObject p = payload(dir);
                putFile(c, path, p.toString(), null, "初始化 Luvsic 同步数据");
                conf.put("baseRemoteTs", p.optLong("updatedAt"));
                conf.put("baseLocalTs", localStamp(dir));
                writeJson(new File(dir, SYNC_FILE), conf);
                action = "push";
                JSONArray tr0 = p.optJSONObject("library") == null ? null : p.optJSONObject("library").optJSONArray("tracks");
                msg = "已上传本机曲库/歌单（" + (tr0 == null ? 0 : tr0.length()) + " 首）";
            } else {
                JSONObject rem = new JSONObject(remote[0]);
                long rts = rem.optLong("updatedAt", 0);
                long baseR = conf.optLong("baseRemoteTs", 0);
                long baseL = conf.optLong("baseLocalTs", 0);
                boolean localChanged = lts > baseL;
                boolean remoteChanged = rts > baseR;
                if (remoteChanged && !localChanged) {
                    JSONObject mr = applyPayload(dir, rem);
                    conf.put("baseRemoteTs", rts);
                    conf.put("baseLocalTs", localStamp(dir));
                    writeJson(new File(dir, SYNC_FILE), conf);
                    action = "pull";
                    // 合并后把结果推回云端（云端保持两端并集，不丢本机独有信息）
                    try {
                        JSONObject p2 = payload(dir);
                        putFile(c, path, p2.toString(), remote[1], "Luvsic 同步（合并后）");
                        conf.put("baseRemoteTs", p2.optLong("updatedAt"));
                        conf.put("baseLocalTs", localStamp(dir));
                        writeJson(new File(dir, SYNC_FILE), conf);
                    } catch (Exception ignored) {
                    }
                    msg = "已合并云端：新增 " + mr.optInt("added", 0) + " 首";
                    if (mr.optInt("removed", 0) > 0) msg += "、移除 " + mr.optInt("removed", 0) + " 首";
                    if (mr.optInt("matched", 0) > 0) msg += "、" + mr.optInt("matched", 0) + " 首换回本机路径";
                    if (mr.optInt("offline", 0) > 0) msg += "、" + mr.optInt("offline", 0) + " 首未下载";
                    msg += "（本机共 " + mr.optInt("total", 0) + " 首）";
                } else if (localChanged && !remoteChanged) {
                    // push 前先合并云端：避免用本机旧数据覆盖另一端的元数据（歌手/标题等）
                    try {
                        applyPayload(dir, rem);
                    } catch (Exception ignored) {
                    }
                    JSONObject p = payload(dir);
                    putFile(c, path, p.toString(), remote[1], "Luvsic 同步");
                    conf.put("baseRemoteTs", p.optLong("updatedAt"));
                    conf.put("baseLocalTs", localStamp(dir));
                    writeJson(new File(dir, SYNC_FILE), conf);
                    action = "push";
                    java.util.Set<String> snap = loadSnapshot(dir);
                    JSONArray curArr = p.optJSONObject("library") == null ? null : p.optJSONObject("library").optJSONArray("tracks");
                    java.util.Set<String> curSet = new java.util.HashSet<String>();
                    for (int i = 0; curArr != null && i < curArr.length(); i++) {
                        JSONObject t = curArr.optJSONObject(i);
                        if (t != null && !t.optString("path", "").isEmpty()) curSet.add(t.optString("path", ""));
                    }
                    int dUp = 0, dDel = 0;
                    if (!snap.isEmpty()) {
                        for (String pp : curSet) if (!snap.contains(pp)) dUp++;
                        for (String pp : snap) if (!curSet.contains(pp)) dDel++;
                    }
                    if (!snap.isEmpty() && (dUp + dDel) > 0) {
                        msg = "已上传本机改动：+" + dUp + " 首";
                        if (dDel > 0) msg += "、-" + dDel + " 首";
                    } else {
                        msg = "已上传本机改动";
                    }
                } else if (localChanged && remoteChanged) {
                    JSONObject mr = applyPayload(dir, rem);
                    conf.put("baseRemoteTs", rts);
                    conf.put("baseLocalTs", localStamp(dir));
                    writeJson(new File(dir, SYNC_FILE), conf);
                    action = "conflict";
                    // 合并后把结果推回云端（两端并集统一）
                    try {
                        JSONObject p2 = payload(dir);
                        putFile(c, path, p2.toString(), remote[1], "Luvsic 同步（合并后）");
                        conf.put("baseRemoteTs", p2.optLong("updatedAt"));
                        conf.put("baseLocalTs", localStamp(dir));
                        writeJson(new File(dir, SYNC_FILE), conf);
                    } catch (Exception ignored) {
                    }
                    msg = "两端都有改动，已合并：新增 " + mr.optInt("added", 0) + " 首";
                    if (mr.optInt("removed", 0) > 0) msg += "、移除 " + mr.optInt("removed", 0) + " 首";
                    if (mr.optInt("matched", 0) > 0) msg += "、" + mr.optInt("matched", 0) + " 首换回本机路径";
                    if (mr.optInt("offline", 0) > 0) msg += "、" + mr.optInt("offline", 0) + " 首未下载";
                    msg += "（本机共 " + mr.optInt("total", 0) + " 首）";
                } else {
                    action = "none";
                    msg = "已是最新";
                }
            }
            saveSnapshot(dir);
            pushSocial(c, dir);
            out.put("ok", true);
            out.put("action", action);
            out.put("msg", msg);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "同步失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject profileGet(File dir) {
        JSONObject out = new JSONObject();
        try {
            JSONObject u = readJson(new File(dir, USER_FILE), "{}");
            out.put("ok", true);
            out.put("id", u.optString("id", ""));
            out.put("name", u.optString("name", ""));
            out.put("avatar", u.optString("avatar", ""));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject profileSet(File dir, String name, String avatar) {
        JSONObject out = new JSONObject();
        try {
            JSONObject u = readJson(new File(dir, USER_FILE), "{}");
            if (name != null && !name.isEmpty()) u.put("name", name);
            if (avatar != null && !avatar.isEmpty()) u.put("avatar", avatar);
            writeJson(new File(dir, USER_FILE), u);
            try {
                String uidNow = u.optString("id", "");
                if (uidNow.matches("\\d{8}") && name != null && !name.isEmpty()) {
                    JSONObject nicks = readJson(new File(dir, "nicknames.json"), "{}");
                    nicks.put(uidNow, name);
                    writeJson(new File(dir, "nicknames.json"), nicks);
                }
            } catch (Exception ignored) {
            }
            // 立刻把资料推到云端 + 账号表昵称（好友和电脑端马上看到新名字）
            try {
                Creds c = creds(dir);
                String uidNow = uid(dir);
                if (c != null && !uidNow.isEmpty()) {
                    JSONObject p = new JSONObject();
                    p.put("id", uidNow);
                    p.put("name", u.optString("name", ""));
                    p.put("avatar", u.optString("avatar", ""));
                    p.put("updatedAt", System.currentTimeMillis() / 1000L);
                    String[] old = getFile(c, "users/" + uidNow + "/profile.json");
                    putFile(c, "users/" + uidNow + "/profile.json", p.toString(),
                            old == null ? null : old[1], "更新资料");
                    String[] acc = getFile(c, "accounts.json");
                    if (acc != null) {
                        JSONObject wh = new JSONObject(acc[0]);
                        JSONArray list = wh.optJSONArray("accounts");
                        for (int i = 0; list != null && i < list.length(); i++) {
                            JSONObject a = list.optJSONObject(i);
                            if (a != null && uidNow.equals(a.optString("id", ""))) {
                                a.put("name", u.optString("name", ""));
                                break;
                            }
                        }
                        wh.put("updatedAt", System.currentTimeMillis() / 1000L);
                        putFile(c, "accounts.json", wh.toString(), acc[1], "同步账号昵称");
                    }
                }
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("profile", profileGet(dir));
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", e.toString());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject statsAdd(File dir, double sec) {
        JSONObject out = new JSONObject();
        try {
            if (sec <= 0) {
                out.put("ok", true);
                return out;
            }
            JSONObject st = readJson(new File(dir, STATS_FILE), "{\"days\":{},\"total\":0}");
            JSONObject days = st.optJSONObject("days");
            if (days == null) days = new JSONObject();
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
            days.put(day, days.optDouble(day, 0) + sec);
            st.put("days", days);
            st.put("total", st.optDouble("total", 0) + sec);
            writeJson(new File(dir, STATS_FILE), st);
            out.put("ok", true);
            out.put("total", (int) st.optDouble("total", 0));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject statsGet(File dir) {
        JSONObject out = new JSONObject();
        try {
            JSONObject st = readJson(new File(dir, STATS_FILE), "{\"days\":{},\"total\":0}");
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
            JSONObject days = st.optJSONObject("days");
            out.put("ok", true);
            out.put("today", (int) (days == null ? 0 : days.optDouble(day, 0)));
            out.put("total", (int) st.optDouble("total", 0));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject logins(File dir) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            JSONArray arr = getArray(c, "users");
            JSONArray rows = new JSONArray();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String name = it.optString("name", "");
                    if (name.isEmpty()) continue;
                    JSONObject row = new JSONObject();
                    row.put("id", name);
                    row.put("name", "");
                    row.put("platform", "");
                    row.put("device", "");
                    row.put("lastLoginAt", 0);
                    try {
                        String[] lg = getFile(c, "users/" + name + "/login.json");
                        if (lg != null) {
                            JSONObject o = new JSONObject(lg[0]);
                            row.put("id", o.optString("id", name));
                            row.put("name", o.optString("name", ""));
                            row.put("platform", o.optString("platform", ""));
                            row.put("device", o.optString("device", ""));
                            row.put("lastLoginAt", o.optLong("lastLoginAt", 0));
                        }
                    } catch (Exception ignored) {
                    }
                    if (row.optLong("lastLoginAt") == 0) {
                        try {
                            String[] pr = getFile(c, "users/" + name + "/profile.json");
                            if (pr != null) {
                                JSONObject o = new JSONObject(pr[0]);
                                row.put("name", o.optString("name", ""));
                                row.put("lastLoginAt", o.optLong("updatedAt", 0));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    rows.put(row);
                }
            }
            out.put("ok", true);
            out.put("list", rows);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", e.toString());
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject leaderboard(File dir) {
        JSONObject out = new JSONObject();
        try {
            Creds c = creds(dir);
            // 排行榜只统计「自己 + 好友」
            java.util.LinkedHashSet<String> allow = new java.util.LinkedHashSet<String>();
            String me = uid(dir);
            if (me.matches("\\d{8}")) {
                allow.add(me);
                JSONObject myfd = readRemoteJson(c, friendsPath(dir));
                JSONArray myFriends = myfd == null ? null : myfd.optJSONArray("friends");
                for (int i = 0; myFriends != null && i < myFriends.length(); i++) {
                    Object ro = myFriends.get(i);
                    String fid = ro instanceof JSONObject
                            ? ((JSONObject) ro).optString("id", "") : String.valueOf(ro);
                    if (fid.matches("\\d{8}")) allow.add(fid);
                }
            }
            // 我给好友的备注（昵称优先级最高）
            JSONObject nicks = new JSONObject();
            try {
                JSONObject myfd2 = readRemoteJson(c, friendsPath(dir));
                JSONArray myFriends2 = myfd2 == null ? null : myfd2.optJSONArray("friends");
                for (int i = 0; myFriends2 != null && i < myFriends2.length(); i++) {
                    JSONObject f = myFriends2.optJSONObject(i);
                    if (f == null) continue;
                    String nk = f.optString("nick", "").trim();
                    if (!nk.isEmpty()) nicks.put(f.optString("id", ""), nk);
                }
            } catch (Exception ignored) {
            }
            // 白名单昵称一次取回
            JSONObject meta = new JSONObject();
            try {
                String[] acc = getFile(c, "accounts.json");
                if (acc != null) {
                    JSONArray listA = new JSONObject(acc[0]).optJSONArray("accounts");
                    for (int i = 0; listA != null && i < listA.length(); i++) {
                        JSONObject a = listA.optJSONObject(i);
                        if (a != null) meta.put(a.optString("id", ""), a);
                    }
                }
            } catch (Exception ignored) {
            }
            // 自己 + 好友全部列出：没有听歌时长也显示 0 分钟，产生时长后自动排上去
            List<JSONObject> rows = new ArrayList<>();
            for (String one : allow) {
                String nm = nicks.optString(one, "");
                long total = 0, week = 0, month = 0;
                String nowTitle = "";
                boolean nowPlaying = false;
                try {
                    String[] st = getFile(c, "users/" + one + "/stats.json");
                    if (st != null) {
                        JSONObject sd = new JSONObject(st[0]);
                        total = sd.optLong("total", 0);
                        JSONObject days = sd.optJSONObject("days");
                        if (days != null) {
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.add(java.util.Calendar.DAY_OF_YEAR, -6);
                            String cut7 = fmtDate(cal);
                            cal = java.util.Calendar.getInstance();
                            cal.add(java.util.Calendar.DAY_OF_YEAR, -29);
                            String cut30 = fmtDate(cal);
                            java.util.Iterator<String> itd = days.keys();
                            while (itd.hasNext()) {
                                String dd = itd.next();
                                long v = days.optLong(dd, 0);
                                if (dd.compareTo(cut7) >= 0) week += v;
                                if (dd.compareTo(cut30) >= 0) month += v;
                            }
                        }
                        JSONObject nw = sd.optJSONObject("now");
                        if (nw != null && !nw.optString("title", "").isEmpty()
                                && nw.optLong("updatedAt", 0) > System.currentTimeMillis() / 1000L - 300) {
                            nowTitle = nw.optString("title", "");
                            nowPlaying = nw.optBoolean("playing", false);
                        }
                    }
                } catch (Exception ignored) {
                }
                // 显示名优先级：我给对方的备注 > 对方在 App 设置的昵称
                if (nm.isEmpty()) {
                    try {
                        String[] pr = getFile(c, "users/" + one + "/profile.json");
                        if (pr != null) nm = new JSONObject(pr[0]).optString("name", "").trim();
                    } catch (Exception ignored) {
                    }
                }
                if (nm.isEmpty()) {
                    JSONObject a = meta.optJSONObject(one);
                    if (a != null) nm = a.optString("name", "");
                }
                JSONObject accRow = meta.optJSONObject(one);
                JSONObject row = new JSONObject();
                row.put("id", one);
                row.put("code", accRow == null ? "" : accRow.optString("friendCode", ""));
                row.put("nick", nicks.optString(one, ""));
                row.put("name", nm.isEmpty() ? "" : nm);
                row.put("me", one.equals(me));
                row.put("total", total);
                row.put("week", week);
                row.put("month", month);
                row.put("nowTitle", nowTitle);
                row.put("nowPlaying", nowPlaying);
                rows.add(row);
            }
            Collections.sort(rows, new Comparator<JSONObject>() {
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("total"), a.optLong("total"));
                }
            });
            JSONArray outArr = new JSONArray();
            for (JSONObject o : rows) outArr.put(o);
            out.put("ok", true);
            out.put("list", outArr);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", e.toString());
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
