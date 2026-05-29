package com.example.ocularai.ui;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import com.example.ocularai.R;

public class UIController {
    private final TextView resultTextView;
    private final TextView modeText;
    private final TextView lightingText;
    private final TextView objCount;
    private final TextView dirChip;
    private final TextView distChip;
    private final View dangerBorder;
    private final View micBtn;
    private final TextView statusPill;

    //  Thread-safe fields for cross-thread access
    private volatile String lastUIText = "";
    private volatile String lastUIDirection = "";
    private volatile String lastUIDistance = "";
    private volatile int lastUIObjectCount = -1;
    private volatile boolean lastUIDanger = false;

    public UIController(View rootView) {
        resultTextView = rootView.findViewById(R.id.resultTextView);
        modeText = rootView.findViewById(R.id.modeText);
        lightingText = rootView.findViewById(R.id.lightingText);
        objCount = rootView.findViewById(R.id.objCount);
        dirChip = rootView.findViewById(R.id.dirChip);
        distChip = rootView.findViewById(R.id.distChip);
        dangerBorder = rootView.findViewById(R.id.dangerBorder);
        micBtn = rootView.findViewById(R.id.micBtn);
        statusPill = rootView.findViewById(R.id.statusPill);
    }

    public void updateDetection(String label, String direction, String distance,
                                int objectCount, boolean isStable, boolean isDanger, float confidence) {
        // Edge check: If critical layout references are unlinked, prevent crash
        if (resultTextView == null || dirChip == null || distChip == null) return;

        String displayText = isStable ? label : label + "?";
        boolean uiChanged = !displayText.equals(lastUIText) ||
                !direction.equals(lastUIDirection) ||
                !distance.equals(lastUIDistance) ||
                objectCount != lastUIObjectCount ||
                isDanger != lastUIDanger;

        // Skip redundant updates for performance
        if (!uiChanged) return;

        // Update cached state
        lastUIText = displayText;
        lastUIDirection = direction;
        lastUIDistance = distance;
        lastUIObjectCount = objectCount;
        lastUIDanger = isDanger;

        // Apply UI changes
        resultTextView.setText(displayText);
        resultTextView.setTextColor(isDanger ? Color.RED : Color.WHITE);

        dirChip.setText(direction);
        distChip.setText(distance);

        // Robust null-safety for optional views
        if (objCount != null) {
            String suffix = (objectCount == 1) ? " object" : " objects";
            int percentage = Math.round(confidence * 100);
            String displayStats = objectCount + suffix + " (" + percentage + "%)";
            objCount.setText(displayStats);
        }

        if (dangerBorder != null) {
            dangerBorder.setVisibility(isDanger ? View.VISIBLE : View.GONE);
        }
    }

    public void updateEnvironment(String lightText, int lightColor, String statusText) {
        if (lightingText != null) {
            lightingText.setText(lightText);
            lightingText.setTextColor(lightColor);
        }
        if (statusPill != null) {
            statusPill.setText(statusText);
        }
    }

    public void updateWalkingState(boolean isWalking) {
        if (modeText == null) return;
        modeText.setText(isWalking ? "WALKING" : "STATIONARY");
        modeText.setTextColor(isWalking ? Color.parseColor("#FFA500") : Color.parseColor("#00FFCC"));
    }

    public void setMicButtonAlpha(float alpha) {
        if (micBtn != null) {
            micBtn.setAlpha(alpha);
        }
    }

    public void setStatusText(String text) {
        if (statusPill != null) {
            statusPill.setText(text);
        }
    }

    //  Thread-safe getters for cross-thread state access
    public String getLastUIText() {
        return lastUIText;
    }

    public String getLastUIDirection() {
        return lastUIDirection;
    }

    public String getLastUIDistance() {
        return lastUIDistance;
    }
}