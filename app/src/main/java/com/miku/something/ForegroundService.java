package com.miku.something;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that:
 *  - shows several images bouncing around the screen as system overlays,
 *  - loops an audio track,
 *  - shows an ongoing notification with a "Stop" action,
 *  - swaps the launcher icon while it is running.
 */
public class ForegroundService extends Service {

    // Intent action sent by the notification "Stop" button
    private static final String ACTION_STOP = "com.miku.something.ACTION_STOP";

    // Notification channel / notification identifiers
    private static final String NOTIFICATION_CHANNEL_ID = "miku_channel";
    private static final int NOTIFICATION_ID = 1002;

    // Size of every bouncing image in dp (converted to pixels, so it looks the same on every screen)
    private static final float IMAGE_SIZE_DP = 250f;

    // Movement speed in dp per second, on each axis
    private static final float SPEED_DP_PER_SEC = 140f;

    // Longest time step accepted for one frame (seconds). Prevents a big jump after a lag spike
    private static final float MAX_FRAME_DELTA_SEC = 0.05f;

    // How many bouncing images are created
    private static final int IMAGE_COUNT = 3;

    private NotificationManager notificationManager;
    private WindowManager windowManager;

    // Screen size in pixels, used to detect when an image hits an edge
    private int screenWidth;
    private int screenHeight;

    // Image size and speed converted from dp to pixels
    private int imageSizePx;
    private float speedPxPerSec;

    // All the images currently displayed on the screen
    private final List<BouncingImage> bouncingImages = new ArrayList<>();

    // Lets MainActivity know whether the service is already running
    public static boolean isRunning = false;

    // True once the overlays and the audio were created, so a repeated
    // onStartCommand() (for example a sticky restart) does not duplicate them
    private boolean isSetupDone = false;

    // Plays the looping audio track
    private MediaPlayer mediaPlayer;

    // Timestamp of the previous frame, used to compute the elapsed time between frames
    private long lastFrameTimeNanos = 0;

