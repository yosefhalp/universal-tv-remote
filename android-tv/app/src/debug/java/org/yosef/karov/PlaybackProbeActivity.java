package org.yosef.karov;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

// נגן מעבדה בלבד. קובץ הווידאו נוצר מקומית ונמצא בתיקיית הקבצים החיצונית של debug.
public final class PlaybackProbeActivity extends Activity {
    private MediaSession session;
    private VideoView video;
    private TextView status;
    private final Handler main = new Handler();
    private boolean prepared;
    private int plays, pauses, forwards, rewinds;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("secureWindow", false))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 20, 40, 20);
        root.setBackgroundColor(0xff0d1016);
        TextView title = new TextView(this);
        title.setText("קרוב · בדיקת שליטה בניגון");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);
        video = new VideoView(this);
        video.setSecure(getIntent().getBooleanExtra("secureVideo", false));
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        root.addView(status);
        setContentView(root);
        session = new MediaSession(this, "KarovPlaybackProbe");
        session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setMetadata(
                new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "סרט בדיקה של קרוב")
                        .build());
        session.setCallback(
                new MediaSession.Callback() {
                    @Override
                    public void onPlay() {
                        plays++;
                        video.start();
                        update();
                    }

                    @Override
                    public void onPause() {
                        pauses++;
                        video.pause();
                        update();
                    }

                    @Override
                    public void onFastForward() {
                        forwards++;
                        video.seekTo(
                                Math.min(
                                        video.getDuration() - 1000,
                                        video.getCurrentPosition() + 10000));
                        update();
                    }

                    @Override
                    public void onRewind() {
                        rewinds++;
                        video.seekTo(Math.max(0, video.getCurrentPosition() - 10000));
                        update();
                    }
                });
        video.setOnPreparedListener(player -> {
            prepared = true;
            player.setLooping(true);
            video.start();
            session.setActive(true);
            update();
        });
        video.setVideoURI(Uri.fromFile(new File(getExternalFilesDir(null), "playback-test.mp4")));
        main.post(tick);
    }

    private void update() {
        if (!prepared) return;
        boolean playing = video.isPlaying();
        int position = video.getCurrentPosition();
        session.setPlaybackState(
                new PlaybackState.Builder()
                        .setActions(
                                PlaybackState.ACTION_PLAY
                                        | PlaybackState.ACTION_PAUSE
                                        | PlaybackState.ACTION_PLAY_PAUSE
                                        | PlaybackState.ACTION_FAST_FORWARD
                                        | PlaybackState.ACTION_REWIND)
                        .setState(
                                playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                position,
                                playing ? 1 : 0)
                        .build());
        status.setText(
                (playing ? "מנגן" : "מושהה")
                        + " · "
                        + position / 1000
                        + " שניות\nניגון: "
                        + plays
                        + " · השהיה: "
                        + pauses
                        + " · קדימה: "
                        + forwards
                        + " · אחורה: "
                        + rewinds);
    }

    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    update();
                    main.postDelayed(this, 500);
                }
            };

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        video.stopPlayback();
        session.release();
        super.onDestroy();
    }
}
