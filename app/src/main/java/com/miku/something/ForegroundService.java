package com.miku.something;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that:
 *  - shows several images bouncing around the screen in ONE full-screen overlay,
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

    // Opacity of the overlay window (1 = fully opaque).
    // Android 12+ blocks touches to the apps below a pass-through overlay that is more opaque
    // than 0.8, so this value keeps the phone usable while the overlay is shown.
    private static final float OVERLAY_ALPHA = 0.8f;

    private NotificationManager notificationManager;
    private WindowManager windowManager;

    // Image size and speed converted from dp to pixels
    private int imageSizePx;
    private float speedPxPerSec;

    // The single overlay view that draws and animates all the images
    private BouncingView bouncingView;

    // Lets MainActivity know whether the service is already running
    public static boolean isRunning = false;

    // True once the overlay and the audio were created, so a repeated
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

            // Move the images, then ask the view to redraw in this same frame
            bouncingView.step(speedPxPerSec * Math.min(deltaSec, MAX_FRAME_DELTA_SEC));
            bouncingView.invalidate();

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

        // Compute the pixel values used by the animation
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

        // Already set up: do not create a second overlay and a second player
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
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isSetupDone = false;

        // Stop the animation loop
        Choreographer.getInstance().removeFrameCallback(frameCallback);

        // Remove the overlay from the window manager
        if (bouncingView != null && bouncingView.isAttachedToWindow()) {
            windowManager.removeView(bouncingView);
        }
        bouncingView = null;

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

    /** Converts the dp constants to pixels, never letting an image exceed the smaller screen side. */
    @SuppressWarnings("deprecation") // getRealMetrics is only used below API 30
    private void updateScreenMetrics() {
        int screenWidth;
        int screenHeight;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API: bounds of the whole screen
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
        imageSizePx = Math.round(Math.min(IMAGE_SIZE_DP * density, Math.min(screenWidth, screenHeight)));
        speedPxPerSec = SPEED_DP_PER_SEC * density;
    }

    /** Creates the single full-screen overlay that draws all the images. */
    private void setupImages() {
        // Decode the image once, already scaled to its final size
        Bitmap bitmap = loadScaledBitmap(R.drawable.hitler, imageSizePx);
        bouncingView = new BouncingView(this, bitmap, imageSizePx, IMAGE_COUNT);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Does not take focus and lets touches pass through to the apps below
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        // Cover the whole screen, including the status and navigation bars
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // Render with the GPU. Overlay windows are NOT hardware accelerated
                        // by default, which makes animations stutter.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.alpha = OVERLAY_ALPHA;

        // Also draw under the camera cutout (notch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        windowManager.addView(bouncingView, params);
    }

    /**
     * Decodes a drawable resource directly at the size it will be shown,
     * keeping its aspect ratio (same as ImageView's default "fit center").
     * Decoding at the final size saves memory and avoids scaling work on every frame.
     */
    private Bitmap loadScaledBitmap(int resId, int targetSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // Do not let the system rescale the bitmap according to screen density
        options.inScaled = false;

        // First pass: read only the image dimensions
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), resId, options);

        // Largest power-of-two subsampling that still leaves the image at least as big as the target
        int sampleSize = 1;
        while (options.outWidth / (sampleSize * 2) >= targetSize
                && options.outHeight / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2;
        }

        // Second pass: decode the pixels
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        Bitmap decoded = BitmapFactory.decodeResource(getResources(), resId, options);

        // Final exact scaling so the image fits inside the square box
        float scale = Math.min(
                targetSize / (float) decoded.getWidth(),
                targetSize / (float) decoded.getHeight());
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));

        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
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

    /** Switches the launcher to the alternative icon and name. */
    private void changeIcon() {
        AppIconManager.useAlt(this);
    }
}
