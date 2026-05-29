package com.example.ocularai.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import org.tensorflow.lite.support.image.TensorImage;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class CameraManager {
    private static final String TAG = "CameraManager";

    private ProcessCameraProvider cameraProvider;
    private final ExecutorService cameraExecutor;
    private final FrameCallback callback;
    private final AtomicLong lastAnalysisTime = new AtomicLong(0);
    private final AtomicLong lastInferenceTime = new AtomicLong(0);
    private volatile long adaptiveInterval = 500L;

    public interface FrameCallback {
        void onFrameProcessed(Bitmap bitmapFrame, int rotationDegrees,
                              ImageProxy imageProxy, int frameWidth, int frameHeight);
        void onBrightnessAnalyzed(float avgBrightness);
        boolean isCameraBlocked();
        boolean isWalking();
        void onDetectionComplete(long inferenceTime);
    }

    public CameraManager(FrameCallback callback) {
        this.callback = callback;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    public void startCamera(LifecycleOwner lifecycleOwner, PreviewView previewView) {
        final Context context;
        if (lifecycleOwner instanceof Context) {
            context = (Context) lifecycleOwner;
        } else {
            Log.e(TAG, "LifecycleOwner is not a Context");
            return;
        }

        ProcessCameraProvider.getInstance(context).addListener(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(context).get();
                bindCamera(lifecycleOwner, previewView);
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindCamera(LifecycleOwner lifecycleOwner, PreviewView previewView) {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            boolean shouldCloseProxyImmediately = true;
            try {
                long currentTime = System.currentTimeMillis();
                long lastAnalysis = lastAnalysisTime.get();

                long targetInterval = callback.isWalking() ? 400L : 600L;
                if (adaptiveInterval > targetInterval) {
                    targetInterval = adaptiveInterval;
                }

                if (currentTime - lastAnalysis < targetInterval) {
                    return; // Closed in finally
                }
                lastAnalysisTime.set(currentTime);

                Image mediaImage = imageProxy.getImage();
                if (mediaImage == null) {
                    return;
                }

                // Analyze brightness BEFORE any heavy processing
                analyzeBrightness(imageProxy);

                if (callback.isCameraBlocked()) {
                    return;
                }

                long conversionStart = System.currentTimeMillis();

                //  SAFE: Convert YUV to RGB Bitmap (works with TFLite)
                Bitmap bitmapFrame = imageProxy.toBitmap();
                if (bitmapFrame == null) {
                    return;
                }

                int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                int[] dimensions = getFrameDimensions(mediaImage.getWidth(), mediaImage.getHeight(), rotationDegrees);

                //  Transfer ownership to callback (MainActivity handles .close())
                shouldCloseProxyImmediately = false;
                callback.onFrameProcessed(bitmapFrame, rotationDegrees, imageProxy, dimensions[0], dimensions[1]);

                //  Measure total time including conversion
                long totalProcessingTime = System.currentTimeMillis() - conversionStart;
                callback.onDetectionComplete(totalProcessingTime);

            } catch (Exception e) {
                Log.e(TAG, "Frame processing failed", e);
                shouldCloseProxyImmediately = true;
            } finally {
                if (shouldCloseProxyImmediately) {
                    imageProxy.close();
                }
            }
        });

        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            Log.d(TAG, "Camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Binding use cases failed", e);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeBrightness(ImageProxy imageProxy) {
        try {
            Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) return;

            Image.Plane yPlane = mediaImage.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();

            int width = mediaImage.getWidth();
            int height = mediaImage.getHeight();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();

            long sum = 0;
            int sampleCount = 0;
            int sampleStep = 10;
            byte[] rowData = new byte[rowStride];

            for (int row = 0; row < height; row += sampleStep) {
                yBuffer.position(row * rowStride);
                yBuffer.get(rowData, 0, Math.min(rowStride, rowData.length));

                for (int col = 0; col < width; col += sampleStep) {
                    int pixelIndex = col * pixelStride;
                    if (pixelIndex < rowData.length) {
                        sum += (rowData[pixelIndex] & 0xFF);
                        sampleCount++;
                    }
                }
            }

            float avgBrightness = sampleCount > 0 ? (float) sum / sampleCount : 128f;
            callback.onBrightnessAnalyzed(avgBrightness);

        } catch (Exception e) {
            Log.e(TAG, "Brightness analysis failed", e);
        }
    }

    private int[] getFrameDimensions(int width, int height, int rotationDegrees) {
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }

    public void updateAdaptiveInterval(long inferenceTime, boolean isWalking) {
        //  FIXED: Actually store the inference time
        lastInferenceTime.set(inferenceTime);

        long baseInterval = isWalking ? 400L : 600L;

        if (inferenceTime > baseInterval) {
            adaptiveInterval = inferenceTime + 50L;
        } else {
            adaptiveInterval = baseInterval;
        }
        adaptiveInterval = Math.min(adaptiveInterval, 1000L);
    }

    public void shutdown() {
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}