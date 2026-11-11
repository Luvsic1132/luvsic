package com.luvsic.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 悬浮歌词：浮在其他应用上方的一条歌词，可拖动、可一键关闭。 */
public class LyricOverlay {
    private static final String TAG = "LuvsicOverlay";
    private static final int TYPE = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private TextView lyric;
    private TextView sub;
    private boolean showing = false;
    private WindowManager.LayoutParams lp;
    private float downX, downY;
    private int startX, startY;
    private int lyricColor = 0xFFFAE9CE;   // 默认暖金，可由设置里的歌词颜色改
    private int lyricSize = 16;            // 字号 sp，0=默认16

    public LyricOverlay(Activity a) {
        this.act = a;
    }

    /** 修改浮窗歌词颜色（须在主线程调用） */
    public void setColor(int color) {
        lyricColor = color;
        if (lyric != null) {
            lyric.setTextColor(color);
            lyric.setShadowLayer(8f, 0f, 0f, (color & 0x00FFFFFF) | 0xAA000000);
        }
    }

    /** 修改浮窗歌词字号（sp，<=0 视为默认 16） */
    public void setSize(int sp) {
        lyricSize = sp > 0 ? sp : 16;
        if (lyric != null) lyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, lyricSize);
    }

    public boolean isShowing() {
        return showing;
    }

    public boolean hasPermission() {
        return Settings.canDrawOverlays(act);
    }

    public void requestPermission() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(i);
                } catch (Exception e) {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** 返回 need_permission / on / off */
    public String toggle() {
        if (!hasPermission()) {
            requestPermission();
            return "need_permission";
        }
        final boolean want = !showing;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (want) show();
                else hide();
            }
        });
        return want ? "on" : "off";
    }

    private void show() {
        if (showing) return;
        try {
            wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
            int padH = dp(16), padV = dp(10);
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(padH, padV, padH, padV);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD90B0A10);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), 0x55E4C29A);
            box.setBackground(bg);
            box.setElevation(dp(8));

            lyric = new TextView(act);
            lyric.setText("♪ 歌词准备中");
            lyric.setTextColor(lyricColor);
            lyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, lyricSize);
            lyric.setShadowLayer(8f, 0f, 0f, (lyricColor & 0x00FFFFFF) | 0xAA000000);
            lyric.setMaxLines(3);
            lyric.setGravity(Gravity.CENTER);
            box.addView(lyric);

            sub = new TextView(act);
            sub.setText("");
            sub.setTextColor(0x99F3EEE7);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            sub.setPadding(0, dp(4), 0, 0);
            box.addView(sub);

            TextView close = new TextView(act);
            close.setText("×");
            close.setTextColor(0x88F3EEE7);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            close.setPadding(dp(8), dp(2), dp(2), 0);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.END;
            box.addView(close, clp);

            lp = new WindowManager.LayoutParams(
                    dp(320),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(24);
            lp.y = dp(160);

            box.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startX = lp.x;
                            startY = lp.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = startX + (int) (e.getRawX() - downX);
                            lp.y = startY + (int) (e.getRawY() - downY);
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Exception ignored) {
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            root = box;
            wm.addView(root, lp);
            showing = true;
            Log.i(TAG, "overlay shown");
        } catch (Exception e) {
            Log.w(TAG, "show failed", e);
        }
    }

    private void hide() {
        try {
            if (root != null && wm != null) wm.removeView(root);
        } catch (Exception ignored) {
        }
        root = null;
        lyric = null;
        sub = null;
        showing = false;
    }

    /** 由 JS 桥推送：{"text","title","artist","playing"} */
    public void update(final String json) {
        if (!showing || json == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    String t = o.optString("text", "");
                    String title = o.optString("title", "");
                    String artist = o.optString("artist", "");
                    boolean playing = o.optBoolean("playing", false);
                    if (lyric != null) lyric.setText(t.isEmpty() ? (playing ? "♪ 正在播放" : "♪ 已暂停") : t);
                    if (sub != null) {
                        StringBuilder sb = new StringBuilder();
                        if (!title.isEmpty()) sb.append(title);
                        if (!artist.isEmpty()) {
                            if (sb.length() > 0) sb.append(" · ");
                            sb.append(artist);
                        }
                        sub.setText(sb.toString());
                    }
                    if (root != null) root.requestLayout();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }
}
