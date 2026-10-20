package one.asmr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaPlaybackService extends Service {
    private static final String TAG = "MediaPlaybackService";
    public static final String CHANNEL_ID = "asmr_media_channel";
    public static final int NOTIFICATION_ID = 2002;

    public static final String ACTION_PLAY = "one.asmr.media.PLAY";
    public static final String ACTION_PAUSE = "one.asmr.media.PAUSE";
    public static final String ACTION_PLAY_PAUSE = "one.asmr.media.PLAY_PAUSE";
    public static final String ACTION_NEXT = "one.asmr.media.NEXT";
    public static final String ACTION_PREV = "one.asmr.media.PREV";
    public static final String ACTION_FAST_FORWARD = "one.asmr.media.FAST_FORWARD";
    public static final String ACTION_REWIND = "one.asmr.media.REWIND";
    public static final String ACTION_STOP = "one.asmr.media.STOP";

    public interface PlayerListener {
        void onTrackChanged(MediaTrack track, int index, int totalCount);
        void onPlaybackStateChanged(boolean isPlaying, boolean isPreparing);
        void onProgressUpdate(int currentMs, int durationMs);
        void onLyricChanged(String lyric);
        void onError(String message);
    }

    public interface ProxyResolver {
        String resolvePlaybackUrl(String targetUrl);
        Bitmap fetchCoverBitmap(String url);
        void onLoadSubtitlesForTrack(MediaTrack track);
    }

    private static volatile MediaPlaybackService instance = null;
    private static final CopyOnWriteArraySet<PlayerListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile ProxyResolver proxyResolver = null;
    private static final Handler mainHandlerStatic = new Handler(Looper.getMainLooper());

    private MediaSession mediaSession;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private boolean pausedByTransientLossOfFocus = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final List<MediaTrack> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private MediaTrack currentTrack = null;
    private Bitmap currentCoverBitmap = null;
    private String currentCoverUrl = "";

    private boolean isPlaying = false;
    private boolean isPreparing = false;

    private List<VttSubtitleParser.Cue> currentSubtitles = new ArrayList<>();
    private String currentLyric = "";
    private Runnable progressRunnable;
    private boolean isNoisyReceiverRegistered = false;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.i(TAG, "Headphones disconnected (ACTION_AUDIO_BECOMING_NOISY), pausing audio");
                pausePlayback();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    Log.i(TAG, "Audio focus lost permanently");
                    hasAudioFocus = false;
                    pausePlayback();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.i(TAG, "Audio focus lost transiently");
                    hasAudioFocus = false;
                    if (isPlaying) {
                        pausedByTransientLossOfFocus = true;
                        pausePlayback();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.i(TAG, "Audio focus ducking");
                    if (mediaPlayer != null) {
                        try {
                            mediaPlayer.setVolume(0.2f, 0.2f);
                        } catch (Throwable ignored) {}
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.i(TAG, "Audio focus gained");
                    hasAudioFocus = true;
                    if (mediaPlayer != null) {
                        try {
                            mediaPlayer.setVolume(1.0f, 1.0f);
                        } catch (Throwable ignored) {}
                    }
                    if (pausedByTransientLossOfFocus) {
                        pausedByTransientLossOfFocus = false;
                        resumePlayback();
                    }
                    break;
            }
        }
    };

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            resumePlayback();
        }

        @Override
        public void onPause() {
            pausePlayback();
        }

        @Override
        public void onSkipToNext() {
            skipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            skipToPrevious();
        }

        @Override
        public void onSeekTo(long pos) {
            seekTo((int) pos);
        }

        @Override
        public void onFastForward() {
            fastForward(15000);
        }

        @Override
        public void onRewind() {
            rewind(15000);
        }

        @Override
        public void onStop() {
            stopPlayback();
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            return super.onMediaButtonEvent(mediaButtonIntent);
        }
    };

    public static MediaPlaybackService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void setProxyResolver(ProxyResolver resolver) {
        proxyResolver = resolver;
    }

    public static void addListener(PlayerListener listener) {
        if (listener != null) {
            listeners.add(listener);
            MediaPlaybackService service = instance;
            if (service != null && service.currentTrack != null) {
                listener.onTrackChanged(service.currentTrack, service.currentIndex, service.playlist.size());
                listener.onPlaybackStateChanged(service.isPlaying, service.isPreparing);
                listener.onProgressUpdate(service.getCurrentPosition(), service.getDuration());
                listener.onLyricChanged(service.currentLyric);
            }
        }
    }

    public static void removeListener(PlayerListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, MediaPlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        if (instance != null) {
            instance.stopPlayback();
        } else if (context != null) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            context.stopService(intent);
        }
    }

    public static void startPlay(Context context, List<MediaTrack> tracks, int startIndex) {
        start(context);
        mainHandlerStatic.post(() -> {
            if (instance != null) {
                instance.playTrackList(tracks, startIndex);
            } else {
                // If service is starting up, retry shortly
                mainHandlerStatic.postDelayed(() -> {
                    if (instance != null) {
                        instance.playTrackList(tracks, startIndex);
                    }
                }, 300);
            }
        });
    }

    public synchronized void setSubtitles(List<VttSubtitleParser.Cue> cues) {
        setSubtitles(cues, currentTrack != null ? currentTrack.title : "");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
        initMediaSession();

        // Start foreground immediately to satisfy Android background startup requirements
        Notification initialNotification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, initialNotification);
        }
        Log.i(TAG, "MediaPlaybackService created and started in foreground");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "onStartCommand action=" + action);
            switch (action) {
                case ACTION_PLAY:
                    resumePlayback();
                    break;
                case ACTION_PAUSE:
                    pausePlayback();
                    break;
                case ACTION_PLAY_PAUSE:
                    togglePlayPause();
                    break;
                case ACTION_NEXT:
                    skipToNext();
                    break;
                case ACTION_PREV:
                    skipToPrevious();
                    break;
                case ACTION_FAST_FORWARD:
                    fastForward(15000);
                    break;
                case ACTION_REWIND:
                    rewind(15000);
                    break;
                case ACTION_STOP:
                    stopPlayback();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ASMR 媒体控制",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ASMR 音频播放控制与锁屏/通知栏显示");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "AsmrMediaSession");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(sessionCallback);

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        mediaSession.setSessionActivity(pi);

        Intent mbrIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mbrIntent.setClass(this, MediaPlaybackService.class);
        PendingIntent mbrPi = PendingIntent.getService(
                this, 0, mbrIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        mediaSession.setMediaButtonReceiver(mbrPi);

        updatePlaybackState(PlaybackState.STATE_NONE, 0, 0f);
        mediaSession.setActive(true);
    }

    private boolean requestAudioFocus() {
        if (hasAudioFocus) return true;
        if (audioManager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            int res = audioManager.requestAudioFocus(audioFocusRequest);
            hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            int res = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus) return;
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        hasAudioFocus = false;
        pausedByTransientLossOfFocus = false;
    }

    private void registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            isNoisyReceiverRegistered = true;
        }
    }

    private void unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Throwable ignored) {}
            isNoisyReceiverRegistered = false;
        }
    }

    public synchronized void playTrackList(List<MediaTrack> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) return;
        playlist.clear();
        playlist.addAll(tracks);
        if (startIndex < 0 || startIndex >= playlist.size()) {
            startIndex = 0;
        }
        currentIndex = startIndex;
        playCurrentIndex();
    }

    public synchronized void playSingleTrack(MediaTrack track) {
        if (track == null) return;
        playlist.clear();
        playlist.add(track);
        currentIndex = 0;
        playCurrentIndex();
    }

    private synchronized void playCurrentIndex() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            stopPlayback();
            return;
        }
        MediaTrack track = playlist.get(currentIndex);
        this.currentTrack = track;
        this.currentLyric = "";
        this.currentSubtitles.clear();

        // Notify track change
        for (PlayerListener listener : listeners) {
            listener.onTrackChanged(track, currentIndex, playlist.size());
        }

        // Request subtitle loading via proxyResolver
        if (proxyResolver != null) {
            proxyResolver.onLoadSubtitlesForTrack(track);
        }

        // Load cover art if different
        if (track.coverUrl != null && !track.coverUrl.isEmpty() && !track.coverUrl.equals(currentCoverUrl)) {
            currentCoverUrl = track.coverUrl;
            loadCoverAsync(currentCoverUrl);
        } else if (track.coverUrl == null || track.coverUrl.isEmpty()) {
            currentCoverUrl = "";
            currentCoverBitmap = null;
        }

        // Prepare and play audio
        startPreparingAudio(track);
    }

    private void startPreparingAudio(MediaTrack track) {
        isPreparing = true;
        isPlaying = false;
        stopProgressUpdater();

        updatePlaybackState(PlaybackState.STATE_BUFFERING, 0, 0f);
        updateMediaMetadata();
        updateNotification();

        for (PlayerListener listener : listeners) {
            listener.onPlaybackStateChanged(isPlaying, isPreparing);
        }

        ioExecutor.execute(() -> {
            try {
                String rawUrl = track.getPlaybackUrl();
                String playbackUrl = rawUrl;
                if (proxyResolver != null) {
                    playbackUrl = proxyResolver.resolvePlaybackUrl(rawUrl);
                }

                String finalPlaybackUrl = playbackUrl;
                mainHandler.post(() -> {
                    try {
                        if (mediaPlayer != null) {
                            try {
                                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                            } catch (Throwable ignored) {}
                            mediaPlayer.reset();
                            mediaPlayer.release();
                            mediaPlayer = null;
                        }

                        mediaPlayer = new MediaPlayer();
                        mediaPlayer.setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                        );
                        mediaPlayer.setDataSource(MediaPlaybackService.this, Uri.parse(finalPlaybackUrl));

                        mediaPlayer.setOnPreparedListener(mp -> {
                            isPreparing = false;
                            requestAudioFocus();
                            mp.start();
                            isPlaying = true;
                            registerNoisyReceiver();

                            updatePlaybackState(PlaybackState.STATE_PLAYING, mp.getCurrentPosition(), 1.0f);
                            updateMediaMetadata();
                            updateNotification();
                            startProgressUpdater();

                            for (PlayerListener listener : listeners) {
                                listener.onPlaybackStateChanged(isPlaying, isPreparing);
                            }
                        });

                        mediaPlayer.setOnCompletionListener(mp -> {
                            Log.i(TAG, "Audio playback completed: " + track.title);
                            if (hasNext()) {
                                skipToNext();
                            } else {
                                isPlaying = false;
                                isPreparing = false;
                                stopProgressUpdater();
                                unregisterNoisyReceiver();
                                updatePlaybackState(PlaybackState.STATE_STOPPED, 0, 0f);
                                updateNotification();

                                for (PlayerListener listener : listeners) {
                                    listener.onPlaybackStateChanged(isPlaying, isPreparing);
                                    listener.onProgressUpdate(0, getDuration());
                                }
                            }
                        });

                        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                            isPreparing = false;
                            isPlaying = false;
                            stopProgressUpdater();
                            unregisterNoisyReceiver();
                            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);

                            updatePlaybackState(PlaybackState.STATE_ERROR, 0, 0f);
                            updateNotification();

                            for (PlayerListener listener : listeners) {
                                listener.onPlaybackStateChanged(isPlaying, isPreparing);
                                listener.onError("播放出错 (" + what + ", " + extra + ")");
                            }
                            return true;
                        });

                        mediaPlayer.prepareAsync();
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to start media player", e);
                        isPreparing = false;
                        isPlaying = false;
                        for (PlayerListener listener : listeners) {
                            listener.onPlaybackStateChanged(isPlaying, isPreparing);
                            listener.onError("播放初始化失败: " + e.getMessage());
                        }
                    }
                });
            } catch (Throwable e) {
                Log.e(TAG, "Proxy URL resolution failed", e);
                mainHandler.post(() -> {
                    isPreparing = false;
                    isPlaying = false;
                    for (PlayerListener listener : listeners) {
                        listener.onPlaybackStateChanged(isPlaying, isPreparing);
                        listener.onError("媒体地址解析失败: " + e.getMessage());
                    }
                });
            }
        });
    }

    public synchronized void resumePlayback() {
        if (mediaPlayer != null && !isPlaying && !isPreparing) {
            requestAudioFocus();
            try {
                mediaPlayer.start();
                isPlaying = true;
                registerNoisyReceiver();
                updatePlaybackState(PlaybackState.STATE_PLAYING, mediaPlayer.getCurrentPosition(), 1.0f);
                updateNotification();
                startProgressUpdater();

                for (PlayerListener listener : listeners) {
                    listener.onPlaybackStateChanged(isPlaying, isPreparing);
                }
            } catch (Throwable e) {
                Log.e(TAG, "resumePlayback failed", e);
            }
        } else if (mediaPlayer == null && currentTrack != null) {
            playCurrentIndex();
        }
    }

    public synchronized void pausePlayback() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.pause();
                isPlaying = false;
                stopProgressUpdater();
                unregisterNoisyReceiver();
                updatePlaybackState(PlaybackState.STATE_PAUSED, mediaPlayer.getCurrentPosition(), 0f);
                updateNotification();

                for (PlayerListener listener : listeners) {
                    listener.onPlaybackStateChanged(isPlaying, isPreparing);
                }
            } catch (Throwable e) {
                Log.e(TAG, "pausePlayback failed", e);
            }
        }
    }

    public synchronized void togglePlayPause() {
        if (isPlaying) {
            pausePlayback();
        } else {
            resumePlayback();
        }
    }

    public synchronized void seekTo(int posMs) {
        if (mediaPlayer != null) {
            try {
                int duration = mediaPlayer.getDuration();
                if (duration > 0) {
                    posMs = Math.max(0, Math.min(posMs, duration));
                }
                mediaPlayer.seekTo(posMs);
                updatePlaybackState(
                        isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        posMs,
                        isPlaying ? 1.0f : 0f
                );
                notifyProgress(posMs, duration);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void fastForward(int deltaMs) {
        if (mediaPlayer != null) {
            try {
                int duration = mediaPlayer.getDuration();
                int current = mediaPlayer.getCurrentPosition();
                int target = Math.min(duration > 0 ? duration : Integer.MAX_VALUE, current + deltaMs);
                seekTo(target);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void rewind(int deltaMs) {
        if (mediaPlayer != null) {
            try {
                int current = mediaPlayer.getCurrentPosition();
                int target = Math.max(0, current - deltaMs);
                seekTo(target);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized boolean hasNext() {
        return currentIndex + 1 < playlist.size();
    }

    public synchronized boolean hasPrevious() {
        return currentIndex > 0;
    }

    public synchronized void skipToNext() {
        if (hasNext()) {
            currentIndex++;
            playCurrentIndex();
        } else {
            // If at end, seek to start of current
            seekTo(0);
        }
    }

    public synchronized void skipToPrevious() {
        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3000) {
            seekTo(0);
        } else if (hasPrevious()) {
            currentIndex--;
            playCurrentIndex();
        } else {
            seekTo(0);
        }
    }

    public synchronized void stopPlayback() {
        stopProgressUpdater();
        unregisterNoisyReceiver();
        abandonAudioFocus();

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }

        isPlaying = false;
        isPreparing = false;
        currentLyric = "";
        currentSubtitles.clear();

        updatePlaybackState(PlaybackState.STATE_STOPPED, 0, 0f);

        if (mediaSession != null) {
            mediaSession.setActive(false);
        }

        for (PlayerListener listener : listeners) {
            listener.onPlaybackStateChanged(isPlaying, isPreparing);
            listener.onLyricChanged("");
        }

        if (FloatingSubtitleService.isRunning()) {
            FloatingSubtitleService.updateSubtitle("", "", false);
        }

        stopForeground(true);
        stopSelf();
    }

    public synchronized void setSubtitles(List<VttSubtitleParser.Cue> cues, String subFileName) {
        this.currentSubtitles = cues != null ? new ArrayList<>(cues) : new ArrayList<>();
        if (!currentSubtitles.isEmpty() && currentTrack != null) {
            String initialMsg = "💬 已加载字幕: " + (subFileName != null ? subFileName : currentTrack.title);
            this.currentLyric = initialMsg;
            for (PlayerListener listener : listeners) {
                listener.onLyricChanged(initialMsg);
            }
            if (FloatingSubtitleService.isRunning()) {
                FloatingSubtitleService.updateSubtitle(initialMsg, currentTrack.title, isPlaying);
            }
        }
    }

    public MediaTrack getCurrentTrack() {
        return currentTrack;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPreparing() {
        return isPreparing;
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null && !isPreparing) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    public int getDuration() {
        if (mediaPlayer != null && !isPreparing) {
            try {
                int d = mediaPlayer.getDuration();
                if (d > 0) return d;
            } catch (Throwable ignored) {}
        }
        if (currentTrack != null && currentTrack.duration > 0) {
            return (int) (currentTrack.duration * 1000);
        }
        return 0;
    }

    public List<MediaTrack> getPlaylist() {
        return Collections.unmodifiableList(playlist);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    private void startProgressUpdater() {
        stopProgressUpdater();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    try {
                        int current = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        notifyProgress(current, duration);

                        // Sync subtitle
                        if (currentSubtitles != null && !currentSubtitles.isEmpty()) {
                            String cueText = VttSubtitleParser.getCueText(currentSubtitles, current);
                            if (!cueText.equals(currentLyric)) {
                                currentLyric = cueText;
                                for (PlayerListener listener : listeners) {
                                    listener.onLyricChanged(cueText);
                                }
                                if (FloatingSubtitleService.isRunning()) {
                                    FloatingSubtitleService.updateSubtitle(
                                            cueText,
                                            currentTrack != null ? currentTrack.title : "",
                                            isPlaying
                                    );
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    mainHandler.postDelayed(this, 500);
                }
            }
        };
        mainHandler.post(progressRunnable);
    }

    private void stopProgressUpdater() {
        if (progressRunnable != null) {
            mainHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void notifyProgress(int current, int duration) {
        for (PlayerListener listener : listeners) {
            listener.onProgressUpdate(current, duration);
        }
    }

    private void updatePlaybackState(int state, long position, float speed) {
        if (mediaSession == null) return;

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND;

        if (hasPrevious() || (mediaPlayer != null && position > 3000)) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (hasNext()) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }

        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, speed, SystemClock.elapsedRealtime());

        // Custom actions for fast forward / rewind
        b.addCustomAction(
                new PlaybackState.CustomAction.Builder(ACTION_REWIND, "快退15秒", android.R.drawable.ic_media_rew).build()
        );
        b.addCustomAction(
                new PlaybackState.CustomAction.Builder(ACTION_FAST_FORWARD, "快进15秒", android.R.drawable.ic_media_ff).build()
        );

        mediaSession.setPlaybackState(b.build());
    }

    private void updateMediaMetadata() {
        if (mediaSession == null || currentTrack == null) return;

        MediaMetadata.Builder b = new MediaMetadata.Builder();
        b.putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack.title);
        b.putString(MediaMetadata.METADATA_KEY_ARTIST,
                !currentTrack.artist.isEmpty() ? currentTrack.artist : currentTrack.workId);
        b.putString(MediaMetadata.METADATA_KEY_ALBUM,
                !currentTrack.workTitle.isEmpty() ? currentTrack.workTitle : currentTrack.workId);

        int dur = getDuration();
        if (dur > 0) {
            b.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        }

        if (currentCoverBitmap != null && !currentCoverBitmap.isRecycled()) {
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentCoverBitmap);
            b.putBitmap(MediaMetadata.METADATA_KEY_ART, currentCoverBitmap);
        }

        mediaSession.setMetadata(b.build());
    }

    private void updateNotification() {
        Notification notification = buildNotification();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        String titleText = currentTrack != null ? currentTrack.title : "ASMR 播放器";
        String artistText = currentTrack != null ? (!currentTrack.artist.isEmpty() ? currentTrack.artist : currentTrack.workTitle) : "准备播放";

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevPending = createActionPendingIntent(ACTION_PREV, 10);
        PendingIntent playPausePending = createActionPendingIntent(ACTION_PLAY_PAUSE, 11);
        PendingIntent nextPending = createActionPendingIntent(ACTION_NEXT, 12);
        PendingIntent stopPending = createActionPendingIntent(ACTION_STOP, 13);

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        // Show Prev(0), Play/Pause(1), Next(2) in compact view
        mediaStyle.setShowActionsInCompactView(0, 1, 2);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setStyle(mediaStyle)
                .setSmallIcon(isPlaying ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(titleText)
                .setContentText(artistText)
                .setContentIntent(contentPending)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "上一首", prevPending
                ).build())
                .addAction(new Notification.Action.Builder(
                        isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "暂停" : "播放", playPausePending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "下一首", nextPending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPending
                ).build());

        if (currentTrack != null && !currentTrack.workTitle.isEmpty()) {
            builder.setSubText(currentTrack.workTitle);
        }

        if (currentCoverBitmap != null && !currentCoverBitmap.isRecycled()) {
            builder.setLargeIcon(currentCoverBitmap);
        }

        return builder.build();
    }

    private PendingIntent createActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private void loadCoverAsync(String coverUrl) {
        ioExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                if (proxyResolver != null) {
                    bmp = proxyResolver.fetchCoverBitmap(coverUrl);
                }
                if (bmp == null) {
                    URL url = new URL(coverUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
                    conn.setRequestProperty("Referer", "https://asmr.one/");
                    try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                        bmp = BitmapFactory.decodeStream(in);
                    } finally {
                        conn.disconnect();
                    }
                }
                if (bmp != null) {
                    int maxDim = 512;
                    int w = bmp.getWidth();
                    int h = bmp.getHeight();
                    if (w > maxDim || h > maxDim) {
                        float ratio = Math.min((float) maxDim / w, (float) maxDim / h);
                        int targetW = Math.max(1, Math.round(w * ratio));
                        int targetH = Math.max(1, Math.round(h * ratio));
                        bmp = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
                    }
                    Bitmap finalBmp = bmp;
                    mainHandler.post(() -> {
                        if (coverUrl.equals(currentCoverUrl)) {
                            currentCoverBitmap = finalBmp;
                            updateMediaMetadata();
                            updateNotification();
                        }
                    });
                }
            } catch (Throwable e) {
                Log.w(TAG, "Failed to load cover art: " + coverUrl, e);
            }
        });
    }

    @Override
    public void onDestroy() {
        stopProgressUpdater();
        unregisterNoisyReceiver();
        abandonAudioFocus();

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        instance = null;
        super.onDestroy();
        Log.i(TAG, "MediaPlaybackService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
