package com.luvsic.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 系统媒体控制：MediaSession + MediaStyle 通知。
 * 网页把当前歌曲/播放状态推过来，系统就把它显示在通知栏 / 锁屏 / 控制中心；
 * 用户点卡片上的按钮时，通过回调叫网页执行 上一首 / 播放暂停 / 下一首 / 跳转。
 */
public final class MediaNotif {
    private static final String TAG = "LuvsicMedia";
    private static final String CHANNEL = "luvsic_playback";
    private static final int NOTIF_ID = 0x4C56;

    private static final String A_PLAY = "com.luvsic.app.MEDIA_PLAY";
    private static final String A_PAUSE = "com.luvsic.app.MEDIA_PAUSE";
    private static final String A_NEXT = "com.luvsic.app.MEDIA_NEXT";
    private static final String A_PREV = "com.luvsic.app.MEDIA_PREV";
    private static final String A_STOP = "com.luvsic.app.MEDIA_STOP";

    public interface Sink {
        /** action: play / pause / next / prev / stop / seek；seekMs 仅 seek 时有效 */
        void onAction(String action, long seekMs);
    }

    private final Activity act;
    private final NotificationManager nm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private Sink sink;

    private String title = "";
    private String artist = "";
    private String coverPath = "";
    private String coverLoadedFor = "";
    private Bitmap cover;
    private boolean playing = false;
    private long positionMs = 0;
    private long durationMs = 0;
    private boolean active = false;
    private BroadcastReceiver receiver;

    public MediaNotif(Activity a) {
        this.act = a;
        this.nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void setSink(Sink s) {
        this.sink = s;
    }

    public void init() {
        if (session != null) return;
        // 前台服务里已有会话（媒体样式通知用它），这里复用，避免系统里出现两个媒体卡片
        try {
            android.media.session.MediaSession shared = PlaybackService.sharedSession();
            if (shared != null) {
                session = shared;
                session.setCallback(new MediaSession.Callback() {
                    @Override
                    public void onPlay() { emit("play", -1); }
                    @Override
                    public void onPause() { emit("pause", -1); }
                    @Override
                    public void onSkipToNext() { emit("next", -1); }
                    @Override
                    public void onSkipToPrevious() { emit("prev", -1); }
                    @Override
                    public void onSeekTo(long pos) { emit("seek", pos); }
                    @Override
                    public void onStop() { emit("stop", -1); }
                });
                session.setActive(true);
                return;
            }
        } catch (Exception ignored) {
        }
        session = new MediaSession(act, "LuvsicSession");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { emit("play", -1); }
            @Override
            public void onPause() { emit("pause", -1); }
            @Override
            public void onSkipToNext() { emit("next", -1); }
            @Override
            public void onSkipToPrevious() { emit("prev", -1); }
            @Override
            public void onSeekTo(long pos) { emit("seek", pos); }
            @Override
            public void onStop() { emit("stop", -1); }
        });
        session.setActive(true);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "播放控制",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(A_PLAY);
        f.addAction(A_PAUSE);
        f.addAction(A_NEXT);
        f.addAction(A_PREV);
        f.addAction(A_STOP);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent in) {
                String a = in.getAction();
                if (A_PLAY.equals(a)) emit("play", -1);
                else if (A_PAUSE.equals(a)) emit("pause", -1);
                else if (A_NEXT.equals(a)) emit("next", -1);
                else if (A_PREV.equals(a)) emit("prev", -1);
                else if (A_STOP.equals(a)) emit("stop", -1);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                act.registerReceiver(receiver, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver", e);
        }
    }

    private void emit(String action, long seekMs) {
        if (sink != null) sink.onAction(action, seekMs);
    }

    /** 由网页调用：更新当前播放信息并刷新系统卡片 */
    public void update(JSONObject o) {
        if (o == null) return;
        title = o.optString("title", "");
        artist = o.optString("artist", "");
        String cp = o.optString("coverPath", "");
        playing = o.optBoolean("playing", false);
        positionMs = o.optLong("positionMs", 0);
        durationMs = o.optLong("durationMs", 0);
        if (cp != null && !cp.equals(coverPath)) {
            coverPath = cp;
        }
        if (!playing && positionMs <= 0) {
            // 停止/切换时清空卡片
            hide();
            return;
        }
        active = true;
        init();
        publish();
        loadCoverIfNeeded();
    }

    public void hide() {
        active = false;
        // 会话由 PlaybackService 持有，这里不释放（否则会连带停掉保活服务的媒体卡片）
    }

    private void loadCoverIfNeeded() {
        if (coverPath == null || coverPath.isEmpty()) {
            cover = null;
            return;
        }
        if (coverPath.equals(coverLoadedFor)) return;
        final String want = coverPath;
        coverLoadedFor = want;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = fetchCover(want);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!want.equals(coverPath)) return;
                        cover = bmp;
                        if (active) publish();
                    }
                });
            }
        }, "luvsic-cover").start();
    }

    private Bitmap fetchCover(String path) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            String url = "http://127.0.0.1:18756/cover?p=" + URLEncoder.encode(path, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return null;
            is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;
            Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (raw == null) return null;
            int max = 512;
            int w = raw.getWidth(), h = raw.getHeight();
            if (w > max || h > max) {
                float s = Math.min(max / (float) w, max / (float) h);
                Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.max(1, (int) (w * s)),
                        Math.max(1, (int) (h * s)), true);
                if (scaled != raw) raw.recycle();
                return scaled;
            }
            return raw;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(action);
        i.setPackage(act.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(act, code, i, flags);
    }

    private void publish() {
        try {
            boolean isPlaying = playing;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(act, CHANNEL);
            else b = new Notification.Builder(act);

            b.setSmallIcon(act.getApplicationInfo().icon);
            b.setContentTitle(title.isEmpty() ? "Luvsic" : title);
            b.setContentText(artist);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            b.setShowWhen(false);
            b.setOnlyAlertOnce(true);
            b.setOngoing(isPlaying);
            if (cover != null) b.setLargeIcon(cover);

            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "上一首", pi(A_PREV, 1)).build());
            b.addAction(new Notification.Action.Builder(
                    isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "暂停" : "播放", pi(isPlaying ? A_PAUSE : A_PLAY, 2)).build());
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "下一首", pi(A_NEXT, 3)).build());

            Notification.MediaStyle st = new Notification.MediaStyle();
            st.setShowActionsInCompactView(0, 1, 2);
            if (session != null) st.setMediaSession(session.getSessionToken());
            b.setStyle(st);

            Intent open = new Intent(act, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) pflags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(act, 0, open, pflags));

            // 通知统一由 PlaybackService 发（全局唯一媒体卡片），这里不再自己 nm.notify，
            // 否则系统会同时显示两个媒体卡片（0x4C56 + 0x4C57），暂停控制还会不同步。
            Notification n = b.build();

            if (session != null) {
                MediaMetadata.Builder md = new MediaMetadata.Builder();
                md.putString(MediaMetadata.METADATA_KEY_TITLE, title);
                md.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
                md.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
                if (cover != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
                session.setMetadata(md.build());

                PlaybackState.Builder pb = new PlaybackState.Builder();
                pb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP);
                pb.setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs, isPlaying ? 1f : 0f, SystemClock.elapsedRealtime());
                session.setPlaybackState(pb.build());
                session.setActive(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "publish", e);
        }
    }
}
