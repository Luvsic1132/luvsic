package com.luvsic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 播放保活前台服务（全局唯一的媒体会话 + 唯一的媒体通知）。
 *
 * 三件事：
 * 1. 播放中：钉住进程，通知栏显示当前歌曲；
 * 2. 暂停后超过设定分钟数（默认 30，可在设置里选 10/30/60/不退出）：自动退出后台；
 * 3. 看门狗：网页每 3 秒推来进度，服务按「剩余时长」推算播完时间；
 *    后台 WebView 被系统冻结、ended 没触发时，服务主动让网页切下一首。
 */
public class PlaybackService extends Service {
    private static final String CH = "luvsic_keepalive";
    private static final int ID = 0x4C57;
    private static final String ACT_PREV = "com.luvsic.app.MEDIA_PREV";
    private static final String ACT_NEXT = "com.luvsic.app.MEDIA_NEXT";
    private static final String ACT_PLAY = "com.luvsic.app.MEDIA_PLAY";
    private static final String ACT_PAUSE = "com.luvsic.app.MEDIA_PAUSE";

    private String title = "";
    private String artist = "";
    private boolean playing = false;
    private long positionMs = 0;
    private long durationMs = 0;
    private long stateAt = 0;          // 最近一次收到播放状态的时间
    private int timeoutMin = 30;       // 暂停超时（分钟，0=不退出）
    private static MediaSession sSession = null;

    private Handler handler = null;

    /** 暂停超时：到点即退出后台（删通知、停服务） */
    private final Runnable pauseTask = new Runnable() {
        @Override
        public void run() {
            if (playing) return;
            try {
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignored) {
            }
            try {
                if (sSession != null) sSession.setActive(false);
            } catch (Exception ignored) {
            }
            stopSelf();
        }
    };

    /** 看门狗：推算播完时间，到点还没切歌就主动切下一首 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (playing && durationMs > 0 && stateAt > 0) {
                    long expectedEnd = stateAt + (durationMs - positionMs);
                    if (System.currentTimeMillis() > expectedEnd + 1500L) {
                        MainActivity.mediaAction("next", -1);
                        handler.postDelayed(this, 4000L);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 1000L);
        }
    };

    /** 给 MediaNotif 复用同一个会话（系统媒体卡片靠它） */
    public static MediaSession sharedSession() {
        return sSession;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CH, "后台播放",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            if (sSession == null) {
                sSession = new MediaSession(this, "LuvsicPlayback");
                // 系统媒体卡片的按钮（播放/暂停/上一首/下一首/进度）都走这个回调。
                // 之前回调只挂在 MediaNotif 自己的 session 上，卡片换成服务的 session 后点了没反应。
                sSession.setCallback(new MediaSession.Callback() {
                    @Override
                    public void onPlay() {
                        MainActivity.mediaAction("play", -1);
                    }

                    @Override
                    public void onPause() {
                        MainActivity.mediaAction("pause", -1);
                    }

                    @Override
                    public void onSkipToNext() {
                        MainActivity.mediaAction("next", -1);
                    }

                    @Override
                    public void onSkipToPrevious() {
                        MainActivity.mediaAction("prev", -1);
                    }

                    @Override
                    public void onSeekTo(long pos) {
                        MainActivity.mediaAction("seek", pos);
                    }

                    @Override
                    public void onStop() {
                        MainActivity.mediaAction("pause", -1);
                    }
                });
                sSession.setActive(true);
            }
            startForeground(ID, build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) return START_STICKY;
            // 通知按钮：直接回到本服务处理，不依赖广播接收器
            String act = intent.getAction();
            if (ACT_PREV.equals(act)) {
                MainActivity.mediaAction("prev", -1);
                return START_STICKY;
            }
            if (ACT_NEXT.equals(act)) {
                MainActivity.mediaAction("next", -1);
                return START_STICKY;
            }
            if (ACT_PLAY.equals(act)) {
                MainActivity.mediaAction("play", -1);
                return START_STICKY;
            }
            if (ACT_PAUSE.equals(act)) {
                MainActivity.mediaAction("pause", -1);
                return START_STICKY;
            }
            // 网页推来的播放状态
            String t = intent.getStringExtra("title");
            String a = intent.getStringExtra("artist");
            boolean p = intent.getBooleanExtra("playing", playing);
            if (t != null) title = t;
            if (a != null) artist = a;
            playing = p;
            positionMs = intent.getLongExtra("positionMs", positionMs);
            durationMs = intent.getLongExtra("durationMs", durationMs);
            timeoutMin = intent.getIntExtra("timeoutMin", timeoutMin);
            stateAt = System.currentTimeMillis();
            updateSession();
            startForeground(ID, build());

            handler.removeCallbacks(pauseTask);
            if (!playing) {
                if (timeoutMin > 0) handler.postDelayed(pauseTask, timeoutMin * 60_000L);
            }
            handler.removeCallbacks(watchdog);
            if (playing) handler.postDelayed(watchdog, 1200L);
        } catch (Exception ignored) {
        }
        return START_STICKY;
    }

    private void updateSession() {
        try {
            if (sSession != null) {
                MediaMetadata.Builder mb = new MediaMetadata.Builder();
                mb.putString(MediaMetadata.METADATA_KEY_TITLE, title);
                mb.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
                if (durationMs > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
                sSession.setMetadata(mb.build());
                PlaybackState.Builder sb = new PlaybackState.Builder();
                sb.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs, playing ? 1.0f : 0.0f);
                sb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO);
                sSession.setPlaybackState(sb.build());
            }
        } catch (Exception ignored) {
        }
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, piFlags);
        String text = title.isEmpty() ? "Luvsic 正在后台运行" : title;
        if (!artist.isEmpty()) text = text + " · " + artist;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Luvsic 正在播放")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        try {
            int piFlag = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            b.addAction(new Notification.Action(android.R.drawable.ic_media_previous, "上一首",
                    PendingIntent.getService(this, 11,
                            new Intent(this, PlaybackService.class).setAction(ACT_PREV), piFlag)));
            b.addAction(new Notification.Action(
                    playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "暂停" : "播放",
                    PendingIntent.getService(this, 12,
                            new Intent(this, PlaybackService.class)
                                    .setAction(playing ? ACT_PAUSE : ACT_PLAY), piFlag)));
            b.addAction(new Notification.Action(android.R.drawable.ic_media_next, "下一首",
                    PendingIntent.getService(this, 13,
                            new Intent(this, PlaybackService.class).setAction(ACT_NEXT), piFlag)));
        } catch (Exception ignored) {
        }
        try {
            if (sSession != null && Build.VERSION.SDK_INT >= 21) {
                Notification.MediaStyle ms = new Notification.MediaStyle();
                ms.setMediaSession(sSession.getSessionToken());
                ms.setShowActionsInCompactView(0, 1, 2);
                b.setStyle(ms);
            }
        } catch (Exception ignored) {
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        try {
            if (handler != null) handler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }
}
