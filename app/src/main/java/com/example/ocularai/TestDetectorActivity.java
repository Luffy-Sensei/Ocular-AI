package com.example.ocularai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.ocularai.detection.ObjectDetectorHelper;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestDetectorActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 101;
    private PreviewView previewView;
    private TextView resultText;
    private ExecutorService cameraExecutor;
    private int frameCount = 0;

    // FIXED: Swapped out ML Kit for your custom TFLite Helper
    private ObjectDetectorHelper detectorHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_detector);

        previewView = findViewById(R.id.previewView);
        resultText = findViewById(R.id.resultText);

        if (previewView == null || resultText == null) {
            Toast.makeText(this, "Layout error: Views not found", Toast.LENGTH_LONG).show();
            return;
        }

        resultText.setText("App: Starting...");

        // FIXED: Instantiating the same local model model asset
        detectorHelper = new ObjectDetectorHelper(this, "efficientdet_lite0.tflite");
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            resultText.setText("Requesting camera permission...");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        resultText.setText("Initializing camera...");

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    frameCount++;

                    // FIXED: Read frames directly into our TFLite tensor pipeline
                    if (imageProxy != null && detectorHelper != null) {
                        try {
                            android.media.Image mediaImage = imageProxy.getImage();
                            if (mediaImage != null) {
                                int rotation = imageProxy.getImageInfo().getRotationDegrees();
                                TensorImage tensorImage = new TensorImage(org.tensorflow.lite.DataType.UINT8);
                                tensorImage.load(mediaImage);

                                List<Detection> detections = detectorHelper.detect(tensorImage, rotation);
                                updateUI(detections);
                            }
                        } catch (Exception e) {
                            android.util.Log.e("OcularAI_Test", "Inference mismatch pass", e);
                        } finally {
                            imageProxy.close(); // Crucial to prevent buffer blocking
                        }
                    } else if (imageProxy != null) {
                        imageProxy.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

                runOnUiThread(() -> resultText.setText("READY - Point camera at objects"));

            } catch (Exception e) {
                runOnUiThread(() -> resultText.setText("Camera Error: " + e.getMessage()));
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // FIXED: Formats output using TFLite's Detection classes cleanly
    private void updateUI(List<Detection> detections) {
        runOnUiThread(() -> {
            if (detections == null || detections.isEmpty()) {
                resultText.setText("Frame " + frameCount + ": Nothing detected");
            } else {
                Detection top = detections.get(0);
                String label = "Unclassified";
                float score = 0f;

                if (!top.getCategories().isEmpty()) {
                    label = top.getCategories().get(0).getLabel();
                    score = top.getCategories().get(0).getScore();
                }

                resultText.setText(String.format(java.util.Locale.US, "Frame %d: %s (%.0f%%)", frameCount, label, score * 100));
                android.util.Log.d("OcularAI_Test", "Detected: " + label + " (" + (score * 100) + "%)");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            runOnUiThread(() -> resultText.setText("Camera permission required"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (detectorHelper != null) {
            detectorHelper.close();
        }
    }
}