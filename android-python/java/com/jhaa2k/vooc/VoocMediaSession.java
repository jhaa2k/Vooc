package com.jhaa2k.vooc;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

/** Native Android media-session bridge for Vooc notification and lock-screen controls. */
public final class VoocMediaSession {
    private static final String CHANNEL = "vooc_music";
    private static final int NOTIFICATION_ID = 4107;
    private static MediaSession session;
    private static NotificationManager manager;
    private static Context app;
    private static ActionReceiver receiver;
    private static volatile String pendingAction = "";
    private VoocMediaSession() {}

    public static synchronized void init(Activity activity) {
        if (activity == null) return;
        app = activity.getApplicationContext();
        manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Vooc music controls",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback controls for music playing in Vooc");
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
        if (session == null) {
            session = new MediaSession(app, "VoocPlayback");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { pendingAction = "play"; }
                @Override public void onPause() { pendingAction = "pause"; }
                @Override public void onSkipToNext() { pendingAction = "next"; }
                @Override public void onSkipToPrevious() { pendingAction = "previous"; }
                @Override public void onStop() { pendingAction = "pause"; }
            });
            session.setActive(true);
        }
        if (receiver == null) {
            receiver = new ActionReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(actionName("play")); filter.addAction(actionName("pause"));
            filter.addAction(actionName("next")); filter.addAction(actionName("previous"));
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else app.registerReceiver(receiver, filter);
            } catch (Exception ignored) {}
        }
    }

    private static String actionName(String action) {
        return "com.jhaa2k.vooc.MEDIA_" + action.toUpperCase();
    }

    public static final class ActionReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String a = intent.getAction();
            if (a.endsWith("_PLAY")) pendingAction = "play";
            else if (a.endsWith("_PAUSE")) pendingAction = "pause";
            else if (a.endsWith("_NEXT")) pendingAction = "next";
            else if (a.endsWith("_PREVIOUS")) pendingAction = "previous";
        }
    }

    private static PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(actionName(action)).setPackage(app.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, requestCode, intent, flags);
    }

    public static synchronized void update(String title, String artist, boolean playing,
                                            long duration, long position) {
        if (app == null || manager == null || session == null) return;
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "Vooc" : title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist == null ? "" : artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, duration)).build());
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                    PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT |
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            Math.max(0, position), playing ? 1f : 0f).build());
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
            b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title == null ? "Vooc" : title)
                    .setContentText(artist == null ? "Music Visualizer" : artist)
                    .setOnlyAlertOnce(true).setOngoing(playing)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_TRANSPORT).setShowWhen(false)
                    .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent("previous", 1))
                    .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                            playing ? "Pause" : "Play", actionIntent(playing ? "pause" : "play", 2))
                    .addAction(android.R.drawable.ic_media_next, "Next", actionIntent("next", 3));
            if (Build.VERSION.SDK_INT >= 21)
                b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
            manager.notify(NOTIFICATION_ID, b.build());
        } catch (Exception ignored) {}
    }

    public static String consumeAction() {
        String a = pendingAction; pendingAction = ""; return a;
    }

    public static synchronized void release() {
        try {
            if (manager != null) manager.cancel(NOTIFICATION_ID);
            if (session != null) { session.setActive(false); session.release(); }
            if (app != null && receiver != null) app.unregisterReceiver(receiver);
        } catch (Exception ignored) {}
        session = null; receiver = null; manager = null; app = null; pendingAction = "";
    }
}
