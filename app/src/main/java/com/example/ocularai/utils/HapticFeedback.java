package com.example.ocularai.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

public class HapticFeedback {
    private static final String TAG = "HapticFeedback";
    private final Vibrator vibrator;

    /**
     * Modern constructor using Context.
     * Handles Android 12+ VibratorManager and legacy Vibrator service.
     */
    @SuppressWarnings("deprecation") //  Suppress legacy API warning
    public HapticFeedback(Context context) {
        Vibrator detectedVibrator = null;

        if (context != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31): Use VibratorManager
                VibratorManager vibratorManager = (VibratorManager)
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null) {
                    detectedVibrator = vibratorManager.getDefaultVibrator();
                }
            } else {
                // Legacy: Use VIBRATOR_SERVICE directly
                detectedVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        }

        this.vibrator = detectedVibrator;
    }

    /**
     * Legacy constructor for backwards compatibility.
     * Accepts a pre-obtained Vibrator instance.
     */
    public HapticFeedback(Vibrator vibrator) {
        this.vibrator = vibrator;
    }

    /**
     * Triggers distinct haptic patterns based on object type and danger level.
     * Different patterns help visually impaired users identify threats non-verbally.
     */
    public void triggerForObject(String label, boolean isDanger) {
        if (!isHardwareAvailable()) return;
        if (label == null) label = "";

        if (isDanger) {
            // Intense double-pulse for immediate danger
            vibrate(new long[]{0, 500, 150, 500});
        } else if (label.equals("person")) {
            // Quick double-tap for people nearby
            vibrate(new long[]{0, 150, 80, 150});
        } else if (label.contains("car") || label.contains("bus") || label.contains("truck")) {
            // Long double-rumble for vehicles
            vibrate(new long[]{0, 600, 200, 600});
        } else {
            // Single short tap for general objects
            vibrateOneShot(180);
        }
    }

    /**
     * Long vibration for environmental warnings (lens blocked, etc.)
     */
    public void vibrateLong() {
        if (!isHardwareAvailable()) return;
        vibrate(new long[]{0, 600, 300, 600});
    }

    /**
     * Emergency pattern - triple pulse for urgent situations.
     */
    public void vibrateEmergency() {
        if (!isHardwareAvailable()) return;
        vibrate(new long[]{0, 800, 300, 800, 300, 800});
    }

    /**
     * Creates a waveform vibration pattern.
     * Handles API 26+ VibrationEffect and legacy vibrate().
     */
    private void vibrate(long[] pattern) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Waveform vibration failed", e);
        }
    }

    /**
     * Creates a single continuous vibration.
     * Handles API 26+ VibrationEffect and legacy vibrate().
     */
    private void vibrateOneShot(long duration) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        } catch (Exception e) {
            Log.e(TAG, "One-shot vibration failed", e);
        }
    }

    /**
     * Checks if the device has a vibrator motor available.
     */
    private boolean isHardwareAvailable() {
        return vibrator != null && vibrator.hasVibrator();
    }

    /**
     * Cancels any ongoing vibration.
     */
    public void cancel() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }
}