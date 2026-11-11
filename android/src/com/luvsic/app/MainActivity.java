package com.luvsic.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Luvsic 安卓外壳：启动本地 HTTP 服务（WebServer），再用 WebView 打开它，
 * 这样界面里的 /api、/audio、/cover 请求都走和桌面版一样的路径。
 */
public class MainActivity extends Activity {
    /** 服务/看门狗通过它把「上一首/播放/下一首」转给网页 */
    private static MainActivity INSTANCE = null;

    /** 供 PlaybackService 的通知按钮和看门狗调用：让网页执行媒体动作 */
    public static void mediaAction(final String action, final long seekMs) {
        final MainActivity a = INSTANCE;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String js = "(function(){try{var f=document.getElementById('frame');"
                            + "if(f&&f.contentWindow&&f.contentWindow.__LUV&&f.contentWindow.__LUV.mediaAction){"
                            + "f.contentWindow.__LUV.mediaAction('" + action + "'," + seekMs + ");}"
                            + "}else if(window.__LUV&&window.__LUV.mediaAction){"
                            + "window.__LUV.mediaAction('" + action + "'," + seekMs + ");}"
                            + "}catch(e){}})()";
                    a.web.evaluateJavascript(js, null);
                    // 后台 WebView 可能被系统冻结，第一次注入会延迟/丢失；
                    // play / pause 是幂等操作，1.5 秒后补发一次，减少「点了没反应」
                    if ("play".equals(action) || "pause".equals(action)) {
                        a.web.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    a.web.evaluateJavascript(js, null);
                                } catch (Exception ignored) {
                                }
                            }
                        }, 1500);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }
    private static final String TAG = "Luvsic";
    private static final int REQ_MEDIA = 1001;
    private static final int REQ_IMAGE = 1002;
    private static final int REQ_FILE_CHOOSER = 1003;

    /** WebView 内 <input type=file> 的回调（手机端选头像用） */
    private android.webkit.ValueCallback<android.net.Uri[]> fileChooserCallback;

    private WebView web;
    private WebServer server;
    private LyricOverlay overlay;
    private int lyricColor = 0xFFFAE9CE;   // 悬浮歌词颜色，随设置同步
    private int lyricSize = 0;             // 悬浮歌词字号 sp，0=默认
    private MediaNotif media;              // 系统媒体控制（通知栏/锁屏/控制中心）

    @Override
    protected void onResume() {
        super.onResume();
        INSTANCE = this;
        try {
            if (web != null) {
                web.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            web.evaluateJavascript(
                                    "(function(){try{var f=document.getElementById('frame');"
                                            + "if(f&&f.contentWindow&&f.contentWindow.__LUV&&f.contentWindow.__LUV.afterResume){"
                                            + "f.contentWindow.__LUV.afterResume();}"
                                            + "}else if(window.__LUV&&window.__LUV.afterResume){window.__LUV.afterResume();}"
                                            + "}catch(e){}})()", null);
                        } catch (Exception ignored) {
                        }
                    }
                }, 500);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0B0A10);
        getWindow().setNavigationBarColor(0xFF0B0A10);

        requestMediaPermission();

        server = new WebServer(this);
        server.start();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0A10);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "onReceivedError " + errorCode + " " + description + " " + failingUrl);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.i(TAG + "JS", cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             android.webkit.ValueCallback<android.net.Uri[]> callback,
                                             FileChooserParams params) {
                // 给 <input type=file> 用的系统选择器（选头像）
                if (fileChooserCallback != null) {
                    try { fileChooserCallback.onReceiveValue(null); } catch (Exception ignored) {}
                }
                fileChooserCallback = callback;
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                    i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(android.content.Intent.createChooser(i, "选择图片"), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "onShowFileChooser", e);
                    fileChooserCallback = null;
                    return false;
                }
            }
        });
        overlay = new LyricOverlay(this);
        overlay.setColor(lyricColor);
        overlay.setSize(lyricSize);
        media = new MediaNotif(this);
        media.setSink(new MediaNotif.Sink() {
            @Override
            public void onAction(final String action, final long seekMs) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (web == null) return;
                        String js = "(function(){try{var f=document.getElementById('frame');"
                                + "if(f&&f.contentWindow&&f.contentWindow.__LUV&&f.contentWindow.__LUV.mediaAction){"
                                + "f.contentWindow.__LUV.mediaAction('" + action + "'," + seekMs + ");}"
                                + "}catch(e){}})()";
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        });
        web.addJavascriptInterface(new NativeBridge(), "LuvsicNative");
        hideBars();
        setContentView(web);
        String url = buildUrl(getIntent());
        Log.i(TAG, "load " + url);
        web.loadUrl(url);
        Log.i(TAG, "server on port " + server.getPort());
    }

    /**
     * 拼启动 URL。支持深链（也方便 adb 调试/自动化）：
     *   am start -n com.luvsic.app/.MainActivity --es tab search --es q 晴天
     *   am start ... --es tab search --es batch <酷狗歌单链接> --es limit 2
     */
    private String buildUrl(android.content.Intent intent) {
        String tab = null;
        String deepQ = null;
        String deepBatch = null;
        String deepLimit = null;
        try {
            tab = intent == null ? null : intent.getStringExtra("tab");
            deepQ = intent == null ? null : intent.getStringExtra("q");
            deepBatch = intent == null ? null : intent.getStringExtra("batch");
            deepLimit = intent == null ? null : intent.getStringExtra("limit");
        } catch (Exception ignored) {
        }
        String url = "http://127.0.0.1:" + server.getPort() + "/android.html";
        StringBuilder qs = new StringBuilder();
        appendParam(qs, "q", deepQ);
        appendParam(qs, "batch", deepBatch);
        appendParam(qs, "limit", deepLimit);
        if (qs.length() > 0) url += "?" + qs;
        if (tab != null && !tab.isEmpty()) url += "#" + tab;
        return url;
    }

    private static void appendParam(StringBuilder qs, String key, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            if (qs.length() > 0) qs.append('&');
            qs.append(key).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            if (web != null) {
                // 只有「深链」参数（tab/q/batch/limit）才需要重新加载页面。
                // 普通从桌面图标 / 通知栏回到前台时绝对不能 loadUrl：WebView 重载会销毁
                // 正在后台播放的 <audio>，导致「重新进入 App 时播放被暂停」。
                String tab = intent == null ? null : intent.getStringExtra("tab");
                String deepQ = intent == null ? null : intent.getStringExtra("q");
                String deepBatch = intent == null ? null : intent.getStringExtra("batch");
                String deepLimit = intent == null ? null : intent.getStringExtra("limit");
                boolean deep = (tab != null && !tab.isEmpty())
                        || (deepQ != null && !deepQ.isEmpty())
                        || (deepBatch != null && !deepBatch.isEmpty())
                        || (deepLimit != null && !deepLimit.isEmpty());
                if (deep) {
                    String url = buildUrl(intent);
                    Log.i(TAG, "newIntent load " + url);
                    web.loadUrl(url);
                } else {
                    Log.i(TAG, "newIntent: keep webview (播放继续)");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onNewIntent", e);
        }
    }

    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            java.util.ArrayList<String> need = new java.util.ArrayList<String>();
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, REQ_MEDIA);
            }
        } else {
            // Android 9 及以下：写 /sdcard/Music/Luvsic 还要写权限
            String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
            boolean missing = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing = true;
            }
            if (missing) requestPermissions(perms, REQ_MEDIA);
        }
    }

    private void hideBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    /** 给网页用的桥：悬浮歌词 / 选图换壁纸 */
    public class NativeBridge {
        @android.webkit.JavascriptInterface
        public void setLyricSize(int sp) {
            lyricSize = sp > 0 ? sp : 0;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (overlay != null) overlay.setSize(lyricSize);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void setLyricColor(final String hex) {
            int c = 0xFFFAE9CE;
            try {
                if (hex != null && hex.matches("#?[0-9a-fA-F]{6}")) {
                    c = 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
                }
            } catch (Exception ignored) {
            }
            lyricColor = c;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (overlay != null) overlay.setColor(lyricColor);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @android.webkit.JavascriptInterface
        public void setLyric(String json) {
            if (overlay != null) overlay.update(json);
        }

        /** 网页把当前播放状态推给系统媒体卡片（通知栏/锁屏/控制中心） */
        @android.webkit.JavascriptInterface
        public void updatePlayback(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                if (media != null) media.update(o);
                // 播放中：拉起前台服务保活，避免切后台被系统冻结/回收
                try {
                    android.content.Intent svc = new android.content.Intent(MainActivity.this, PlaybackService.class);
                    svc.putExtra("title", o.optString("title", ""));
                    svc.putExtra("artist", o.optString("artist", ""));
                    svc.putExtra("playing", o.optBoolean("playing", false));
                    svc.putExtra("positionMs", o.optLong("positionMs", 0));
                    svc.putExtra("durationMs", o.optLong("durationMs", 0));
                    svc.putExtra("timeoutMin", o.optInt("timeoutMin", 30));
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                Log.w(TAG, "updatePlayback", e);
            }
        }

        @android.webkit.JavascriptInterface
        public String toggleOverlay() {
            if (overlay == null) return "unsupported";
            return overlay.toggle();
        }

        @android.webkit.JavascriptInterface
        public boolean isOverlayOn() {
            return overlay != null && overlay.isShowing();
        }

        @android.webkit.JavascriptInterface
        public void pickImage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Intent it = new android.content.Intent(
                                android.content.Intent.ACTION_GET_CONTENT);
                        it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        it.setType("image/*");
                        startActivityForResult(it, REQ_IMAGE);
                    } catch (Exception e) {
                        try {
                            android.content.Intent it2 = new android.content.Intent(
                                    android.content.Intent.ACTION_OPEN_DOCUMENT);
                            it2.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                            it2.setType("image/*");
                            startActivityForResult(it2, REQ_IMAGE);
                        } catch (Exception e2) {
                            Log.w(TAG, "pickImage failed", e2);
                        }
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "onActivityResult req=" + requestCode + " res=" + resultCode + " data=" + (data != null && data.getData() != null));
        if (requestCode == REQ_FILE_CHOOSER) {
            // 把选中的图片回传给 WebView 里的 <input type=file>
            android.webkit.ValueCallback<android.net.Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            if (cb != null) {
                android.net.Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    uris = new android.net.Uri[]{data.getData()};
                }
                try {
                    cb.onReceiveValue(uris);
                } catch (Exception e) {
                    Log.w(TAG, "fileChooserCallback", e);
                }
            }
            return;
        }
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int scale = 1;
            while (o.outWidth / (scale * 2) >= 1600) scale *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = scale;
            in = getContentResolver().openInputStream(data.getData());
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            if (bmp == null) { toastJs("图片读取失败"); return; }
            java.io.File f = new java.io.File(getFilesDir(), "custom_wall.jpg");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fos);
            fos.close();
            bmp.recycle();
            Log.i(TAG, "custom wall saved " + f.length());
            // 界面实际跑在内嵌 iframe 里，要在外壳页面里转发进 iframe，同时让外壳顶/底栏刷新
            String js = "(function(){"
                    + "try{var f=document.getElementById('frame');"
                    + "if(f&&f.contentWindow&&f.contentWindow.__LUV&&f.contentWindow.__LUV.applyCustomWall){"
                    + "f.contentWindow.__LUV.applyCustomWall();}}catch(e){}"
                    + "try{if(window.__SHELL_WALL_REFRESH)window.__SHELL_WALL_REFRESH();}catch(e){}"
                    + "})()";
            web.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.w(TAG, "onActivityResult", e);
            toastJs("换壁纸失败");
        }
    }

    private void toastJs(String msg) {
        try {
            String js = "if(window.__LUV_TOAST)window.__LUV_TOAST(" + org.json.JSONObject.quote(msg) + ")";
            web.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            // 没有可回退的页面时退回桌面，而不是直接杀掉 App
            moveTaskToBack(true);
        }
    }

    /** 请求把 App 加入电池优化白名单（华为/荣耀/小米等会杀后台） */
    private void requestIgnoreBatteryOptimizations() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("luvsic", MODE_PRIVATE);
            if (sp.getBoolean("asked_battery", false)) return;
            sp.edit().putBoolean("asked_battery", true).apply();
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}
