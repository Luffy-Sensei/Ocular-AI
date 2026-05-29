package com.example.ocularai.detection;

import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ObstacleTracker {
    private static final String TAG = "ObstacleTracker";
    private static final long OBJECT_HISTORY_MS = 3000;
    private static final long OBSTACLE_TRACKING_MS = 2000;
    private static final int STABLE_FRAME_THRESHOLD = 3;
    private static final float APPROACHING_SPEED_THRESHOLD = 100f;
    private static final int CLEANUP_INTERVAL_FRAMES = 10; //  Clean every 10 frames
    private static final float MAX_TRACK_DISTANCE = 150f; // Max pixel drift for matching

    //  Static - only one copy for all instances
    private static final Map<String, Integer> PRIORITY_MAP = new HashMap<String, Integer>() {{
        put("person", 0);
        put("cat", 0); put("dog", 0);
        put("bicycle", 1); put("car", 1); put("motorcycle", 1);
        put("bus", 1); put("truck", 1);
        put("door", 2);
        put("chair", 3); put("couch", 3); put("bed", 3);
        put("dining table", 3);
        put("bottle", 4); put("cup", 4); put("book", 4);
    }};

    private final Map<String, PriorityObstacle> trackedObstacles = new HashMap<>();
    private final Map<String, Integer> objectStabilityCounter = new LinkedHashMap<>();
    private final Map<String, Long> objectHistory = new LinkedHashMap<>();
    private int consecutiveEmpty = 0;
    private int frameCount = 0; //  Track frames for lazy cleanup

    public static class ObstacleInfo {
        public String className;
        public String direction;
        public String distance;
        public int objectCount;
        public boolean isStable;
        public boolean isDanger;
        public float confidence;

        public ObstacleInfo(String className, String direction, String distance,
                            int objectCount, boolean isStable, boolean isDanger, float confidence) {
            this.className = className;
            this.direction = direction;
            this.distance = distance;
            this.objectCount = objectCount;
            this.isStable = isStable;
            this.isDanger = isDanger;
            this.confidence = confidence;
        }
    }

    //  Lazy cleanup - only runs every N frames
    private void lazyCleanup(long currentTime) {
        if (++frameCount % CLEANUP_INTERVAL_FRAMES != 0) return;
        cleanup(currentTime);
    }

    private void cleanup(long currentTime) {
        // Clean history
        Iterator<Map.Entry<String, Long>> historyIterator = objectHistory.entrySet().iterator();
        while (historyIterator.hasNext()) {
            if (currentTime - historyIterator.next().getValue() > OBJECT_HISTORY_MS) {
                historyIterator.remove();
            }
        }

        // Clean stability counters with safe null check
        Iterator<Map.Entry<String, Integer>> stabilityIterator = objectStabilityCounter.entrySet().iterator();
        while (stabilityIterator.hasNext()) {
            Map.Entry<String, Integer> entry = stabilityIterator.next();
            Long lastSeenTime = objectHistory.get(entry.getKey());
            if (lastSeenTime == null || (currentTime - lastSeenTime > OBJECT_HISTORY_MS)) {
                stabilityIterator.remove();
            }
        }

        // Clean tracked obstacles
        Iterator<Map.Entry<String, PriorityObstacle>> obstacleIterator = trackedObstacles.entrySet().iterator();
        while (obstacleIterator.hasNext()) {
            if (currentTime - obstacleIterator.next().getValue().lastSeen > OBSTACLE_TRACKING_MS) {
                obstacleIterator.remove();
            }
        }
    }

    public ObstacleInfo processDetections(List<Detection> detections, int frameWidth, int frameHeight) {
        long currentTime = System.currentTimeMillis();
        lazyCleanup(currentTime); //  Runs cleanup only every 10 frames

        if (detections == null || detections.isEmpty()) {
            consecutiveEmpty++;
            if (consecutiveEmpty >= 15) {
                return new ObstacleInfo("CLEAR", "---", "---", 0, false, false, 0f);
            }
            return null;
        }

        Log.d(TAG, "Detected: " + detections.size() + " objects");
        consecutiveEmpty = 0;

        List<PriorityObstacle> obstacles = new ArrayList<>();

        for (Detection detection : detections) {
            if (detection.getCategories().isEmpty()) continue;

            RectF box = detection.getBoundingBox();
            //  Null safety for bounding box
            if (box == null) continue;

            String className = detection.getCategories().get(0).getLabel();
            if (className == null) className = "object";
            else className = className.toLowerCase(Locale.US);

            float confidence = detection.getCategories().get(0).getScore();
            float actualCenterX = box.centerX();
            float areaRatio = (box.width() * box.height()) / (float)(frameWidth * frameHeight);

            //  Smart tracking - finds closest existing obstacle
            String trackKey = findBestExistingTrackKey(className, actualCenterX);

            PriorityObstacle obstacle = new PriorityObstacle(detection, className, confidence,
                    actualCenterX, areaRatio, currentTime);

            // Update movement tracking from previous frame
            if (trackedObstacles.containsKey(trackKey)) {
                PriorityObstacle prev = trackedObstacles.get(trackKey);
                float distanceMoved = Math.abs(actualCenterX - prev.centerX);
                long timeDelta = currentTime - prev.lastSeen;

                float smoothingFactor = 0.3f;
                obstacle.smoothedAreaRatio = prev.smoothedAreaRatio * (1 - smoothingFactor) + areaRatio * smoothingFactor;
                float areaChange = obstacle.smoothedAreaRatio - prev.smoothedAreaRatio;

                if (timeDelta > 0 && timeDelta < 500) {
                    obstacle.speed = distanceMoved * 1000f / timeDelta;
                    obstacle.isMoving = obstacle.speed > 50f;
                    obstacle.isApproaching = areaChange > 0.015f;
                }
                obstacle.lastAreaRatio = prev.areaRatio;
            }

            trackedObstacles.put(trackKey, obstacle);
            obstacles.add(obstacle);
        }

        //  Removed redundant isEmpty check (can't happen if detections wasn't empty)
        PriorityObstacle best = getBestObstacle(obstacles, frameWidth);
        return createObstacleInfo(best, obstacles.size(), frameWidth, currentTime);
    }

    /**
     *  Optimized: Finds closest existing track for smooth object tracking.
     * Instead of rigid grid cells, matches to nearest obstacle of same class.
     */
    private String findBestExistingTrackKey(String className, float currentCenterX) {
        String defaultKey = className + "_" + ((int) (currentCenterX / 200));
        float minimumDistance = MAX_TRACK_DISTANCE;
        String bestKey = defaultKey;

        for (Map.Entry<String, PriorityObstacle> entry : trackedObstacles.entrySet()) {
            String key = entry.getKey();
            //  Faster check: className is always at start of key
            if (key.startsWith(className)) {
                PriorityObstacle obj = entry.getValue();
                if (obj != null) {
                    float currentDelta = Math.abs(obj.centerX - currentCenterX);
                    if (currentDelta < minimumDistance) {
                        minimumDistance = currentDelta;
                        bestKey = key;
                    }
                }
            }
        }
        return bestKey;
    }

    private PriorityObstacle getBestObstacle(List<PriorityObstacle> obstacles, int frameWidth) {
        obstacles.sort((a, b) -> {
            // 1. Approaching objects first
            if (a.isApproaching && !b.isApproaching) return -1;
            if (!a.isApproaching && b.isApproaching) return 1;

            // 2. Moving objects second
            if (a.isMoving && !b.isMoving) return -1;
            if (!a.isMoving && b.isMoving) return 1;

            // 3. Center corridor priority (25% from center)
            float aCenterBias = Math.abs(a.centerX - frameWidth / 2f);
            float bCenterBias = Math.abs(b.centerX - frameWidth / 2f);
            boolean aInCorridor = aCenterBias < frameWidth * 0.25f;
            boolean bInCorridor = bCenterBias < frameWidth * 0.25f;
            if (aInCorridor && !bInCorridor) return -1;
            if (!aInCorridor && bInCorridor) return 1;

            // 4. Priority map lookup
            int aPriority = PRIORITY_MAP.getOrDefault(a.className, 999);
            int bPriority = PRIORITY_MAP.getOrDefault(b.className, 999);
            if (aPriority != bPriority) return Integer.compare(aPriority, bPriority);

            // 5. Higher confidence last
            return Float.compare(b.confidence, a.confidence);
        });

        return obstacles.get(0);
    }

    private ObstacleInfo createObstacleInfo(PriorityObstacle best, int detectionCount,
                                            int frameWidth, long currentTime) {
        String direction = getDirection(best.centerX, frameWidth);
        String distance = getDistance(best.areaRatio);
        boolean isDanger = checkDanger(distance, direction, best);

        if (isDanger && best.isApproaching && direction.equals("CENTER")) {
            distance = "APPROACHING";
        }

        String stabilityKey = best.className + "_" + direction;
        Integer stabilityCount = objectStabilityCounter.getOrDefault(stabilityKey, 0);
        stabilityCount++;
        objectStabilityCounter.put(stabilityKey, stabilityCount);
        objectHistory.put(stabilityKey, currentTime);
        boolean isStable = stabilityCount >= STABLE_FRAME_THRESHOLD;

        return new ObstacleInfo(
                best.className.toUpperCase(Locale.US),
                direction,
                distance,
                detectionCount,
                isStable,
                isDanger,
                best.confidence
        );
    }

    private String getDirection(float centerX, int frameWidth) {
        if (centerX < frameWidth / 3f) return "LEFT";
        else if (centerX < 2 * frameWidth / 3f) return "CENTER";
        else return "RIGHT";
    }

    private String getDistance(float areaRatio) {
        if (areaRatio > 0.25f) return "NEAR";
        else if (areaRatio > 0.08f) return "MID";
        else return "FAR";
    }

    private boolean checkDanger(String distance, String direction, PriorityObstacle best) {
        if (distance.equals("NEAR") && direction.equals("CENTER")) return true;
        if (best.isApproaching && direction.equals("CENTER") && best.speed > APPROACHING_SPEED_THRESHOLD) return true;
        if (best.isMoving && direction.equals("CENTER") && distance.equals("MID")) return true;
        return false;
    }
}