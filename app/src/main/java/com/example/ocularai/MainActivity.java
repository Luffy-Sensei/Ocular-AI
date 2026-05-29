package com.example.ocularai;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.ocularai.camera.CameraManager;
import com.example.ocularai.detection.ObjectDetectorHelper;
import com.example.ocularai.detection.ObstacleTracker;
import com.example.ocularai.environment.EnvironmentAnalyzer;
import com.example.ocularai.sensors.MovementDetector;
import com.example.ocularai.speech.SpeechManager;
import com.example.ocularai.ui.UIController;
import com.example.ocularai.utils.HapticFeedback;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "OcularAI";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final float SPEECH_CONFIDENCE_THRESHOLD = 0.65f;

    // Managers
    private CameraManager cameraManager;
    private ObjectDetectorHelper detectorHelper;
    private ObstacleTracker obstacleTracker;
    private SpeechManager speechManager;
    private MovementDetector movementDetector;
    private EnvironmentAnalyzer environmentAnalyzer;
    private UIController uiController;
    private HapticFeedback hapticFeedback;

    // Views
    private PreviewView previewView;
    private View scanLineView;
    private View micBtn;

    private boolean isCameraStarting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        initManagers();

        findViewById(android.R.id.content).post(this::startScanAnimation);
        checkPermissions();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        scanLineView = findViewById(R.id.scanLine);
        micBtn = findViewById(R.id.micBtn);

        if (previewView != null) {
            previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        }

        uiController = new UIController(findViewById(android.R.id.content));

        if (micBtn != null) {
            micBtn.setOnClickListener(v -> {
                if (speechManager != null) {
                    if (speechManager.isCurrentlyListening()) {
                        speechManager.stopListening();
                        Toast.makeText(MainActivity.this, "Voice commands paused", Toast.LENGTH_SHORT).show();
                    } else {
                        speechManager.startListening();
                        Toast.makeText(MainActivity.this, "Listening for commands...", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void initManagers() {
        obstacleTracker = new ObstacleTracker();
        detectorHelper = new ObjectDetectorHelper(this, "efficientdet_lite0.tflite");

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        hapticFeedback = new HapticFeedback(this);

        speechManager = new SpeechManager(this, this::onSpeechStatusChanged);
        speechManager.initTTS();

        movementDetector = new MovementDetector(
                (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE),
                this::onWalkingStateChanged
        );

        environmentAnalyzer = new EnvironmentAnalyzer(new EnvironmentAnalyzer.EnvironmentCallback() {
            @Override
            public void onEnvironmentChanged(boolean isLowLight, boolean isCameraBlocked) {
                updateEnvironmentUI(isLowLight, isCameraBlocked);
            }

            @Override
            public void onNeedSpeech(String text, int queueMode) {
                speechManager.speak(text, queueMode);
            }

            @Override
            public void onNeedVibration() {
                hapticFeedback.vibrateLong();
            }

            @Override
            public void onRecoverySpeech(String text, int queueMode) {
                speechManager.speak(text, queueMode);
            }
        });

        cameraManager = new CameraManager(new CameraManager.FrameCallback() {
            @Override
            public void onFrameProcessed(Bitmap bitmapFrame, int rotationDegrees,
                                         androidx.camera.core.ImageProxy imageProxy, int frameWidth, int frameHeight) {
                processCameraFrame(bitmapFrame, rotationDegrees, imageProxy, frameWidth, frameHeight);
            }

            @Override
            public void onBrightnessAnalyzed(float avgBrightness) {
                environmentAnalyzer.analyzeBrightness(avgBrightness);
            }

            @Override
            public boolean isCameraBlocked() {
                return environmentAnalyzer.isCameraBlocked();
            }

            @Override
            public boolean isWalking() {
                return movementDetector.isWalking();
            }

            @Override
            public void onDetectionComplete(long inferenceTime) {
                cameraManager.updateAdaptiveInterval(inferenceTime, movementDetector.isWalking());
            }
        });
    }

    private void processCameraFrame(Bitmap bitmapFrame, int rotationDegrees,
                                    androidx.camera.core.ImageProxy imageProxy, int frameWidth, int frameHeight) {
        if (previewView == null || detectorHelper == null || bitmapFrame == null) {
            if (imageProxy != null) imageProxy.close();
            return;
        }

        try {
            TensorImage safeTensorImage = new TensorImage(org.tensorflow.lite.DataType.UINT8);
            safeTensorImage.load(bitmapFrame);

            List<Detection> detections = detectorHelper.detect(safeTensorImage, rotationDegrees);

            if (detections != null && !detections.isEmpty()) {
                ObstacleTracker.ObstacleInfo info = obstacleTracker.processDetections(detections, frameWidth, frameHeight);

                if (info != null) {
                    runOnUiThread(() -> {
                        uiController.updateDetection(info.className, info.direction, info.distance,
                                info.objectCount, info.isStable, info.isDanger, info.confidence);

                        speechManager.updateLastUI(uiController.getLastUIText(),
                                uiController.getLastUIDirection(),
                                uiController.getLastUIDistance());
                    });

                    String speechKey = info.className.toLowerCase(Locale.US) + "_" + info.direction;
                    long currentTime = System.currentTimeMillis();

                    if (speechManager.canAnnounce(speechKey, currentTime) &&
                            info.isStable && info.confidence > SPEECH_CONFIDENCE_THRESHOLD &&
                            !environmentAnalyzer.isCameraBlocked()) {

                        String announcement = buildAnnouncement(info);
                        int queueMode = info.isDanger ? android.speech.tts.TextToSpeech.QUEUE_FLUSH :
                                android.speech.tts.TextToSpeech.QUEUE_ADD;

                        speechManager.speak(announcement, queueMode);
                        speechManager.recordAnnouncement(speechKey, currentTime);

                        hapticFeedback.triggerForObject(info.className.toLowerCase(Locale.US), info.isDanger);
                    }
                } else {
                    // If detections exist but tracker returned no valid obstacle info, show scanning state
                    runOnUiThread(() -> {
                        uiController.updateDetection("SCANNING", "NONE", "CLEAR", 0, true, false, 0.0f);
                    });
                }
            } else {
                //  Fix: If model returns 0 detections, clear the "INITIALIZING..." placeholder text immediately
                runOnUiThread(() -> {
                    uiController.updateDetection("CLEAR", "NONE", "CLEAR", 0, true, false, 0.0f);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing camera frame", e);
        } finally {
            //  Recycle Bitmap to free native memory immediately
            if (bitmapFrame != null && !bitmapFrame.isRecycled()) {
                bitmapFrame.recycle();
            }
            if (imageProxy != null) {
                imageProxy.close();
            }
        }
    }

    private String buildAnnouncement(ObstacleTracker.ObstacleInfo info) {
        String spokenDirection = info.direction.equals("CENTER") ? "ahead" : info.direction.toLowerCase(Locale.US);

        if (info.isDanger) {
            if (info.distance.equals("APPROACHING")) {
                return "Warning. " + info.className + " approaching fast from " + spokenDirection;
            }
            return "Warning. " + info.className + " directly ahead, very close";
        }

        String spokenDistance = info.distance.toLowerCase(Locale.US);

        if (environmentAnalyzer.isLowLight()) {
            return "Careful, " + info.className + " on your " + spokenDirection + ", " + spokenDistance;
        }

        return info.className + " on your " + spokenDirection + ", " + spokenDistance;
    }

    private void updateEnvironmentUI(boolean isLowLight, boolean isCameraBlocked) {
        if (isCameraBlocked) {
            uiController.updateEnvironment("LENS BLOCKED", Color.RED, "BLOCKED");
        } else if (isLowLight) {
            uiController.updateEnvironment("LOW LIGHT", Color.parseColor("#FFA500"), "LOW LIGHT");
        } else {
            uiController.updateEnvironment("GOOD LIGHT", Color.parseColor("#00FFCC"), "READY");
        }
    }

    private void onWalkingStateChanged(boolean isWalking) {
        uiController.updateWalkingState(isWalking);
    }

    private void onSpeechStatusChanged(boolean isListening) {
        uiController.setMicButtonAlpha(isListening ? 1.0f : 0.6f);
    }

    private void startScanAnimation() {
        if (scanLineView == null) return;

        View parent = (View) scanLineView.getParent();
        float endY = parent != null ? parent.getHeight() : 900f;

        ObjectAnimator animator = ObjectAnimator.ofFloat(scanLineView, "translationY", 0f, endY);
        animator.setDuration(2200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    private void checkPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera and Audio permissions are required for Ocular AI", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        if (cameraManager != null && previewView != null && !isCameraStarting) {
            isCameraStarting = true;
            cameraManager.startCamera(this, previewView);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (movementDetector != null) {
            boolean available = movementDetector.start();
            if (!available) {
                Toast.makeText(this, "Step tracking unavailable on this device", Toast.LENGTH_SHORT).show();
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isCameraStarting = false;
        if (movementDetector != null) movementDetector.stop();
        if (speechManager != null) {
            speechManager.stop();
            if (speechManager.isCurrentlyListening()) {
                speechManager.stopListening();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraManager != null) cameraManager.shutdown();
        if (detectorHelper != null) detectorHelper.close();
        if (speechManager != null) speechManager.shutdown();
        if (hapticFeedback != null) hapticFeedback.cancel();
    }
}