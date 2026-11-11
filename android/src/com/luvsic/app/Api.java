package com.luvsic.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 安卓版后端：曲库（MediaStore）、配置、歌单、歌词、封面。 */
public class Api {
    private static final String TAG = "LuvsicApi";
    private static final String[] AUDIO_EXT = {".mp3", ".flac", ".m4a", ".wav", ".ogg",
            ".opus", ".aac", ".wma", ".ape", ".aiff", ".aif", ".dsf", ".mp4"};

    private final Context ctx;
    private final File dir;
    private final Map<String, byte[]> coverCache = Collections.synchronizedMap(new HashMap<String, byte[]>());

    public Api(Context ctx) {
        this.ctx = ctx;
        this.dir = ctx.getFilesDir();
        // 启动自愈：升级/换机/清数据后，曲库里若是别的设备路径（如 D:\...），
        // 按歌名/歌手/时长重新匹配本机文件，避免「曲库看起来被清空」。
        try {
            if (hasAudioPermission()) repairLibrary();
        } catch (Exception e) {
            Log.w(TAG, "startup repairLibrary", e);
        }
    }

    /** 曲库需要重建时的标记，避免每次调用都全盘扫描 */
    private volatile boolean libraryRepaired = false;

    // ───────────────────────── 分发 ─────────────────────────
    public String dispatch(String name, JSONArray args) {
        try {
            if (name == null) return err("missing name");
            if (name.equals("get_config")) return configJson();
            if (name.equals("save_config")) return saveConfig(args.optJSONObject(0));
            if (name.equals("cached_library")) return cachedLibrary();
            if (name.equals("scan_folder")) return scanFolder();
            if (name.equals("library_candidates")) return libraryCandidates();
            if (name.equals("library_set_picks")) return librarySetPicks(args.optJSONArray(0));
            if (name.equals("get_playlists")) return readJsonFile("playlists.json", "{}");
            if (name.equals("save_playlists")) return savePlaylists(args.optJSONObject(0));
            if (name.equals("get_lyrics")) {
                return getLyrics(args.optString(0, ""), args.optString(1, ""),
                        args.optString(2, ""), args.optInt(3, 0),
                        args.optString(4, ""), args.optBoolean(5, false));
            }
            if (name.equals("clear_lyrics_cache")) return ok();
            if (name.equals("remove_tracks")) return removeTracks(args.optJSONArray(0));
            if (name.equals("set_now_playing")) return HubSync.setNow(dir, args.optString(0, ""), args.optBoolean(1, true)).toString();
            if (name.equals("share_playlist")) return HubSync.sharePlaylist(dir, args.optString(0, ""), args.optString(1, "")).toString();
            if (name.equals("share_inbox_get")) return HubSync.shareInbox(dir).toString();
            if (name.equals("import_shared_playlist")) return HubSync.importShare(dir, args.optString(0, "")).toString();
            if (name.equals("update_track_meta")) {
                return updateTrackMeta(args.optString(0, ""), args.optString(1, ""),
                        args.optString(2, ""), args.optString(3, "")).toString();
            }
            if (name.equals("meta_fix_start")) return metaFixStart().toString();
            if (name.equals("meta_fix_status")) return metaFixStatus().toString();
            if (name.equals("login_with_id")) {
                String r = HubSync.loginWithId(dir, args.optString(0, ""), Build.MODEL, args.optString(1, "")).toString();
                libraryRepaired = false;
                try { repairLibrary(); } catch (Exception ignored) {}
                return r;
            }
            if (name.equals("sync_status")) return HubSync.status(dir).toString();
            if (name.equals("sync_logout")) return HubSync.logout(dir).toString();
            if (name.equals("library_repair")) {
                libraryRepaired = false;
                return repairLibrary().toString();
            }
            if (name.equals("sync_now")) {
                String r = HubSync.syncNow(dir).toString();
                try { repairLibrary(); } catch (Exception ignored) {}
                return r;
            }
            if (name.equals("profile_get")) return HubSync.profileGet(dir).toString();
            if (name.equals("profile_set")) return HubSync.profileSet(dir, args.optString(0, ""), args.optString(1, "")).toString();
            if (name.equals("stats_add")) return HubSync.statsAdd(dir, args.optDouble(0, 0)).toString();
            if (name.equals("stats_get")) return HubSync.statsGet(dir).toString();
            if (name.equals("hub_flush_stats")) return HubSync.statsFlush(dir).toString();
            if (name.equals("login_touch")) return HubSync.touchLogin(dir).toString();
            if (name.equals("hub_leaderboard")) return HubSync.leaderboard(dir).toString();
            if (name.equals("hub_logins")) return HubSync.logins(dir).toString();
            if (name.equals("hub_accounts")) return HubSync.accounts(dir).toString();
            if (name.equals("hub_set_account")) {
                return HubSync.setAccount(dir, args.optString(0, ""), args.optBoolean(1, true)).toString();
            }
            if (name.equals("hub_set_note")) {
                return HubSync.setNote(dir, args.optString(0, ""), args.optString(1, "")).toString();
            }
            if (name.equals("friends_list")) return HubSync.friendsList(dir).toString();
            if (name.equals("friends_add")) return HubSync.addFriend(dir, args.optString(0, "")).toString();
            if (name.equals("friends_remove")) return HubSync.removeFriend(dir, args.optString(0, "")).toString();
            if (name.equals("friends_note")) {
                return HubSync.setFriendNote(dir, args.optString(0, ""), args.optString(1, "")).toString();
            }
            if (name.equals("app_version")) {
                try {
                    android.content.pm.PackageInfo pi = ctx.getPackageManager()
                            .getPackageInfo(ctx.getPackageName(), 0);
                    JSONObject ov = new JSONObject();
                    ov.put("ok", true);
                    ov.put("version", pi.versionName);
                    ov.put("versionCode", pi.versionCode);
                    ov.put("platform", "android");
                    return ov.toString();
                } catch (Exception e) {
                    return err("app_version: " + e);
                }
            }
            if (name.equals("check_update")) return HubSync.checkUpdate(dir, ctx).toString();
            if (name.equals("download_update")) {
                return downloadUpdate(args.optString(0, ""), args.optString(1, ""));
            }
            if (name.equals("open_install_settings")) {
                try {
                    android.content.Intent s2 = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + ctx.getPackageName()));
                    s2.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(s2);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("msg", "已打开设置，请允许「应用内安装其他应用」");
                    return o.toString();
                } catch (Exception e) {
                    return err("打开设置失败：" + e);
                }
            }
            if (name.equals("device_info")) return HubSync.deviceInfo(dir).toString();
            if (name.equals("admin_pending_devices")) return HubSync.adminPendingDevices(dir).toString();
            if (name.equals("admin_approve_device")) {
                return HubSync.adminApproveDevice(dir, args.optString(0, ""), args.optString(1, "")).toString();
            }
            if (name.equals("admin_deny_device")) {
                return HubSync.adminDenyDevice(dir, args.optString(0, ""), args.optString(1, "")).toString();
            }
            if (name.equals("friends_requests")) return HubSync.friendsRequests(dir).toString();
            if (name.equals("friends_sent")) return HubSync.friendsSent(dir).toString();
            if (name.equals("friends_accept")) return HubSync.acceptFriend(dir, args.optString(0, "")).toString();
            if (name.equals("friends_reject")) return HubSync.rejectFriend(dir, args.optString(0, "")).toString();
            if (name.equals("scan_wechat_music")) return err("安卓版不支持微信导入");
            if (name.equals("pick_folder") || name.equals("pick_files") || name.equals("pick_image")) {
                return "null";
            }
            if (name.equals("set_custom_wallpaper")) return err("暂不支持自定义壁纸");
            if (name.equals("start_drag") || name.equals("min_window") || name.equals("max_window")
                    || name.equals("close_window") || name.equals("toggle_top")) {
                return ok();
            }
            return err("unknown api: " + name);
        } catch (Exception e) {
            Log.w(TAG, "dispatch " + name, e);
            return err(e.toString());
        }
    }

    // ───────────────────────── 配置 / 歌单 ─────────────────────────
    private String configJson() {
        return readJsonFile("config.json", "{}");
    }

    private String saveConfig(JSONObject cfg) {
        return saveJsonFile("config.json", cfg);
    }

    /** 下载更新 APK：后台线程读 contents API -> 写 Downloads -> 调起系统安装器 */
    private String downloadUpdate(final String url, final String fileName) {
        try {
            if (url == null || url.trim().isEmpty()) return err("下载地址无效");
            // 华为/鸿蒙等系统需要先允许"安装未知应用"，否则安装界面会被系统静默拦掉
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26
                        && !ctx.getPackageManager().canRequestPackageInstalls()) {
                    try {
                        android.content.Intent s = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + ctx.getPackageName()));
                        s.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(s);
                    } catch (Exception ignored) {
                    }
                    JSONObject need = new JSONObject();
                    need.put("ok", false);
                    need.put("needPermission", true);
                    need.put("msg", "需要「安装未知应用」权限才能更新");
                    return need.toString();
                }
            } catch (Exception ignored) {
            }
            final String name = fileName == null || fileName.trim().isEmpty()
                    ? "Luvsic-update.apk" : fileName.trim();
            final Context appCtx = ctx.getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.net.HttpURLConnection con =
                                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        con.setConnectTimeout(30000);
                        con.setReadTimeout(180000);
                        con.setRequestProperty("User-Agent", "Luvsic-Android");
                        java.io.InputStream is = con.getInputStream();
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                        is.close();
                        String text = new String(bos.toByteArray(), "UTF-8");
                        String b64 = "";
                        int idx = text.indexOf("\"content\"");
                        if (idx >= 0) b64 = new JSONObject(text).optString("content", "");
                        if (b64.isEmpty()) throw new Exception("下载内容为空");
                        byte[] apk = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        android.net.Uri uri = null;
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                                    "application/vnd.android.package-archive");
                            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_DOWNLOADS + "/Luvsic");
                            uri = appCtx.getContentResolver().insert(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        }
                        if (uri == null) throw new Exception("系统不支持（Android 10+）");
                        java.io.OutputStream os = appCtx.getContentResolver().openOutputStream(uri);
                        os.write(apk);
                        os.close();
                        final android.net.Uri furi = uri;
                        final Context actCtx = ctx;
                        // 先发一条通知：即使安装界面没弹出来，点通知也能装
                        try {
                            android.app.NotificationManager nm = (android.app.NotificationManager)
                                    appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
                            String ch = "luvsic_update";
                            if (android.os.Build.VERSION.SDK_INT >= 26) {
                                nm.createNotificationChannel(new android.app.NotificationChannel(
                                        ch, "Luvsic 更新", android.app.NotificationManager.IMPORTANCE_HIGH));
                            }
                            android.content.Intent open = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW);
                            open.setDataAndType(furi, "application/vnd.android.package-archive");
                            open.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            int piFlags = android.os.Build.VERSION.SDK_INT >= 23
                                    ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                            | android.app.PendingIntent.FLAG_IMMUTABLE
                                    : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                                    appCtx, 1, open, piFlags);
                            android.app.Notification.Builder nb = android.os.Build.VERSION.SDK_INT >= 26
                                    ? new android.app.Notification.Builder(appCtx, ch)
                                    : new android.app.Notification.Builder(appCtx);
                            nb.setSmallIcon(android.R.drawable.stat_sys_download_done)
                                    .setContentTitle("Luvsic 新版本已下载")
                                    .setContentText("点这里安装")
                                    .setContentIntent(pi)
                                    .setAutoCancel(true);
                            nm.notify(1001, nb.build());
                        } catch (Exception ignored) {
                        }
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.post(new Runnable() {
                            public void run() {
                                try {
                                    android.content.Intent it = new android.content.Intent(
                                            android.content.Intent.ACTION_VIEW);
                                    it.setDataAndType(furi, "application/vnd.android.package-archive");
                                    it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                    actCtx.startActivity(it);
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    } catch (Exception e) {
                        android.util.Log.w("LuvsicApi", "downloadUpdate", e);
                    }
                }
            }).start();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("msg", "正在下载（约 1MB），完成后会自动弹出安装界面");
            return out.toString();
        } catch (Exception e) {
            return err("download_update: " + e);
        }
    }

    /** 保存歌单：字符串条目统一转对象，并从曲库补标题/歌手（避免旧格式混进数据） */
    private String savePlaylists(JSONObject obj) {
        try {
            JSONObject lib = new JSONObject(readJsonFile("library.json", "{\"tracks\":[]}"));
            JSONArray tracks = lib.optJSONArray("tracks");
            java.util.HashMap<String, JSONObject> byPath = new java.util.HashMap<String, JSONObject>();
            for (int i = 0; tracks != null && i < tracks.length(); i++) {
                JSONObject t = tracks.optJSONObject(i);
                if (t != null) byPath.put(t.optString("path", ""), t);
            }
            JSONArray pls = obj == null ? null : obj.optJSONArray("playlists");
            for (int i = 0; pls != null && i < pls.length(); i++) {
                JSONObject pl = pls.optJSONObject(i);
                if (pl == null) continue;
                JSONArray items = pl.optJSONArray("items");
                JSONArray norm = new JSONArray();
                for (int j = 0; items != null && j < items.length(); j++) {
                    Object raw = items.get(j);
                    JSONObject o;
                    if (raw instanceof JSONObject) {
                        o = new JSONObject(raw.toString());
                    } else {
                        String s = String.valueOf(raw);
                        int sl = s.lastIndexOf('/');
                        if (sl < 0) sl = s.lastIndexOf('\\');
                        String fn = sl >= 0 ? s.substring(sl + 1) : s;
                        o = new JSONObject();
                        o.put("path", s);
                        o.put("name", fn);
                    }
                    JSONObject hit = byPath.get(o.optString("path", ""));
                    if (hit != null) {
                        if (o.optString("title", "").isEmpty()) o.put("title", hit.optString("title", ""));
                        if (o.optString("artist", "").isEmpty()) o.put("artist", hit.optString("artist", ""));
                        if (o.optDouble("duration", 0) <= 0) o.put("duration", hit.optDouble("duration", 0));
                    }
                    norm.put(o);
                }
                pl.put("items", norm);
            }
            return saveJsonFile("playlists.json", obj == null ? new JSONObject() : obj);
        } catch (Exception e) {
            return err("save_playlists: " + e);
        }
    }

    private String saveJsonFile(String name, JSONObject obj) {
        try {
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write((obj == null ? "{}" : obj.toString()).getBytes("UTF-8"));
            fos.close();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e.toString());
        }
    }

    private String readJsonFile(String name, String dflt) {
        try {
            File f = new File(dir, name);
            if (!f.isFile()) return dflt;
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            String s = new String(bos.toByteArray(), "UTF-8").trim();
            return s.isEmpty() ? dflt : s;
        } catch (Exception e) {
            return dflt;
        }
    }

    // ───────────────────────── 曲库 ─────────────────────────
    private static final String PICKS_FILE = "picks.json";   // 用户勾选入库的歌曲路径白名单

    /** 读白名单；文件不存在返回 null（老用户/首次），存在返回集合（可能为空） */
    private java.util.LinkedHashSet<String> readPicksSet() {
        try {
            String raw = readJsonFile(PICKS_FILE, "");
            if (raw == null || raw.trim().isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            JSONArray arr = o.optJSONArray("paths");
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (p != null && !p.isEmpty()) set.add(p);
                }
            }
            return set;
        } catch (Exception e) {
            return null;
        }
    }

    private void writePicksSet(java.util.Collection<String> paths) {
        try {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            if (paths != null) for (String p : paths) arr.put(p);
            o.put("paths", arr);
            o.put("updated", System.currentTimeMillis());
            saveJsonFile(PICKS_FILE, o);
        } catch (Exception ignored) {
        }
    }

    private void addPick(String path) {
        if (path == null || path.isEmpty()) return;
        java.util.LinkedHashSet<String> set = readPicksSet();
        if (set == null) set = new java.util.LinkedHashSet<String>();
        if (set.add(path)) writePicksSet(set);
    }

    private static java.util.Set<String> pathsOf(JSONArray tracks) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        if (tracks != null) {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject t = tracks.optJSONObject(i);
                if (t == null) continue;
                String p = t.optString("path", "");
                if (!p.isEmpty()) set.add(p);
            }
        }
        return set;
    }

    private String cachedLibrary() {
        java.util.LinkedHashSet<String> picks = readPicksSet();
        String raw = readJsonFile("library.json", "");
        JSONArray all = new JSONArray();
        JSONObject lib = null;
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                lib = new JSONObject(raw);
                JSONArray tr = lib.optJSONArray("tracks");
                if (tr != null) all = tr;
            } catch (Exception ignored) {
            }
        }
        if (picks == null) {
            // 老版本升级：把现有曲库当成用户已选的，之后不再自动加歌
            if (all.length() > 0) {
                writePicksSet(pathsOf(all));
                return raw;
            }
            // 真·首次：不再自动全加，交给界面弹「选歌入库」让用户自己挑
            return "{\"ok\":true,\"tracks\":[],\"folder\":\"系统音乐\",\"total\":0,\"msg\":\"点「选歌」从本机挑选歌曲\"}";
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t == null) continue;
            if (picks.contains(t.optString("path", ""))) {
                // 读出来时顺手清洗旧曲库里的脏歌名/歌手（1782994xxxx_PsCopy_ 之类）
                String p = t.optString("path", "");
                try {
                    t.put("title", cleanTitle(t.optString("title", ""), p));
                    t.put("artist", cleanArtist(t.optString("artist", ""), p));
                } catch (Exception ignored) {
                }
                out.put(t);
            }
        }
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("tracks", out);
            res.put("folder", "系统音乐");
            res.put("total", out.length());
            res.put("msg", out.length() + " 首");
        } catch (Exception ignored) {
        }
        return res.toString();
    }

    private boolean hasAudioPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private String scanFolder() {
        if (!hasAudioPermission()) {
            return "{\"ok\":false,\"tracks\":[],\"msg\":\"需要「音乐和音频」权限才能扫描本机歌曲\"}";
        }
        JSONArray all = scanAllLocal();
        if (all == null) all = new JSONArray();
        java.util.LinkedHashSet<String> picks = readPicksSet();
        JSONArray use;
        if (picks == null) {
            // 第一次扫描：全部入库，并记为「用户已选」，之后不再自动加歌
            picks = new java.util.LinkedHashSet<String>(pathsOf(all));
            writePicksSet(picks);
            use = all;
        } else {
            java.util.Set<String> exist = pathsOf(all);
            picks.retainAll(exist);   // 已从手机删除的文件，从白名单里也去掉
            writePicksSet(picks);
            use = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject t = all.optJSONObject(i);
                if (t != null && picks.contains(t.optString("path", ""))) use.put(t);
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("tracks", use);
            out.put("folder", "系统音乐");
            out.put("total", use.length());
            out.put("msg", use.length() + " 首（库内） / 本机共 " + all.length() + " 首");
            Log.i(TAG, "scanFolder library=" + use.length() + " local=" + all.length());
            saveJsonFile("library.json", out);
            try {
                HubSync.clearTombstones(dir, use);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    /** 扫描本机全部音乐（不按白名单过滤），返回按歌手+标题排好序的数组 */
    private JSONArray scanAllLocal() {
        JSONArray tracks = new JSONArray();
        ContentResolver cr = ctx.getContentResolver();
        String[] cols = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
        };
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Audio.Media.IS_MUSIC + "!=0 OR "
                            + MediaStore.Audio.Media.DURATION + ">=30000", null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String path = c.getString(1);
                    String disp = c.getString(6);
                    if (path == null) continue;
                    String ext = "";
                    String lower = (disp != null ? disp : path).toLowerCase(Locale.US);
                    int dot = lower.lastIndexOf('.');
                    if (dot >= 0) ext = lower.substring(dot);
                    boolean okExt = false;
                    for (String e : AUDIO_EXT) {
                        if (ext.equals(e)) {
                            okExt = true;
                            break;
                        }
                    }
                    if (!okExt) continue;
                    String baseName = (disp != null ? disp : path).toLowerCase(Locale.US);
                    if (baseName.contains("录音") || baseName.contains("voice")
                            || baseName.contains("recording") || baseName.contains("通话")
                            || baseName.startsWith("rec") || baseName.contains("语音")) continue;
                    long durMs = c.getLong(5);
                    if (durMs < 30000) continue;
                    JSONObject t = new JSONObject();
                    t.put("path", path);
                    t.put("name", disp != null ? disp : new File(path).getName());
                    t.put("title", cleanTitle(safeStr(c.getString(2)), path));
                    t.put("artist", cleanArtist(safeStr(c.getString(3)), path));
                    t.put("album", safeStr(c.getString(4)));
                    t.put("duration", durMs / 1000.0);
                    t.put("hasCover", false);
                    tracks.put(t);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
        } finally {
            if (c != null) c.close();
        }
        try {
            List<JSONObject> list = new ArrayList<JSONObject>();
            for (int i = 0; i < tracks.length(); i++) list.add(tracks.getJSONObject(i));
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    String aa = a.optString("artist", "zzz") + "|" + a.optString("title");
                    String bb = b.optString("artist", "zzz") + "|" + b.optString("title");
                    return aa.compareTo(bb);
                }
            });
            JSONArray sorted = new JSONArray();
            for (JSONObject o : list) sorted.put(o);
            tracks = sorted;
        } catch (Exception ignored) {
        }
        return tracks;
    }

    /**
     * 扫描本机全部歌曲，带上「是否已在曲库」标记，给界面做勾选入库用。
     */
    private String libraryCandidates() {
        if (!hasAudioPermission()) {
            return "{\"ok\":false,\"tracks\":[],\"msg\":\"需要「音乐和音频」权限才能扫描本机歌曲\"}";
        }
        JSONArray all = scanAllLocal();
        java.util.LinkedHashSet<String> picks = readPicksSet();
        if (picks == null) picks = new java.util.LinkedHashSet<String>(pathsOf(all));
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < all.length(); i++) {
                JSONObject t = all.optJSONObject(i);
                if (t == null) continue;
                t.put("inLib", picks.contains(t.optString("path", "")));
                out.put(t);
            }
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("tracks", out);
            res.put("total", out.length());
            res.put("picked", picks.size());
            return res.toString();
        } catch (Exception e) {
            return err("candidates: " + e);
        }
    }

    /** 保存用户勾选：只保留勾选的歌进曲库，之后重新扫描也不会把删掉的加回来 */
    private String librarySetPicks(JSONArray paths) {
        try {
            java.util.LinkedHashSet<String> picks = new java.util.LinkedHashSet<String>();
            if (paths != null) {
                for (int i = 0; i < paths.length(); i++) {
                    String p = paths.optString(i, "");
                    if (p != null && !p.isEmpty()) picks.add(p);
                }
            }
            writePicksSet(picks);
            return scanFolder();
        } catch (Exception e) {
            return err("set_picks: " + e);
        }
    }

    private String removeTracks(JSONArray paths) {
        if (paths == null) return ok();
        try {
            java.util.Set<String> gone = new java.util.HashSet<String>();
            for (int i = 0; i < paths.length(); i++) gone.add(paths.optString(i));
            File f = new File(dir, "library.json");
            if (!f.isFile()) return ok();
            String raw = readJsonFile("library.json", "{}");
            JSONObject lib = new JSONObject(raw);
            JSONArray old = lib.optJSONArray("tracks");
            JSONArray now = new JSONArray();
            JSONArray removed = new JSONArray();
            if (old != null) {
                for (int i = 0; i < old.length(); i++) {
                    JSONObject t = old.getJSONObject(i);
                    if (!gone.contains(t.optString("path"))) now.put(t);
                    else removed.put(t);
                }
            }
            lib.put("tracks", now);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(lib.toString().getBytes("UTF-8"));
            fos.close();
            try {
                HubSync.addTombstones(dir, removed);
            } catch (Exception ignored) {
            }
            // 从白名单里也删掉，避免下次扫描又自动加回来
            java.util.LinkedHashSet<String> picks = readPicksSet();
            if (picks != null) {
                picks.removeAll(gone);
                writePicksSet(picks);
            }
            return ok();
        } catch (Exception e) {
            return err(e.toString());
        }
    }

    // ───────────────────────── 曲库辅助 ─────────────────────────
    private static final String MUSIC_SUBDIR = "Luvsic";   // /sdcard/Music/Luvsic


    /** 校验文件头是不是真音频（防止把网络拦截页/广告页存成音频缓存） */
    private static boolean audioHeadOk(File f) {
        if (f == null || !f.isFile() || f.length() < 4) return false;
        java.io.FileInputStream is = null;
        try {
            is = new java.io.FileInputStream(f);
            byte[] h = new byte[16];
            int n = is.read(h);
            if (n < 4) return false;
            if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return true;
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
            if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return true;
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return true;
            if (n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return true;
            if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return true;
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }


    /** 把临时文件发布到 /sdcard/Music/Luvsic，返回最终文件路径；失败返回 null。 */
    private String publishToMusic(File src, String displayName, String fmt) {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fmt));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + MUSIC_SUBDIR);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = ctx.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                if (os == null) return null;
                FileInputStream fis = new FileInputStream(src);
                byte[] buf = new byte[262144];
                int n;
                while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                fis.close();
                os.flush();
                os.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
                String path = queryData(uri);
                if (path != null && new File(path).exists()) return path;
                String guess = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), MUSIC_SUBDIR + "/" + displayName).getAbsolutePath();
                if (new File(guess).exists()) return guess;
                if (path != null && !path.isEmpty()) return path;
                return guess;
            } catch (Exception e) {
                Log.w(TAG, "publish MediaStore", e);
                try {
                    if (uri != null) ctx.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
                return null;
            }
        }
        // Android 10 以下：直接写公共音乐目录（需要 WRITE_EXTERNAL_STORAGE）
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC), MUSIC_SUBDIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File dest = new File(dir, displayName);
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[262144];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close();
            fos.close();
            MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "publish legacy", e);
            return null;
        }
    }

    private String queryData(Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 曲库目录里同名文件已存在时，生成「名字 (1).ext」这样的名字。 */
    private String uniqueDisplayName(String base, String ext) {
        String name = base + "." + ext;
        if (!audioNameExists(name)) return name;
        for (int i = 1; i < 1000; i++) {
            String cand = base + " (" + i + ")." + ext;
            if (!audioNameExists(cand)) return cand;
        }
        return base + " (" + System.currentTimeMillis() + ")." + ext;
    }

    private boolean audioNameExists(String displayName) {
        Cursor c = null;
        try {
            String rel = Environment.DIRECTORY_MUSIC + "/" + MUSIC_SUBDIR + "/";
            c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{displayName, rel + "%"}, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;   // 查询失败就不去重
        } finally {
            if (c != null) c.close();
        }
    }

    private JSONObject buildTrack(String path, String title, String artist, boolean hasCover) {
        JSONObject t = new JSONObject();
        String mmTitle = "", mmArtist = "", mmAlbum = "";
        long durMs = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            mmTitle = nullToEmpty(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            mmArtist = nullToEmpty(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            mmAlbum = nullToEmpty(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) durMs = Long.parseLong(d.trim());
        } catch (Exception ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        String name = new File(path).getName();
        String ft = mmTitle.isEmpty() ? nullToEmpty(title) : mmTitle;
        String fa = mmArtist.isEmpty() ? nullToEmpty(artist) : mmArtist;
        try {
            t.put("path", path);
            t.put("name", name);
            t.put("title", cleanTitle(ft, path));
            t.put("artist", cleanArtist(fa, path));
            t.put("album", mmAlbum);
            t.put("duration", durMs > 0 ? durMs / 1000.0 : 0);
            t.put("hasCover", hasCover);
        } catch (Exception ignored) {
        }
        return t;
    }

    /** 重新扫曲库，并把本次新下载、但还没来得及被 MediaStore 索引的曲目补进去。 */
    private JSONObject refreshLibrary(JSONArray extraTracks) {
        // 新下载/导入的歌先记入白名单，否则扫描时会被当成「未勾选」过滤掉
        if (extraTracks != null) {
            for (int i = 0; i < extraTracks.length(); i++) {
                JSONObject tr = extraTracks.optJSONObject(i);
                if (tr != null) addPick(tr.optString("path", ""));
            }
        }
        JSONObject lib;
        try {
            lib = new JSONObject(scanFolder());
        } catch (Exception e) {
            lib = new JSONObject();
        }
        JSONArray tracks = lib.optJSONArray("tracks");
        if (tracks == null) tracks = new JSONArray();
        if (extraTracks != null) {
            for (int i = 0; i < extraTracks.length(); i++) {
                JSONObject tr = extraTracks.optJSONObject(i);
                if (tr == null) continue;
                String p = tr.optString("path");
                boolean found = false;
                for (int j = 0; j < tracks.length(); j++) {
                    if (p.equals(tracks.optJSONObject(j).optString("path"))) {
                        found = true;
                        // MediaStore 可能刚扫到（duration=0 / hasCover 恒为 null），用我们自己的信息补上
                        JSONObject exist = tracks.optJSONObject(j);
                        if (tr.optDouble("duration", 0) > 0) {
                            try {
                                exist.put("duration", tr.optDouble("duration", 0));
                            } catch (Exception ignored) {
                            }
                        }
                        if (tr.optBoolean("hasCover", false)) {
                            try {
                                exist.put("hasCover", true);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    }
                }
                if (!found) tracks.put(tr);
            }
            try {
                List<JSONObject> list = new ArrayList<JSONObject>();
                for (int i = 0; i < tracks.length(); i++) list.add(tracks.getJSONObject(i));
                Collections.sort(list, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        String aa = a.optString("artist", "zzz") + "|" + a.optString("title");
                        String bb = b.optString("artist", "zzz") + "|" + b.optString("title");
                        return aa.compareTo(bb);
                    }
                });
                JSONArray sorted = new JSONArray();
                for (JSONObject o : list) sorted.put(o);
                tracks = sorted;
            } catch (Exception ignored) {
            }
        }
        try {
            lib.put("ok", true);
            lib.put("tracks", tracks);
            if (lib.optString("folder", "").isEmpty()) lib.put("folder", "系统音乐");
            lib.put("msg", tracks.length() + " 首");
            File f = new File(dir, "library.json");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(lib.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
        return lib;
    }



    /** 用「歌名 + 歌手」在网易云搜索里挑最匹配的一首（歌名完全一致优先，再看歌手）。 */
    private JSONObject matchSong(String title, String artist) {
        String q = (title + " " + artist).trim();
        if (q.isEmpty()) return null;
        JSONObject sr = Netease.search(q, 0, 10);
        if (!sr.optBoolean("ok")) return null;
        JSONArray songs = sr.optJSONArray("songs");
        if (songs == null || songs.length() == 0) return null;
        String nt = normTitle(title);
        String na = normArtist(artist);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            int score = 0;
            String st = normTitle(s.optString("title"));
            String sa = normArtist(s.optString("artist"));
            if (nt != null && nt.equals(st)) score += 2;
            else if (nt != null && st != null && (st.contains(nt) || nt.contains(st))) score += 1;
            if (!na.isEmpty() && !sa.isEmpty()) {
                if (na.equals(sa)) score += 2;
                else if (sa.contains(na) || na.contains(sa)) score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** 歌手名归一化：去掉 & 、 空格 等分隔符，用于跨平台比较。 */
    private static String normArtist(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[&、,，/\\s·・]+", "");
    }


    private static String mimeOf(String fmt) {
        if ("flac".equals(fmt)) return "audio/flac";
        if ("mp3".equals(fmt)) return "audio/mpeg";
        if ("m4a".equals(fmt) || "aac".equals(fmt)) return "audio/mp4";
        if ("ogg".equals(fmt)) return "audio/ogg";
        if ("wav".equals(fmt)) return "audio/wav";
        return "audio/mpeg";
    }

    /** 文件名清洗（对应桌面版 Api._safe_name）：去非法字符、压空格、限长。 */
    private static String safeFileName(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }

    // ─────────────────────── 下载封面缓存 ───────────────────────
    private File coverCacheFile(String path) {
        File d = new File(dir, "covers");
        if (!d.isDirectory()) d.mkdirs();
        return new File(d, md5(path) + ".img");
    }

    private void saveCoverFile(String path, byte[] data) {
        try {
            File f = coverCacheFile(path);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "save cover", e);
        }
    }

    private byte[] readCoverFile(String path) {
        try {
            File f = coverCacheFile(path);
            if (!f.isFile() || f.length() < 500) return null;
            byte[] raw = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int off = 0;
            while (off < raw.length) {
                int n = fis.read(raw, off, raw.length - off);
                if (n <= 0) break;
                off += n;
            }
            fis.close();
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── 封面 ─────────────────────────
    public byte[] readCover(String path) {
        if (path == null || path.isEmpty()) return null;
        byte[] cached = coverCache.get(path);
        if (cached != null) return cached;
        // 在线下载时保存的封面缓存优先（不用改音频标签）
        byte[] fromDisk = readCoverFile(path);
        if (fromDisk != null) {
            if (coverCache.size() > 120) coverCache.clear();
            coverCache.put(path, fromDisk);
            return fromDisk;
        }
        byte[] data = null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            data = mmr.getEmbeddedPicture();
        } catch (Exception ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        if (data == null) {
            data = readAlbumArt(path);
        }
        if (data != null) {
            if (coverCache.size() > 120) coverCache.clear();
            coverCache.put(path, data);
        }
        return data;
    }

    /** 没有内嵌封面时，退回系统媒体库的专辑封面。 */
    private byte[] readAlbumArt(String path) {
        try {
            long albumId = -1;
            Cursor c = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.ALBUM_ID},
                    MediaStore.Audio.Media.DATA + "=?", new String[]{path}, null);
            if (c != null) {
                if (c.moveToFirst()) albumId = c.getLong(0);
                c.close();
            }
            if (albumId <= 0) return null;
            android.net.Uri art = android.content.ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), albumId);
            InputStream is = ctx.getContentResolver().openInputStream(art);
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ───────────────────────── 歌词（网易云 → 酷狗 → LRCLIB）─────────────────────────
    private String getLyrics(String title, String artist, String path, int duration) {
        return getLyrics(title, artist, path, duration, "", false);
    }

    /**
     * @param prefer 优先尝试的来源（netease/kugou/lrclib，空=默认顺序）
     * @param force  true 时忽略缓存重新匹配（界面「换一版」用）
     */
    private String getLyrics(String title, String artist, String path, int duration,
                             String prefer, boolean force) {
        try {
            // 版本前缀：匹配算法升级后旧缓存（含错误歌词/空结果）自动失效
            String key = md5(("v7|" + title + "|" + artist).trim());
            File cacheFile = new File(dir, "lyric_" + key + ".json");
            if (!force && cacheFile.isFile()) {
                String raw = readFileText(cacheFile);
                boolean usable = false;
                if (raw != null) {
                    try {
                        usable = lyricLineCount(new JSONObject(raw)) >= 4;   // 残缺歌词（只回一两行）不算缓存
                    } catch (Exception ignored) {
                    }
                }
                if (usable) return raw;
                try { cacheFile.delete(); } catch (Exception ignored) {}
            }
            boolean cjk = isCjk(title + artist);
            String[] order = cjk
                    ? new String[]{"netease", "kugou", "lrclib"}
                    : new String[]{"lrclib", "netease", "kugou"};
            if (prefer != null && !prefer.isEmpty()) {
                java.util.ArrayList<String> al = new java.util.ArrayList<String>();
                al.add(prefer);
                for (String s : order) if (!s.equals(prefer)) al.add(s);
                order = al.toArray(new String[0]);
            }
            Log.i(TAG, "lyrics query t=" + title + " a=" + artist + " d=" + duration
                    + " cjk=" + cjk + " prefer=" + prefer + " force=" + force);
            String bestResult = null;
            int bestLines = 0;
            for (String src : order) {
                JSONObject r;
                if ("netease".equals(src)) r = neteaseLyrics(title, artist, duration);
                else if ("kugou".equals(src)) r = kugouLyrics(title, artist, duration);
                else r = lrclibLyrics(title, artist, duration);
                int n = lyricLineCount(r);
                Log.i(TAG, "lyrics provider " + src + " -> " + (r == null ? "null" : ("lines=" + n)));
                if (r != null) {
                    String s = r.toString();
                    if (n > bestLines) {       // 多源比较：取行数最多的那一份
                        bestLines = n;
                        bestResult = s;
                    }
                }
            }
            if (bestResult != null && bestLines >= 4) {
                writeText(cacheFile, bestResult);
                return bestResult;
            }
            if (bestResult != null) return bestResult;
            // 空结果不落缓存：下次还能重新匹配
            return "{\"plain\":\"\",\"synced\":\"\",\"source\":\"\"}";
        } catch (Exception e) {
            Log.w(TAG, "lyrics fail", e);
            return err("lyrics: " + e);
        }
    }

    /** 统计歌词真正有几行（过滤纯时间戳/制作信息），用来识别残缺歌词 */
    private static int lyricLineCount(JSONObject r) {
        try {
            if (r == null) return 0;
            String body = r.optString("synced", "");
            if (body.isEmpty()) body = r.optString("plain", "");
            if (body.isEmpty()) return 0;
            int n = 0;
            for (String ln : body.split("\n")) {
                String t = ln.replaceAll("\\[[^\\]]*\\]", "").trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("作词") || t.startsWith("作曲") || t.startsWith("编曲")
                        || t.startsWith("制作人") || t.startsWith("出品")) continue;
                n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 酷狗歌词：对华语歌命中率最好（网易云没有周杰伦这类版权） */
    private JSONObject kugouLyrics(String title, String artist, int duration) {
        try {
            String t = title == null ? "" : title.trim();
            if (t.isEmpty()) return null;
            String a = artist == null ? "" : artist.trim();
            String q = (t + " " + a).trim();
            String surl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword="
                    + URLEncoder.encode(q, "UTF-8") + "&page=1&pagesize=10&showtype=1";
            String sbody = httpGet(surl, "https://m.kugou.com/", 9000);
            if (sbody == null) return null;
            JSONObject root = new JSONObject(sbody);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            JSONArray info = data.optJSONArray("info");
            if (info == null) return null;
            String nt = normTitle(t);
            String na = normTitle(a);
            JSONObject best = null;
            double bestScore = 0;
            for (int i = 0; i < info.length(); i++) {
                JSONObject s = info.getJSONObject(i);
                String rawName = s.optString("songname", "");
                if (rawName == null || rawName.isEmpty()) continue;
                // 严格：歌名主体必须对上
                double bs = titleSim(baseTitle(t), baseTitle(rawName));
                if (bs < 0.85) continue;
                String sn = normTitle(s.optString("singername", ""));
                boolean artistOk = (na == null || na.isEmpty());
                if (!artistOk && sn != null && !sn.isEmpty()) {
                    if (sn.contains(na) || na.contains(sn)) artistOk = true;
                }
                if (!artistOk) continue;                 // 翻唱/别人版本不要
                int dur = s.optInt("duration", 0);
                double sc = bs * 3.0 + 2.0;
                if (duration > 0 && dur > 0) {
                    double diff = Math.abs(dur - duration);
                    if (diff > 12) continue;             // 时长差太多，不是同一版本
                    sc += Math.max(0, 2.0 - diff / 12.0);
                }
                if (sc > bestScore) { bestScore = sc; best = s; }
            }
            if (best == null || bestScore < 5.0) return null;
            String hash = best.optString("hash", "");
            if (hash.isEmpty()) return null;

            // 1) 用 hash 找歌词候选
            String curl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword="
                    + URLEncoder.encode(t, "UTF-8") + "&duration=" + Math.max(0, duration)
                    + "&hash=" + hash;
            String cbody = httpGet(curl, "https://m.kugou.com/", 9000);
            if (cbody == null) return null;
            JSONObject cjson = new JSONObject(cbody);
            JSONArray cands = cjson.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject cand = cands.getJSONObject(0);
            String id = cand.optString("id", "");
            String access = cand.optString("accesskey", "");
            if (id.isEmpty() || access.isEmpty()) return null;

            // 2) 下载 LRC（content 是 base64）
            String durl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=" + id
                    + "&accesskey=" + access + "&fmt=lrc&charset=utf8";
            String dbody = httpGet(durl, "https://m.kugou.com/", 9000);
            if (dbody == null) return null;
            JSONObject djson = new JSONObject(dbody);
            String content = djson.optString("content", "");
            if (content.isEmpty()) return null;
            byte[] raw = java.util.Base64.getMimeDecoder().decode(content);
            String lrc = new String(raw, "UTF-8");
            if (lrc.trim().isEmpty()) return null;
            if (lrc.startsWith("\uFEFF")) lrc = lrc.substring(1);

            JSONObject out = new JSONObject();
            out.put("synced", cleanLyrics(lrc));
            out.put("plain", "");
            out.put("trans", "");
            out.put("source", "酷狗音乐");
            out.put("title", best.optString("songname", title));
            out.put("artist", best.optString("singername", artist));
            Log.i(TAG, "lyrics from kugou: " + out.optString("title") + " / " + out.optString("artist")
                    + " score=" + bestScore);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "kugou lyrics: " + e);
            return null;
        }
    }

    /** 含空格的搜索词逐个试，挑「歌名匹配 + 歌手匹配 + 时长接近」得分最高的一首 */
    private JSONObject neteaseLyrics(String title, String artist, int duration) {
        try {
            String t = title == null ? "" : title.trim();
            if (t.isEmpty()) return null;
            String a = artist == null ? "" : artist.trim();
            String[] queries;
            if (a.isEmpty()) queries = new String[]{t};
            else queries = new String[]{t + " " + a, t};

            String nt = normTitle(t);
            String na = normTitle(a);
            JSONObject bestSong = null;
            double bestScore = 0;
            for (String q : queries) {
                String surl = "https://music.163.com/api/search/get/web?s="
                        + URLEncoder.encode(q, "UTF-8") + "&type=1&limit=10&offset=0";
                String sbody = httpGet(surl, "https://music.163.com/", 9000);
                if (sbody == null) continue;
                JSONObject arr = new JSONObject(sbody);
                JSONObject result = arr.optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null) continue;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject s = songs.getJSONObject(i);
                    double sc = scoreSong(s, t, a, duration);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestSong = s;
                    }
                }
                if (bestScore >= 6.0) break;   // 已经足够好，不用再试别的搜索词
            }
            if (bestSong == null || bestScore < 3.0) return null;   // 宁可没歌词，也不放错歌的词
            long sid = bestSong.optLong("id", 0);
            if (sid <= 0) return null;
            String lurl = "https://music.163.com/api/song/lyric?id=" + sid + "&lv=1&kv=1&tv=-1";
            String lbody = httpGet(lurl, "https://music.163.com/", 9000);
            if (lbody == null) return null;
            JSONObject d = new JSONObject(lbody);
            JSONObject lrc = d.optJSONObject("lrc");
            String synced = lrc == null ? "" : lrc.optString("lyric", "");
            if (synced == null || synced.trim().isEmpty()) return null;
            JSONObject tl = d.optJSONObject("tlyric");
            String trans = tl == null ? "" : tl.optString("lyric", "");
            JSONObject out = new JSONObject();
            out.put("synced", cleanLyrics(synced));
            out.put("plain", "");
            out.put("trans", trans == null ? "" : trans);
            out.put("source", "网易云音乐");
            out.put("title", bestSong.optString("name", title));
            JSONArray artists = bestSong.optJSONArray("artists");
            StringBuilder sb = new StringBuilder();
            if (artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(artists.getJSONObject(i).optString("name", ""));
                }
            }
            out.put("artist", sb.length() > 0 ? sb.toString() : artist);
            Log.i(TAG, "lyrics from netease: " + out.optString("title") + " score=" + bestScore);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "netease lyrics: " + e);
            return null;
        }
    }

    /** 歌名主体：去掉括号内容、「·」后的版本名、常见版本后缀（Live/Remix/伴奏/特别版…） */
    private static String baseTitle(String s) {
        if (s == null) return "";
        String x = s;
        x = x.replaceAll("[（(\\[【].*?[)）\\]】]", "");
        int cut = x.indexOf('·');
        if (cut < 0) cut = x.indexOf('・');
        if (cut > 0) x = x.substring(0, cut);
        x = x.replaceAll("(?i)[\\s\\-_]*(live|remix|dj版|cover|翻唱|伴奏|纯音乐|instrumental|特别版|片段|剪辑版|加速版|降速版|片段版).*$", "");
        x = x.replaceAll("(?i)[\\s\\-_]*[0-9]?\\.[5-9]x.*$", "");
        return normTitle(x);
    }

    /** 歌名相似度（容忍 记念/纪念、括号后缀这类差异）+ 歌手匹配 + 时长接近 */
    private double scoreSong(JSONObject song, String title, String artist, int duration) {
        try {
            String st = song.optString("name", "");
            if (st == null || st.isEmpty()) return 0;
            String bt = baseTitle(title), bst = baseTitle(st);
            double bs = titleSim(bt, bst);
            if (bs < 0.85) return 0;                     // 歌名主体对不上
            String na = normTitle(artist);
            JSONArray artists = song.optJSONArray("artists");
            boolean artistMatched = false;
            if (na != null && !na.isEmpty() && artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    String sa = normTitle(artists.getJSONObject(i).optString("name", ""));
                    if (sa == null || sa.isEmpty()) continue;
                    if (sa.equals(na) || sa.contains(na) || na.contains(sa)) { artistMatched = true; break; }
                }
            }
            // 有歌手信息时，歌手对不上就不要（否则会拿到翻唱/别人版本的歌词）
            if (na != null && !na.isEmpty() && !artistMatched) return 0;
            long durMs = song.optLong("duration", 0);
            if (duration > 0 && durMs > 0) {
                double diff = Math.abs(durMs / 1000.0 - duration);
                if (diff > 12) return 0;                 // 时长差太多，不是同一版本
                return bs * 3.0 + 2.0 + Math.max(0, 2.0 - diff / 12.0);
            }
            return bs * 3.0 + 2.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private JSONObject lrclibLyrics(String title, String artist, int duration) {
        try {
            String q = (title + " " + artist).trim();
            String url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8");
            String body = httpGet(url, null, 12000);
            if (body == null) return null;
            JSONArray arr = new JSONArray(body);
            String bt = baseTitle(title);
            String na = normTitle(artist);
            JSONObject best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                // 严格校验：歌名主体 + 歌手 + 时长，任何一项对不上都跳过
                String trackName = it.optString("trackName", "");
                if (titleSim(bt, baseTitle(trackName)) < 0.85) continue;
                String an = it.optString("artistName", "");
                if (na != null && !na.isEmpty()) {
                    String can = normTitle(an);
                    if (can == null || !(can.contains(na) || na.contains(can))) continue;
                }
                double dsec = it.optDouble("duration", 0);
                if (duration > 0 && dsec > 0 && Math.abs(dsec - duration) > 12) continue;
                if (it.optBoolean("instrumental", false)) { best = it; break; }
                String synced = it.optString("syncedLyrics", "");
                if (synced != null && !synced.trim().isEmpty()) { best = it; break; }
                if (best == null) best = it;
            }
            if (best == null) return null;
            JSONObject out = new JSONObject();
            out.put("synced", best.optString("syncedLyrics", ""));
            out.put("plain", best.optString("plainLyrics", ""));
            out.put("trans", "");
            out.put("source", "LRCLIB");
            out.put("instrumental", best.optBoolean("instrumental", false));
            return out;
        } catch (Exception e) {
            Log.w(TAG, "lrclib lyrics: " + e);
            return null;
        }
    }

    private static boolean isCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String normTitle(String s) {
        if (s == null) return null;
        return s.toLowerCase(Locale.US).replaceAll("[\\s\\-_·・'\"’“”!！?？,，.。:：、/\\\\&+()（）]", "");
    }

    /** 歌名相似度：完全一致=1；包含=0.9；否则按字符重合率（容忍「记念/纪念」这种一字之差） */
    private static double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.9;
        java.util.HashSet<Character> sa = new java.util.HashSet<Character>();
        java.util.HashSet<Character> sb = new java.util.HashSet<Character>();
        for (int i = 0; i < a.length(); i++) sa.add(a.charAt(i));
        for (int i = 0; i < b.length(); i++) sb.add(b.charAt(i));
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int inter = 0;
        for (Character c : sa) if (sb.contains(c)) inter++;
        return inter / (double) Math.max(sa.size(), sb.size());
    }

    /** 去掉「作词：」「制作人：」「Lyrics by」这类 credit 行，保留时间轴与正文。 */
    private static String cleanLyrics(String lrc) {
        if (lrc == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = lrc.split("\n");
        String credit = "(?i)^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:]\\d{1,3})?\\]\\s*"
                + "(作词|作詞|作曲|词|詞|曲|编曲|編曲|填词|填詞|制作人?|製作人?|监制|監製|混音|母带|母帶|"
                + "和声|和聲|配唱|吉他|钢琴|鋼琴|贝斯|貝斯|键盘|鍵盤|演奏|演唱|主唱|"
                + "lyrics?|compos\\w*|writ\\w*|arrang\\w*|produc\\w*|mix\\w*|master\\w*|vocal\\w*)\\s*[：:／/-].*$";
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String t = line.trim();
            // LRC 头部元信息：[ar:歌手] [ti:歌名] [qq:] [offset:0] ...
            if (t.matches("^\\[[a-zA-Z]{1,12}:[^\\]]*\\]\\s*$")) continue;
            if (line.matches(credit)) continue;
            // 时间轴之后的 credit 行（词：/曲：/编曲：/Lyrics by 等）
            String txt = t.replaceFirst("^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]\\s*", "");
            if (txt.matches("(?i)^(作词|作詞|作曲|词|詞|曲|编曲|編曲|填词|填詞|制作人?|製作人?|监制|監製|混音|母带|母帶|"
                    + "和声|和聲|配唱|吉他|钢琴|鋼琴|贝斯|貝斯|键盘|鍵盤|演奏|演唱|主唱|lyrics?|compos\\w*|writ\\w*|"
                    + "arrang\\w*|produc\\w*|mix\\w*|master\\w*|vocal\\w*)\\s*[：:].*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String httpGet(String url, int timeout) {
        return httpGet(url, null, timeout);
    }

    private String httpGet(String url, String referer, int timeout) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) Luvsic/0.1");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ───────────────────────── 曲库自愈 ─────────────────────────

    /** 归一化：小写 + 去掉空格/标点，用于歌名歌手比对 */
    private static String normText(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US);
        t = t.replaceAll("[\\s\\-_·・,，.。;；:：'\"`~!！?？()（）\\[\\]【】{}<>《》/\\\\|+&]", "");
        return t.trim();
    }

    private static double durOf(JSONObject t) {
        return t == null ? 0 : t.optDouble("duration", 0);
    }

    private static String baseNameOf(String name) {
        if (name == null) return "";
        String s = name;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String trackKey(JSONObject t) {
        String a = normText(t.optString("title", ""));
        String b = normText(t.optString("artist", ""));
        if (a.isEmpty()) return "";
        return b.isEmpty() ? a : a + "|" + b;
    }

    /**
     * 曲库自愈：库里保存的 path 若是别的设备（电脑）路径或文件已不存在，
     * 按「文件名 → 标题+歌手+时长 → 纯标题+时长」匹配本机文件并替换 path；
     * 匹配不到的保留条目并标记 offline（未下载）。
     * 曲库为空/全部失效时，把本机扫描结果重新入库（升级、清数据后自动恢复）。
     */
    public JSONObject repairLibrary() {
        JSONObject out = new JSONObject();
        try {
            if (!hasAudioPermission()) {
                out.put("ok", false);
                out.put("msg", "无音频权限");
                return out;
            }
            JSONArray local = scanAllLocal();
            Map<String, JSONObject> byName = new HashMap<String, JSONObject>();
            Map<String, JSONObject> byKey = new HashMap<String, JSONObject>();
            java.util.LinkedHashSet<String> localPaths = new java.util.LinkedHashSet<String>();
            for (int i = 0; i < local.length(); i++) {
                JSONObject t = local.optJSONObject(i);
                if (t == null) continue;
                String p = t.optString("path", "");
                if (!p.isEmpty()) localPaths.add(p);
                String bn = normText(baseNameOf(t.optString("name", "")));
                if (!bn.isEmpty() && !byName.containsKey(bn)) byName.put(bn, t);
                String key = trackKey(t);
                if (!key.isEmpty() && !byKey.containsKey(key)) byKey.put(key, t);
            }

            JSONObject lib = new JSONObject();
            String rawLib = readJsonFile("library.json", "");
            try {
                if (rawLib != null && !rawLib.trim().isEmpty()) lib = new JSONObject(rawLib);
            } catch (Exception ignored) {
            }
            JSONArray old = lib.optJSONArray("tracks");
            if (old == null) old = new JSONArray();

            java.util.LinkedHashSet<String> picks = readPicksSet();
            if (picks == null) picks = new java.util.LinkedHashSet<String>();

            JSONArray keep = new JSONArray();
            java.util.LinkedHashSet<String> used = new java.util.LinkedHashSet<String>();
            int fixed = 0, missing = 0, okCount = 0;
            for (int i = 0; i < old.length(); i++) {
                JSONObject t = old.optJSONObject(i);
                if (t == null) continue;
                String p = t.optString("path", "");
                if (!p.isEmpty() && localPaths.contains(p)) {   // 本机文件还在
                    keep.put(t);
                    used.add(p);
                    picks.add(p);
                    okCount++;
                    continue;
                }
                JSONObject hit = null;
                // ① 文件名精确匹配（去掉扩展名，忽略大小写/标点）
                String bn = normText(baseNameOf(t.optString("name", "")));
                if (!bn.isEmpty()) {
                    JSONObject cand = byName.get(bn);
                    if (cand != null) hit = cand;
                }
                // ② 标题+歌手匹配，时长相差 3 秒内
                if (hit == null) {
                    JSONObject cand = byKey.get(trackKey(t));
                    if (cand != null) {
                        double a = durOf(t), b = durOf(cand);
                        if (a <= 0 || b <= 0 || Math.abs(a - b) <= 3.0) hit = cand;
                    }
                }
                // ③ 纯标题匹配（歌手可能被云端清洗过），时长相差 3 秒内
                if (hit == null) {
                    String nt = normText(t.optString("title", ""));
                    if (!nt.isEmpty()) {
                        for (int j = 0; j < local.length(); j++) {
                            JSONObject cand = local.optJSONObject(j);
                            if (cand == null) continue;
                            if (used.contains(cand.optString("path", ""))) continue;
                            if (!normText(cand.optString("title", "")).equals(nt)) continue;
                            double a = durOf(t), b = durOf(cand);
                            if (a > 0 && b > 0 && Math.abs(a - b) > 3.0) continue;
                            hit = cand;
                            break;
                        }
                    }
                }
                if (hit != null) {
                    String np = hit.optString("path", "");
                    if (!used.contains(np)) {
                        used.add(np);
                        picks.add(np);
                        JSONObject merged = new JSONObject(hit.toString());
                        // 云端清洗过的标题/歌手优先，本机文件名兜底
                        if (!t.optString("title", "").isEmpty()) merged.put("title", t.optString("title"));
                        if (!t.optString("artist", "").isEmpty()) merged.put("artist", t.optString("artist"));
                        if (!t.optString("album", "").isEmpty()) merged.put("album", t.optString("album"));
                        if (t.optBoolean("hasCover", false)) merged.put("hasCover", true);
                        merged.put("path", np);
                        String ln = hit.optString("name", "");
                        if (!ln.isEmpty()) merged.put("name", ln);
                        merged.remove("offline");
                        keep.put(merged);
                        fixed++;
                    }
                    // 已在本机曲库里的歌：说明云端那条只是旧路径，跳过即可，不算丢失
                    okCount++;
                } else {
                    t.put("offline", true);   // 保留条目，标成未下载
                    keep.put(t);
                    missing++;
                }
            }

            boolean rebuild = okCount == 0;
            if (rebuild) {
                keep = new JSONArray();
                picks = new java.util.LinkedHashSet<String>(localPaths);
                for (int i = 0; i < local.length(); i++) {
                    JSONObject t = local.optJSONObject(i);
                    if (t != null) keep.put(t);
                }
            }
            // picks 里勾选过、但曲库被同步覆盖后缺失的歌：补回曲库，避免「更新后曲库变少」
            if (local != null) {
                java.util.HashSet<String> inKeep = new java.util.HashSet<String>();
                for (int i = 0; i < keep.length(); i++) {
                    JSONObject t = keep.optJSONObject(i);
                    if (t != null) inKeep.add(t.optString("path", ""));
                }
                for (int i = 0; i < local.length(); i++) {
                    JSONObject t = local.optJSONObject(i);
                    if (t == null) continue;
                    String lp = t.optString("path", "");
                    if (lp.isEmpty() || inKeep.contains(lp)) continue;
                    if (picks.contains(lp)) {
                        keep.put(t);
                        inKeep.add(lp);
                    }
                }
            }
            // 歌单里本机已存在的歌（比如同步后曲库被覆盖）也补回曲库
            try {
                fixPlaylists(keep, local, picks);
            } catch (Exception ignored) {
            }
            writePicksSet(picks);
            JSONObject newLib = new JSONObject();
            newLib.put("folder", rebuild ? "系统音乐" : lib.optString("folder", ""));
            newLib.put("tracks", keep);
            saveJsonFile("library.json", newLib);
            libraryRepaired = true;

            out.put("ok", true);
            out.put("rebuilt", rebuild);
            out.put("fixed", fixed);
            out.put("offline", missing);
            out.put("tracks", keep.length());
            out.put("msg", "曲库已校验 " + keep.length() + " 首"
                    + (fixed > 0 ? "，跨设备匹配 " + fixed + " 首" : "")
                    + (missing > 0 ? "，未下载 " + missing + " 首" : ""));
        } catch (Exception e) {
            Log.w(TAG, "repairLibrary", e);
            try {
                out.put("ok", false);
                out.put("msg", "曲库自愈失败：" + e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ───────────── 歌名/歌手清洗（1782994xxxx_PsCopy_ 之类的脏名）─────────────
    private static final String VW_CN =
            "完整版?|纯享版?|现场版?|dj版?|3d环绕?|环绕|高清|mv|官方|重制版?|remix|cover|翻唱|"
            + "伴奏|instrumental|demo|钢琴版?|吉他版?|古筝版?|空灵版?|空灵治愈旋律|治愈|慢摇|"
            + "加长版?|串烧|原版?|新版?|旧版?|抖音|快手|车载|热歌|升调|降调|女声|男声|纯音乐|"
            + "无损|立体声|修复|深度|重低音|完整";
    private static final java.util.regex.Pattern P_BRACKET = java.util.regex.Pattern.compile(
            "[（(]\\s*[^（()）]*?(?:" + VW_CN + ")[^（()）]*?\\s*[)）]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_TAIL_CN = java.util.regex.Pattern.compile(
            "[\\s\\-·・_,，]\\s*(?:" + VW_CN + ")\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_TAIL_EN = java.util.regex.Pattern.compile(
            "[\\s\\-·・]\\s*(dj|3d|mv|hd|hq|tc|live|remix|cover|demo|inst)\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_PS_PREFIX = java.util.regex.Pattern.compile(
            "^\\d{8,}[\\s_\\-]*PsCopy[\\s_\\-]*", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_PS_TOKEN = java.util.regex.Pattern.compile(
            "[\\s_\\-]+PsCopy[\\s_\\-]+", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_NUM_PREFIX = java.util.regex.Pattern.compile(
            "^\\d{8,}[\\s_\\-]+");
    private static final java.util.regex.Pattern P_SINGER_TITLE = java.util.regex.Pattern.compile(
            "^[^-]{1,40}-(.+)$");

    /** 清洗歌名：剥掉 1782994xxxx_PsCopy_ 前缀、版本括号/尾缀、年份 */
    static String cleanTitle(String title, String path) {
        String t = title == null ? "" : title.trim();
        String orig = t;
        t = P_PS_PREFIX.matcher(t).replaceAll("");
        t = P_PS_TOKEN.matcher(t).replaceAll(" ");
        t = P_NUM_PREFIX.matcher(t).replaceAll("");
        String prev;
        int guard = 0;
        do {
            prev = t;
            t = P_BRACKET.matcher(t).replaceAll("");
        } while (!prev.equals(t) && ++guard < 10);
        guard = 0;
        do {
            prev = t;
            t = P_TAIL_CN.matcher(t).replaceAll("");
            t = P_TAIL_EN.matcher(t).replaceAll("");
        } while (!prev.equals(t) && ++guard < 10);
        t = t.replaceAll("[·・\\-—_\\s]+(19|20)\\d{2}\\s*$", "");
        if (t.length() > 6) t = t.replaceAll("(19|20)\\d{2}$", "");
        if (path != null && path.contains("_PsCopy_")) {
            java.util.regex.Matcher mm = P_SINGER_TITLE.matcher(t);
            if (mm.matches() && mm.group(1).trim().length() >= 2) t = mm.group(1).trim();
        }
        t = t.replaceAll("^[\\s\\-–—·・_.,，、|/\\\\]+", "");
        t = t.replaceAll("[\\s\\-–—·・_.,，、|/\\\\]+$", "");
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (t.length() < 2) return orig;
        return t;
    }

    /** 清洗歌手：去掉 1782994xxxx_PsCopy_ 之类脏前缀 */
    static String cleanArtist(String artist, String path) {
        String a = artist == null ? "" : artist.trim();
        if (a.isEmpty()) return "";
        a = P_PS_PREFIX.matcher(a).replaceAll("");
        a = P_PS_TOKEN.matcher(a).replaceAll(" ");
        a = P_NUM_PREFIX.matcher(a).replaceAll("");
        a = a.replaceAll("\\s{2,}", " ").trim();
        a = a.replaceAll("^[\\s\\-–—·・_.,，、|/\\\\]+", "");
        a = a.replaceAll("[\\s\\-–—·・_.,，、|/\\\\]+$", "");
        return a;
    }

    // ───────────────────────── 歌单跨设备匹配 ─────────────────────────

    /**
     * 歌单自愈：歌单条目存的也是别的设备路径。
     * 本机有的歌 → 换成本机文件路径；本机没有的 → 保留条目并标 offline（UI 显示「未下载」）。
     * 条目兼容旧格式（纯字符串路径）和新格式（JSONObject）。
     */
    private void fixPlaylists(JSONArray tracks, JSONArray local, java.util.Set<String> picks) {
        try {
            java.util.HashSet<String> libPaths = new java.util.HashSet<String>();
            java.util.HashMap<String, JSONObject> localByPath = new java.util.HashMap<String, JSONObject>();
            if (tracks != null) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject t = tracks.optJSONObject(i);
                    if (t != null) libPaths.add(t.optString("path", ""));
                }
            }
            if (local != null) {
                for (int i = 0; i < local.length(); i++) {
                    JSONObject t = local.optJSONObject(i);
                    if (t != null && !t.optString("path", "").isEmpty()) {
                        localByPath.put(t.optString("path", ""), t);
                    }
                }
            }
            JSONObject d = new JSONObject();
            String raw = readJsonFile("playlists.json", "");
            try {
                if (raw != null && !raw.trim().isEmpty()) d = new JSONObject(raw);
            } catch (Exception ignored) {
            }
            JSONArray pls = d.optJSONArray("playlists");
            if (pls == null) return;

            Map<String, JSONObject> byName = new HashMap<String, JSONObject>();
            Map<String, JSONObject> byKey = new HashMap<String, JSONObject>();
            if (tracks != null) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject t = tracks.optJSONObject(i);
                    if (t == null) continue;
                    String p = t.optString("path", "");
                    String bn = normText(baseNameOf(p.isEmpty() ? t.optString("name", "") : p));
                    if (!bn.isEmpty() && !byName.containsKey(bn)) byName.put(bn, t);
                    String key = trackKey(t);
                    if (!key.isEmpty() && !byKey.containsKey(key)) byKey.put(key, t);
                }
            }
            boolean changed = false;
            for (int i = 0; i < pls.length(); i++) {
                JSONObject pl = pls.optJSONObject(i);
                if (pl == null) continue;
                JSONArray items = pl.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    Object rawItem = items.get(j);
                    JSONObject o;
                    if (rawItem instanceof JSONObject) {
                        o = new JSONObject(((JSONObject) rawItem).toString());
                    } else {
                        String s = String.valueOf(rawItem);
                        o = new JSONObject();
                        o.put("path", s);
                        o.put("name", baseNameOf(s));
                    }
                    String p = o.optString("path", "");
                    if (!p.isEmpty() && new File(p).exists()) {
                        if (o.has("offline")) {
                            o.remove("offline");
                            changed = true;
                        }
                        items.put(j, o);
                        // 本机有这首歌但曲库里没有（比如同步后曲库被覆盖）→ 补进曲库
                        if (tracks != null && !libPaths.contains(p)) {
                            JSONObject lt = localByPath.get(p);
                            if (lt == null) {
                                // MediaStore 没把它标成音乐（IS_MUSIC=0）时扫不到，直接读文件补
                                try {
                                    lt = buildTrack(p, o.optString("title", ""), o.optString("artist", ""), false);
                                } catch (Exception ignored) {
                                }
                            }
                            if (lt != null) {
                                tracks.put(lt);
                                libPaths.add(p);
                                if (picks != null) picks.add(p);
                            }
                        }
                        continue;
                    }
                    JSONObject hit = null;
                    String bn = normText(baseNameOf(p.isEmpty() ? o.optString("name", "") : p));
                    if (!bn.isEmpty()) hit = byName.get(bn);
                    if (hit == null) hit = byKey.get(trackKey(o));
                    if (hit == null && !o.optString("title", "").isEmpty() && tracks != null) {
                        String nt = normText(o.optString("title", ""));
                        for (int k = 0; k < tracks.length(); k++) {
                            JSONObject t = tracks.optJSONObject(k);
                            if (t != null && normText(t.optString("title", "")).equals(nt)) {
                                hit = t;
                                break;
                            }
                        }
                    }
                    if (hit != null && !hit.optString("path", "").isEmpty()
                            && new File(hit.optString("path", "")).exists()) {
                        o.put("path", hit.optString("path", ""));
                        o.put("name", hit.optString("name", o.optString("name", "")));
                        if (!hit.optString("title", "").isEmpty() && o.optString("title", "").isEmpty())
                            o.put("title", hit.optString("title", ""));
                        if (!hit.optString("artist", "").isEmpty() && o.optString("artist", "").isEmpty())
                            o.put("artist", hit.optString("artist", ""));
                        o.remove("offline");
                        changed = true;
                    } else if (hit != null) {
                        o.put("path", hit.optString("path", p));
                        if (!hit.optString("title", "").isEmpty()) o.put("title", hit.optString("title", ""));
                        if (!hit.optString("artist", "").isEmpty()) o.put("artist", hit.optString("artist", ""));
                        o.put("offline", true);
                        changed = true;
                    } else {
                        o.put("offline", true);
                        changed = true;
                    }
                    items.put(j, o);
                }
            }
            if (changed) saveJsonFile("playlists.json", d);
        } catch (Exception e) {
            Log.w(TAG, "fixPlaylists", e);
        }
    }

    // ───────────────────────── 歌曲信息编辑 / 补全歌手 ─────────────────────────

    /** 改歌名/歌手/专辑：写曲库索引 + 尽力写系统媒体库标签（Android 10+ 对非本应用文件可能拒绝） */
    public JSONObject updateTrackMeta(String path, String title, String artist, String album) {
        JSONObject out = new JSONObject();
        try {
            JSONObject lib = new JSONObject(readJsonFile("library.json", "{\"tracks\":[]}"));
            JSONArray tr = lib.optJSONArray("tracks");
            JSONObject hit = null;
            for (int i = 0; tr != null && i < tr.length(); i++) {
                JSONObject t = tr.optJSONObject(i);
                if (t != null && path.equals(t.optString("path", ""))) {
                    hit = t;
                    break;
                }
            }
            if (hit == null) {
                out.put("ok", false);
                out.put("msg", "曲库里没有这首歌");
                return out;
            }
            String nt = (title == null || title.trim().isEmpty()) ? hit.optString("title", "") : title.trim();
            String na = artist == null ? "" : artist.trim();
            String nal = album == null ? "" : album.trim();
            hit.put("title", nt);
            hit.put("artist", na);
            hit.put("album", nal);
            saveJsonFile("library.json", lib);
            boolean tagOk = false;
            try {
                tagOk = updateMediaStoreTags(path, nt, na, nal);
            } catch (Exception ignored) {
            }
            out.put("ok", true);
            out.put("msg", tagOk ? "已保存（含系统媒体库）" : "已保存到曲库");
            out.put("track", hit);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "保存失败：" + e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private boolean updateMediaStoreTags(String path, String title, String artist, String album) {
        ContentValues v = new ContentValues();
        if (title != null && !title.isEmpty()) v.put(MediaStore.Audio.Media.TITLE, title);
        v.put(MediaStore.Audio.Media.ARTIST, artist == null ? "" : artist);
        v.put(MediaStore.Audio.Media.ALBUM, album == null ? "" : album);
        int n = ctx.getContentResolver().update(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v,
                MediaStore.Audio.Media.DATA + "=?", new String[]{path});
        return n > 0;
    }

    private volatile JSONObject metaFixState = new JSONObject();
    private Thread metaFixThread = null;

    /** 联网补全未知歌手：按歌名搜索，标题相似 + 时长接近才采用 */
    public JSONObject metaFixStart() {
        JSONObject out = new JSONObject();
        try {
            if (metaFixThread != null && metaFixThread.isAlive()) {
                out.put("ok", true);
                out.put("msg", "已经在补全中");
                return out;
            }
            final JSONObject st = new JSONObject();
            st.put("running", true);
            st.put("done", 0);
            st.put("total", 0);
            st.put("fixed", 0);
            st.put("msg", "准备中");
            metaFixState = st;
            metaFixThread = new Thread(new Runnable() {
                @Override
                public void run() { metaFixWorker(st); }
            }, "luvsic-metafix");
            metaFixThread.setDaemon(true);
            metaFixThread.start();
            out.put("ok", true);
            out.put("msg", "开始补全");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", "启动失败：" + e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public JSONObject metaFixStatus() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("state", metaFixState == null ? new JSONObject() : metaFixState);
        } catch (Exception ignored) {
        }
        return out;
    }

    private void metaFixWorker(JSONObject st) {
        try {
            JSONObject lib = new JSONObject(readJsonFile("library.json", "{\"tracks\":[]}"));
            JSONArray tr = lib.optJSONArray("tracks");
            java.util.List<JSONObject> todo = new ArrayList<JSONObject>();
            for (int i = 0; tr != null && i < tr.length(); i++) {
                JSONObject t = tr.optJSONObject(i);
                if (t == null) continue;
                String a = t.optString("artist", "").trim();
                if (a.isEmpty() || a.equals("未知艺术家") || a.equals("未知歌手")) todo.add(t);
            }
            st.put("total", todo.size());
            st.put("msg", "补全中");
            int done = 0, fixed = 0;
            for (JSONObject t : todo) {
                done++;
                String kw = t.optString("title", "").trim();
                if (!kw.isEmpty()) {
                    try {
                        JSONObject r = Netease.search(kw, 0, 10);
                        JSONArray songs = r.optJSONArray("songs");
                        JSONObject best = null;
                        double bs = -1;
                        for (int i = 0; songs != null && i < songs.length(); i++) {
                            JSONObject song = songs.optJSONObject(i);
                            if (song == null) continue;
                            double sim = titleSim(normText(t.optString("title", "")),
                                    normText(song.optString("title", "")));
                            if (sim < 0.6) continue;
                            double d1 = t.optDouble("duration", 0);
                            double d2 = song.optDouble("duration", 0);
                            if (d1 > 0 && d2 > 0 && Math.abs(d1 - d2) > 12) continue;
                            if (sim > bs) {
                                bs = sim;
                                best = song;
                            }
                        }
                        if (best != null) {
                            String ar = cleanArtist(best.optString("artist", ""), "");
                            if (!ar.isEmpty()) {
                                t.put("artist", ar);
                                if (t.optString("album", "").trim().isEmpty()) {
                                    t.put("album", best.optString("album", ""));
                                }
                                fixed++;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                st.put("done", done);
                st.put("fixed", fixed);
            }
            saveJsonFile("library.json", lib);
            st.put("running", false);
            st.put("done", done);
            st.put("fixed", fixed);
            st.put("msg", "完成：补全 " + fixed + " 首");
        } catch (Exception e) {
            try {
                st.put("running", false);
                st.put("msg", "出错：" + e);
            } catch (Exception ignored) {
            }
        }
    }

    // ───────────────────────── 工具 ─────────────────────────
    private static String safeStr(String s) {
        return s == null || s.equals("<unknown>") ? "" : s;
    }

    private static String readFileText(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(s.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "x" + s.hashCode();
        }
    }

    static String ok() {
        return "{\"ok\":true}";
    }

    static String err(String msg) {
        return "{\"ok\":false,\"msg\":\"" + WebServer.esc(msg) + "\"}";
    }
}