    // Called by the system once per display frame (vsync), which keeps the
    // animation smooth and its speed independent of the screen refresh rate
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!isSetupDone) return;

            // Seconds elapsed since the previous frame (0 on the very first frame)
            float deltaSec = lastFrameTimeNanos == 0
                    ? 0f
                    : (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
            lastFrameTimeNanos = frameTimeNanos;

            updateImages(Math.min(deltaSec, MAX_FRAME_DELTA_SEC));

            // Schedule the next frame
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        isRunning = true;
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Read the screen size and compute the pixel values
        updateScreenMetrics();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // "Stop" button pressed: restore the normal icon and shut the service down
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppIconManager.useDefault(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();

        // PendingIntent that sends ACTION_STOP back to this service
        Intent stopIntent = new Intent(this, ForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        // Ongoing notification required by the foreground service, with a Stop button.
        // The small icon must be a monochrome vector, otherwise it is drawn as a white square.
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .addAction(
                    android.R.drawable.ic_delete,
                    getString(R.string.notification_action_stop),
                    stopPendingIntent
                )
                .build();

        // Always call startForeground, even on a repeated start, because the system
        // requires it after every startForegroundService() call
        startForeground(NOTIFICATION_ID, notification);

        // Already set up: do not create a second set of overlays and a second player
        if (isSetupDone) {
            return START_STICKY;
        }
        isSetupDone = true;

        changeIcon();
        setupImages();
        setupAudio();

        // Start the animation loop
        lastFrameTimeNanos = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);

        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Screen rotated or resized: recompute the sizes and resize the existing overlays
        updateScreenMetrics();
        for (BouncingImage bi : bouncingImages) {
            bi.params.width = imageSizePx;
            bi.params.height = imageSizePx;
            checkEdges(bi);
            bi.params.x = Math.round(bi.x);
            bi.params.y = Math.round(bi.y);
            windowManager.updateViewLayout(bi.view, bi.params);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isSetupDone = false;

        // Stop the animation loop
        Choreographer.getInstance().removeFrameCallback(frameCallback);

        // Remove every overlay from the window manager
        for (BouncingImage bi : bouncingImages) {
            if (bi.view != null && bi.view.isAttachedToWindow()) {
                windowManager.removeView(bi.view);
            }
        }
        bouncingImages.clear();

        // Stop and release the audio player
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Not a bound service
        return null;
    }

    /**
     * Reads the current screen size and converts the dp constants to pixels.
     * Called on creation and every time the configuration changes (rotation).
     */
    @SuppressWarnings("deprecation") // getRealMetrics is only used below API 30
    private void updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API: bounds of the whole screen in the current rotation
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            // Legacy API for Android 8 - 10
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        }

        float density = getResources().getDisplayMetrics().density;

        // Never let an image be larger than the smaller side of the screen
        imageSizePx = Math.round(Math.min(IMAGE_SIZE_DP * density, Math.min(screenWidth, screenHeight)));
        speedPxPerSec = SPEED_DP_PER_SEC * density;
    }

    /** Creates the overlay ImageViews and adds them to the window manager. */
    private void setupImages() {
        for (int i = 0; i < IMAGE_COUNT; i++) {

            // Spread the images diagonally so they do not start on top of each other
            float startX = (screenWidth  / (float) IMAGE_COUNT) * i;
            float startY = (screenHeight / (float) IMAGE_COUNT) * i;

            BouncingImage bi = new BouncingImage(startX, startY);

            bi.view = new ImageView(this);
            bi.view.setImageResource(R.drawable.hitler);

            // Overlay window parameters: drawn above other apps, does not take focus
            bi.params = new WindowManager.LayoutParams(
                    imageSizePx,
                    imageSizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            // LEFT (not START) so x is always measured from the left edge,
            // even when the device language is right-to-left (Arabic)
            bi.params.gravity = Gravity.TOP | Gravity.LEFT;

            // Make sure the starting position is inside the screen
            checkEdges(bi);
            bi.params.x = Math.round(bi.x);
            bi.params.y = Math.round(bi.y);

            bouncingImages.add(bi);
            windowManager.addView(bi.view, bi.params);
        }
    }

    /** Moves every image according to the elapsed time and applies the new position to its window. */
    private void updateImages(float deltaSec) {
        // Distance covered during this frame, in pixels
        float distance = speedPxPerSec * deltaSec;

        for (BouncingImage bi : bouncingImages) {
            bi.x += bi.xDirection * distance;
            bi.y += bi.yDirection * distance;

            // Bounce if an edge was reached
            checkEdges(bi);

            bi.params.x = Math.round(bi.x);
            bi.params.y = Math.round(bi.y);
            windowManager.updateViewLayout(bi.view, bi.params);
        }
    }

    /** Keeps the image inside the screen and reverses its direction on contact. */
    private void checkEdges(BouncingImage bi) {
        float maxX = screenWidth - imageSizePx;
        float maxY = screenHeight - imageSizePx;

        // Right edge
        if (bi.x >= maxX) {
            bi.x = maxX;
            bi.xDirection = -1;
        }
        // Left edge
        if (bi.x <= 0) {
            bi.x = 0;
            bi.xDirection = 1;
        }

        // Bottom edge
        if (bi.y >= maxY) {
            bi.y = maxY;
            bi.yDirection = -1;
        }
        // Top edge
        if (bi.y <= 0) {
            bi.y = 0;
            bi.yDirection = 1;
        }
    }

    /** Starts the looping audio track from res/raw. */
    private void setupAudio() {
        mediaPlayer = MediaPlayer.create(this, R.raw.erika);

        // create() returns null when the player could not be prepared: continue without audio
        if (mediaPlayer == null) {
            return;
        }

        mediaPlayer.setLooping(true);
        mediaPlayer.setVolume(0.8f, 0.8f);

        // On playback error, release the player so it does not stay in a broken state
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            mp.reset();
            mp.release();
            mediaPlayer = null;
            return true;
        });

        mediaPlayer.start();
    }

    /** Creates the notification channel (name and description come from string resources). */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        notificationManager.createNotificationChannel(channel);
    }

    /** Holds the state of one bouncing image: its view, window params, position and direction. */
    private static class BouncingImage {
        ImageView view;
        WindowManager.LayoutParams params;

        // Position in pixels (float so slow movement is not lost to rounding)
        float x;
        float y;

        // Direction on each axis: +1 = right/down, -1 = left/up
        int xDirection = 1;
        int yDirection = 1;

        BouncingImage(float startX, float startY) {
            this.x = startX;
            this.y = startY;
        }
    }

    /** Switches the launcher to the alternative icon and name. */
    private void changeIcon() {
        AppIconManager.useAlt(this);
    }
}
