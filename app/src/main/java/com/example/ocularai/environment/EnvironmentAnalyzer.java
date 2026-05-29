package com.example.ocularai.environment;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

public class EnvironmentAnalyzer {
    private static final String TAG = "EnvironmentAnalyzer";
    private static final float BLOCKED_THRESHOLD = 20f;
    private static final float LOW_LIGHT_THRESHOLD = 55f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isLowLight = false;
    private volatile boolean isCameraBlocked = false;
    private boolean lastLightWarningSpoken = false;
    private boolean lastBlockedWarningSpoken = false;

    public interface EnvironmentCallback {
        void onEnvironmentChanged(boolean isLowLight, boolean isCameraBlocked);
        void onNeedSpeech(String text, int queueMode);
        void onNeedVibration();
        void onRecoverySpeech(String text, int queueMode);
    }

    private final EnvironmentCallback callback;

    public EnvironmentAnalyzer(EnvironmentCallback callback) {
        this.callback = callback;
    }

    public void analyzeBrightness(float avgBrightness) {
        if (callback == null) return;

        boolean wasLowLight = this.isLowLight;
        boolean wasBlocked = this.isCameraBlocked;

        //  Mutually exclusive state detection
        // BLOCKED: avgBrightness < 20 (lens covered or extreme darkness)
        // LOW_LIGHT: 20 ≤ avgBrightness < 55 (dim but usable)
        // GOOD: avgBrightness ≥ 55 (normal lighting)
        boolean currentBlocked = avgBrightness < BLOCKED_THRESHOLD;
        boolean currentLowLight = !currentBlocked && avgBrightness < LOW_LIGHT_THRESHOLD;

        if (currentBlocked) {
            // Transition to BLOCKED state
            this.isCameraBlocked = true;
            this.isLowLight = false;

            if (!wasBlocked) {
                Log.w(TAG, "Camera blocked detected. Brightness: " + avgBrightness);
                notifyEnvironmentChanged(false, true);

                if (!lastBlockedWarningSpoken) {
                    callback.onNeedSpeech("Camera lens blocked. Please clean the lens.", TextToSpeech.QUEUE_FLUSH);
                    lastBlockedWarningSpoken = true;
                    lastLightWarningSpoken = false;
                }
                callback.onNeedVibration();
            }

        } else if (currentLowLight) {
            // Transition to LOW LIGHT state
            this.isLowLight = true;
            this.isCameraBlocked = false;

            if (!wasLowLight || wasBlocked) {
                Log.d(TAG, "Low light detected. Brightness: " + avgBrightness);
                notifyEnvironmentChanged(true, false);

                if (!lastLightWarningSpoken) {
                    callback.onNeedSpeech("Low light condition. Move slowly and carefully.", TextToSpeech.QUEUE_ADD);
                    lastLightWarningSpoken = true;
                    lastBlockedWarningSpoken = false;
                }
            }

        } else {
            // Transition to GOOD LIGHT state
            this.isLowLight = false;
            this.isCameraBlocked = false;

            if (wasLowLight || wasBlocked) {
                Log.d(TAG, "Lighting restored. Brightness: " + avgBrightness);
                notifyEnvironmentChanged(false, false);

                if (wasBlocked) {
                    callback.onRecoverySpeech("Camera recovered. Resuming normal operation.", TextToSpeech.QUEUE_FLUSH);
                }

                // Reset warning flags so they can fire again if conditions degrade
                lastLightWarningSpoken = false;
                lastBlockedWarningSpoken = false;
            }
        }
    }

    private void notifyEnvironmentChanged(boolean lowLight, boolean blocked) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onEnvironmentChanged(lowLight, blocked);
            }
        });
    }

    public boolean isLowLight() {
        return isLowLight;
    }

    public boolean isCameraBlocked() {
        return isCameraBlocked;
    }
}