package com.miku.something;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Entry point of the app.
 *
 * The activity has no UI (it uses a transparent theme and never sets a layout):
 * it only makes sure the required permissions (draw over other apps +
 * notifications) are granted, starts the foreground service, and closes itself.
 */
public class MainActivity extends AppCompatActivity {

    // Launches the system "Display over other apps" screen and receives the result.
    // If the user granted the permission we continue to the notification check,
    // otherwise we explain why it is needed and close the app.
    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) {
                    checkAndRequestNotificationPermission();
                } else {
                    Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    // Asks for the POST_NOTIFICATIONS runtime permission (Android 13+).
    // Granted -> start the service. Denied -> explain and close the app.
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startBouncingService();
                } else {
                    Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Step 1: the overlay permission. If missing, open the system settings page
        // for this package; otherwise go straight to the notification permission.
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
        } else {
            checkAndRequestNotificationPermission();
        }
    }

    /**
     * Step 2: the notification permission.
     * It is only a runtime permission on Android 13 (TIRAMISU) and above,
     * on older versions we can start the service directly.
     */
    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            } else {
                startBouncingService();
            }
        } else {
            startBouncingService();
        }
    }

    /**
     * Step 3: start the foreground service (unless it is already running)
     * and close the activity, since there is nothing to show.
     */
    private void startBouncingService() {
        if (ForegroundService.isRunning) {
            finish();
            return;
        }
        Intent intent = new Intent(this, ForegroundService.class);
        startForegroundService(intent);
        finish();
    }
}
