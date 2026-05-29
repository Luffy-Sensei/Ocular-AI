package com.example.ocularai.detection;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectDetectorHelper {
    private static final String TAG = "ObjectDetectorHelper";

    //  Constants for easy tuning
    private static final int MODEL_INPUT_SIZE = 320;
    private static final int MAX_DETECTIONS = 5;
    private static final float SCORE_THRESHOLD = 0.50f; // Raised for production

    private ObjectDetector objectDetector;
    private boolean isInitialized = false;

    //  Cache processors by rotation angle (created ONCE, reused forever)
    private final Map<Integer, ImageProcessor> processorCache = new HashMap<>();
    private ImageProcessor resizeOnlyProcessor; // For 0° rotation

    public ObjectDetectorHelper(Context context, String modelName) {
        initDetector(context, modelName);
        initProcessors();
    }

    private void initDetector(Context context, String modelName) {
        try {
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setMaxResults(MAX_DETECTIONS)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .build();

            //  Use ApplicationContext to prevent memory leaks
            objectDetector = ObjectDetector.createFromFileAndOptions(
                    context.getApplicationContext(), modelName, options);

            isInitialized = true;
            Log.d(TAG, "Model loaded successfully: " + modelName);

        } catch (IOException e) {
            isInitialized = false;
            Log.e(TAG, "TFLite model initialization failed: " + e.getMessage());
        }
    }

    //  Create all processors ONCE at startup
    private void initProcessors() {
        // Base resize processor (no rotation)
        resizeOnlyProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .build();

        // Pre-build processors for each rotation: 0°, 90°, 180°, 270°
        for (int rot : new int[]{0, 1, 2, 3}) {
            ImageProcessor processor = new ImageProcessor.Builder()
                    .add(new ResizeOp(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new Rot90Op(rot)) //  Positive rotation (clockwise)
                    .build();
            processorCache.put(rot * 90, processor);
        }
    }

    //  Get cached processor for specific rotation
    private ImageProcessor getProcessorForRotation(int rotationDegrees) {
        // Normalize rotation to 0, 90, 180, 270
        int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        int cacheKey = (normalizedRotation / 90) * 90;

        ImageProcessor processor = processorCache.get(cacheKey);
        if (processor == null) {
            Log.w(TAG, "No cached processor for rotation " + rotationDegrees + ", using 0°");
            return resizeOnlyProcessor;
        }
        return processor;
    }

    public boolean isReady() {
        return isInitialized && objectDetector != null;
    }

    public List<Detection> detect(TensorImage tensorImage, int rotationDegrees) {
        if (!isReady()) {
            Log.w(TAG, "Detector not ready - skipping frame");
            return null;
        }

        try {
            //  Get pre-built processor from cache (no new allocations!)
            ImageProcessor imageProcessor = getProcessorForRotation(rotationDegrees);

            //  Process and return results (doesn't modify original tensorImage reference)
            TensorImage processedImage = imageProcessor.process(tensorImage);
            return objectDetector.detect(processedImage);

        } catch (Exception e) {
            Log.e(TAG, "Detection error: " + e.getMessage());
            return null;
        }
    }

    public void close() {
        if (objectDetector != null) {
            objectDetector.close();
            objectDetector = null;
            isInitialized = false;
        }

        // Clear caches
        processorCache.clear();
        resizeOnlyProcessor = null;
    }
}