package com.example.ocularai.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpeechManager {
    private static final String TAG = "SpeechManager";
    private static final long SPEECH_COOLDOWN_MS = 3000;

    private final Context context;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private final AtomicBoolean ttsReady = new AtomicBoolean(false);
    private final AtomicBoolean userMuted = new AtomicBoolean(false); //  Separate mute flag
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> speechCooldownMap = new HashMap<>();
    private long lastAnnounceTime = 0;
    private volatile boolean isListening = false;
    private boolean speechRecognizerInitialized = false;

    //  Store last obstacle info for voice commands
    private String lastObstacleLabel = "";
    private String lastObstacleDirection = "";
    private String lastObstacleDistance = "";

    public interface SpeechCallback {
        void onSpeechStatusChanged(boolean isListening);
    }

    private final SpeechCallback callback;

    public SpeechManager(Context context, SpeechCallback callback) {
        //  Store ApplicationContext to prevent memory leaks
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                if (tts != null) {
                    tts.setLanguage(Locale.US);
                    tts.setSpeechRate(0.92f);
                    tts.setPitch(1.05f);
                    ttsReady.set(true);
                    speak("Ocular AI ready", TextToSpeech.QUEUE_ADD);
                }
            } else {
                Log.e(TAG, "TTS initialization failed");
            }
        });
    }

    /**
     * Speaks text with queue management.
     * Critical messages (QUEUE_FLUSH) override user mute for safety.
     */
    public void speak(String text, int queueMode) {
        if (tts != null && ttsReady.get()) {
            //  Danger/emergency messages bypass mute for safety
            boolean isCritical = (queueMode == TextToSpeech.QUEUE_FLUSH);
            if (!userMuted.get() || isCritical) {
                tts.speak(text, queueMode, null, "utterance_" + System.currentTimeMillis());
            }
        }
    }

    public boolean canAnnounce(String speechKey, long currentTime) {
        cleanupCooldowns(currentTime);

        Long lastSpoken = speechCooldownMap.get(speechKey);
        if (lastSpoken != null) {
            if (currentTime - lastSpoken < SPEECH_COOLDOWN_MS) {
                return false;
            }
        }
        return true;
    }

    public void recordAnnouncement(String speechKey, long currentTime) {
        speechCooldownMap.put(speechKey, currentTime);
        lastAnnounceTime = currentTime;
    }

    public long getLastAnnounceTime() {
        return lastAnnounceTime;
    }

    private void cleanupCooldowns(long currentTime) {
        Iterator<Map.Entry<String, Long>> iterator = speechCooldownMap.entrySet().iterator();
        while (iterator.hasNext()) {
            if (currentTime - iterator.next().getValue() > SPEECH_COOLDOWN_MS) {
                iterator.remove();
            }
        }
    }

    public void updateLastUI(String label, String direction, String distance) {
        this.lastObstacleLabel = label;
        this.lastObstacleDirection = direction;
        this.lastObstacleDistance = distance;
    }

    public void toggleListening() {
        if (isListening) {
            stopListening();
        } else {
            startListening();
        }
    }

    public void startListening() {
        if (!speechRecognizerInitialized) {
            initSpeechRecognizer();
        }
        if (isListening || speechRecognizer == null) return;

        setListening(true);

        // Don't speak "Listening" if muted (would be confusing)
        if (!userMuted.get()) {
            speak("Listening for command", TextToSpeech.QUEUE_ADD);
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        // Allow TTS prompt to finish before opening mic
        mainHandler.postDelayed(() -> {
            if (speechRecognizer != null && isListening) {
                speechRecognizer.startListening(intent);
            }
        }, 750);
    }

    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        setListening(false);
    }

    private void setListening(boolean active) {
        isListening = active;
        if (callback != null) {
            //  Ensure callback runs on main thread
            mainHandler.post(() -> callback.onSpeechStatusChanged(active));
        }
    }

    public boolean isCurrentlyListening() {
        return isListening;
    }

    private void initSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition engine unavailable on this device model.");
                return;
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onEvent(int t, Bundle b) {}

                @Override
                public void onResults(Bundle results) {
                    //  CRITICAL FIX: RecognitionListener callbacks come from binder thread!
                    // Must post to main thread before calling TTS or UI methods
                    List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        final String command = matches.get(0).toLowerCase(Locale.US).trim();
                        mainHandler.post(() -> {
                            setListening(false);
                            handleVoiceCommand(command);
                        });
                    } else {
                        mainHandler.post(() -> setListening(false));
                    }
                }

                @Override
                public void onError(int error) {
                    //  Post to main thread for TTS calls
                    mainHandler.post(() -> {
                        setListening(false);
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            speak("Command not recognized. Say help for options.", TextToSpeech.QUEUE_ADD);
                        }
                    });
                }

                @Override
                public void onEndOfSpeech() {
                    mainHandler.post(() -> setListening(false));
                }
            });

            speechRecognizerInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to instantiate Google Speech Recognition Engine bindings.", e);
        }
    }

    private void handleVoiceCommand(String command) {
        if (command.contains("help")) {
            speak("Available commands: what's around me, repeat last, emergency, silence, resume.",
                    TextToSpeech.QUEUE_ADD);

        } else if (command.contains("what") && (command.contains("around") || command.contains("near"))) {
            String response = lastObstacleLabel.isEmpty() ?
                    "No obstacles detected in current view." :
                    "Currently: " + lastObstacleLabel + " " + lastObstacleDirection + ", " + lastObstacleDistance;
            speak(response, TextToSpeech.QUEUE_ADD);

        } else if (command.contains("repeat")) {
            String response = lastObstacleLabel.isEmpty() ?
                    "Nothing to repeat." :
                    lastObstacleLabel + " " + lastObstacleDirection + ", " + lastObstacleDistance;
            speak(response, TextToSpeech.QUEUE_ADD);

        } else if (command.contains("emergency")) {
            //  Emergency always speaks regardless of mute
            speak("Emergency alert activated.", TextToSpeech.QUEUE_FLUSH);

        } else if (command.contains("silence") || command.contains("mute")) {
            userMuted.set(true);
            if (tts != null) tts.stop();
            speak("Voice output muted.", TextToSpeech.QUEUE_FLUSH); // Confirm mute

        } else if (command.contains("resume") || command.contains("unmute")) {
            userMuted.set(false);
            speak("Voice output resumed.", TextToSpeech.QUEUE_ADD);

        } else {
            speak("Command not recognized. Say help for available commands.", TextToSpeech.QUEUE_ADD);
        }
    }

    public boolean isMuted() {
        return userMuted.get();
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null); //  Clean up pending callbacks

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        ttsReady.set(false);
        speechRecognizerInitialized = false;
    }
}