package com.example.xraylocal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

public class FloatingSubtitleService extends Service {

    private static final String TAG = "FloatingSubtitleService";
    private static final String CHANNEL_ID = "asmr_floating_subtitle_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_TOGGLE_PLAY = "com.example.xraylocal.ACTION_TOGGLE_PLAY";
    public static final String ACTION_CLOSE = "com.example.xraylocal.ACTION_CLOSE";

    public interface FloatingControlListener {
        void onFloatingTogglePlay();
        void onFloatingClose();
    }

    private static volatile FloatingSubtitleService instance = null;
    private static volatile FloatingControlListener controlListener = null;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams windowParams;

    private TextView lyricTextView;
    private TextView subtitleMetaView;
    private Button playPauseBtn;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isPlaying = false;
    private String currentTitle = "";
    private String currentLyric = "";

    public static void setControlListener(FloatingControlListener listener) {
        controlListener = listener;
    }

    public static boolean isRunning() {
        return instance != null && instance.floatingView != null;
    }

    public static void start(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot start FloatingSubtitleService: overlay permission not granted");
            return;
        }
        Intent intent = new Intent(context, FloatingSubtitleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, FloatingSubtitleService.class);
        context.stopService(intent);
    }

    public static void updateSubtitle(String lyric, String title, boolean playing) {
        FloatingSubtitleService service = instance;
        if (service != null) {
            service.postUpdateSubtitle(lyric, title, playing);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundWithNotification();
        createFloatingView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE_PLAY.equals(action)) {
                if (controlListener != null) {
                    controlListener.onFloatingTogglePlay();
                } else if (MediaPlaybackService.isRunning()) {
                    MediaPlaybackService.getInstance().togglePlayPause();
                }
            } else if (ACTION_CLOSE.equals(action)) {
                if (controlListener != null) {
                    controlListener.onFloatingClose();
                }
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        createNotificationChannel();

        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPending = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent toggleIntent = new Intent(this, FloatingSubtitleService.class);
        toggleIntent.setAction(ACTION_TOGGLE_PLAY);
        PendingIntent togglePending = PendingIntent.getService(
                this, 1, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent closeIntent = new Intent(this, FloatingSubtitleService.class);
        closeIntent.setAction(ACTION_CLOSE);
        PendingIntent closePending = PendingIntent.getService(
                this, 2, closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASMR 悬浮歌词已开启")
                .setContentText(currentTitle.isEmpty() ? "正在其他应用之上悬浮显示歌词" : currentTitle)
                .setContentIntent(contentPending)
                .addAction(android.R.drawable.ic_media_pause, "播放/暂停", togglePending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮窗", closePending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ASMR 悬浮歌词与媒体播放",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("提供 ASMR 桌面悬浮歌词与后台播放控制");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void createFloatingView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot create floating view without overlay permission");
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        windowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        windowParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        windowParams.x = 0;
        windowParams.y = dp(100);

        // Build the floating card UI
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        // Styling: Translucent card with rounded border
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(12));
        cardBg.setColor(Color.argb(225, 245, 246, 250));
        cardBg.setStroke(dp(1), Color.argb(90, 156, 39, 176));
        root.setBackground(cardBg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.setElevation(dp(8));
        }

        // Mini control & drag header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(4));

        subtitleMetaView = new TextView(this);
        subtitleMetaView.setText("💬 ASMR 歌词");
        subtitleMetaView.setTextSize(11);
        subtitleMetaView.setTextColor(Color.rgb(120, 120, 140));
        subtitleMetaView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(subtitleMetaView);

        playPauseBtn = new Button(this);
        playPauseBtn.setText("❚❚");
        playPauseBtn.setTextSize(10);
        playPauseBtn.setPadding(dp(6), dp(1), dp(6), dp(1));
        playPauseBtn.setMinWidth(0);
        playPauseBtn.setMinHeight(0);
        playPauseBtn.setOnClickListener(v -> {
            if (controlListener != null) {
                controlListener.onFloatingTogglePlay();
            } else if (MediaPlaybackService.isRunning()) {
                MediaPlaybackService.getInstance().togglePlayPause();
            }
        });
        header.addView(playPauseBtn);

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(10);
        closeBtn.setPadding(dp(6), dp(1), dp(6), dp(1));
        closeBtn.setMinWidth(0);
        closeBtn.setMinHeight(0);
        closeBtn.setOnClickListener(v -> {
            if (controlListener != null) {
                controlListener.onFloatingClose();
            }
            stopSelf();
        });
        header.addView(closeBtn);

        root.addView(header);

        // Subtitle text container matching #lyricsBar / <span id="lyric">
        FrameLayout lyricsBar = new FrameLayout(this);
        lyricsBar.setPadding(dp(12), dp(4), dp(12), dp(6));

        lyricTextView = new TextView(this);
        lyricTextView.setId(android.R.id.text1);
        lyricTextView.setText(currentLyric.isEmpty() ? "那么…" : currentLyric);
        lyricTextView.setTextColor(Color.rgb(156, 39, 176)); // #9C27B0 purple matching web
        lyricTextView.setTypeface(Typeface.DEFAULT_BOLD);
        lyricTextView.setTextSize(16);
        lyricTextView.setGravity(Gravity.CENTER);
        lyricTextView.setMaxLines(2);
        lyricTextView.setEllipsize(TextUtils.TruncateAt.END);

        // Set max display width for readability across other apps
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
        lyricTextView.setMaxWidth(maxWidth);

        lyricsBar.addView(lyricTextView);
        root.addView(lyricsBar);

        // Drag listener for moving the floating overlay anywhere
        root.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoving;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = windowParams.x;
                        initialY = windowParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                        }
                        if (isMoving && floatingView != null && windowManager != null) {
                            windowParams.x = initialX + dx;
                            windowParams.y = initialY + dy;
                            windowManager.updateViewLayout(floatingView, windowParams);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        return isMoving;
                }
                return false;
            }
        });

        floatingView = root;
        try {
            windowManager.addView(floatingView, windowParams);
            Log.i(TAG, "Floating subtitle view successfully added to WindowManager");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to add floating view to WindowManager", e);
        }
    }

    private void postUpdateSubtitle(String lyric, String title, boolean playing) {
        mainHandler.post(() -> {
            this.currentLyric = lyric != null ? lyric : "";
            this.currentTitle = title != null ? title : "";
            this.isPlaying = playing;

            if (lyricTextView != null) {
                if (currentLyric.isEmpty()) {
                    lyricTextView.setText(currentTitle.isEmpty() ? "💬 (等待歌词…)" : "▶ " + currentTitle);
                    lyricTextView.setTextColor(Color.rgb(160, 140, 180));
                } else {
                    lyricTextView.setText(currentLyric);
                    lyricTextView.setTextColor(Color.rgb(156, 39, 176));
                }
            }

            if (subtitleMetaView != null && !currentTitle.isEmpty()) {
                subtitleMetaView.setText("💬 " + currentTitle);
            }

            if (playPauseBtn != null) {
                playPauseBtn.setText(isPlaying ? "❚❚" : "▶");
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Throwable ignored) {}
            floatingView = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
