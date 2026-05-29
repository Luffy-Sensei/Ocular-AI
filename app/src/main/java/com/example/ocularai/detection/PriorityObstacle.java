package com.example.ocularai.detection;

import org.tensorflow.lite.task.vision.detector.Detection;

public class PriorityObstacle {
    Detection detection;
    String className;
    float confidence;
    float centerX;
    float areaRatio;
    float speed;
    boolean isMoving;
    boolean isApproaching;
    long lastSeen;
    float lastAreaRatio;
    float smoothedAreaRatio;

    public PriorityObstacle(Detection d, String name, float conf, float x, float area, long time) {
        detection = d;
        className = name;
        confidence = conf;
        centerX = x;
        areaRatio = area;
        lastAreaRatio = area;
        smoothedAreaRatio = area;
        isMoving = false;
        isApproaching = false;
        speed = 0;
        lastSeen = time;
    }
}