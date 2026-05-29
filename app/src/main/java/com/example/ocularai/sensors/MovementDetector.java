package com.example.ocularai.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class MovementDetector implements SensorEventListener {
    private static final String TAG = "MovementDetector";

    //  Slightly lower threshold for better sensitivity
    private static final float MOVEMENT_THRESHOLD = 1.4f;
    private static final long STEP_INTERVAL_MS = 280;
    private static final float WALKING_DETECTION_WINDOW = 3;
    private static final int MAX_STEP_BUFFER = 8; //  Smaller buffer for faster state changes
    private static final long DECAY_INITIAL_DELAY_MS = 2000; // Wait 2s before starting decay
    private static final long DECAY_INTERVAL_MS = 600; //  Faster decay: 600ms per step removed

    private final SensorManager sensorManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isWalking = false;
    private float smoothedAccel = 9.8f;
    private final List<Float> recentSteps = new ArrayList<>();
    private long lastStepTime = 0;
    private boolean isSensorAvailable = false;

    public interface MovementCallback {
        void onWalkingStateChanged(boolean isWalking);
    }

    private final MovementCallback callback;

    //  Recursive decay: removes one step at a time for smooth transition
    private final Runnable decaySteps = new Runnable() {
        @Override
        public void run() {
            synchronized (recentSteps) {
                if (!recentSteps.isEmpty()) {
                    recentSteps.remove(0);
                }

                boolean wasWalking = isWalking;
                isWalking = recentSteps.size() > WALKING_DETECTION_WINDOW;

                //  Always post callback for consistent thread behavior
                if (wasWalking != isWalking && callback != null) {
                    mainHandler.post(() -> callback.onWalkingStateChanged(isWalking));
                }

                // Continue draining if steps remain
                if (!recentSteps.isEmpty()) {
                    mainHandler.postDelayed(this, DECAY_INTERVAL_MS);
                }
            }
        }
    };

    public MovementDetector(SensorManager sensorManager, MovementCallback callback) {
        this.sensorManager = sensorManager;
        this.callback = callback;
    }

    public boolean start() {
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            isSensorAvailable = sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

            if (!isSensorAvailable) {
                Log.w(TAG, "Failed to register accelerometer listener");
            }
            return isSensorAvailable;
        } else {
            Log.w(TAG, "Accelerometer not available on this device");
            return false;
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        mainHandler.removeCallbacks(decaySteps);
        synchronized (recentSteps) {
            recentSteps.clear();
            isWalking = false;
        }
        isSensorAvailable = false;
    }

    public boolean isWalking() {
        return isWalking;
    }

    public boolean isSensorAvailable() {
        return isSensorAvailable;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);

        // Low-pass filter to smooth sensor noise
        smoothedAccel = smoothedAccel * 0.85f + magnitude * 0.15f;
        float delta = Math.abs(magnitude - smoothedAccel);

        long currentTime = System.currentTimeMillis();

        if (delta > MOVEMENT_THRESHOLD && (currentTime - lastStepTime) > STEP_INTERVAL_MS) {
            synchronized (recentSteps) {
                recentSteps.add(delta);

                //  Smaller buffer = faster walking detection changes
                if (recentSteps.size() > MAX_STEP_BUFFER) {
                    recentSteps.remove(0);
                }

                boolean wasWalking = isWalking;
                isWalking = recentSteps.size() > WALKING_DETECTION_WINDOW;

                //  Always post to main thread for UI safety
                if (wasWalking != isWalking && callback != null) {
                    mainHandler.post(() -> callback.onWalkingStateChanged(isWalking));
                }
            }
            lastStepTime = currentTime;

            // Reset decay timer - user is active
            mainHandler.removeCallbacks(decaySteps);
            mainHandler.postDelayed(decaySteps, DECAY_INITIAL_DELAY_MS);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used for accelerometer
    }
}