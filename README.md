# Ocular AI
> Real-Time Spatial Awareness Assistant for the Visually Impaired

Ocular AI is an intelligent assistive Android application designed to enhance the environmental awareness of visually impaired individuals. The system transforms a standard commodity smartphone into a real-time sensing device, using on-device machine learning and computer vision — with no internet connection required.

## About the App
Unlike generic object detection tools, Ocular AI provides contextual spatial awareness. It identifies nearby obstacles, estimates their relative direction (left, center, right), calculates relative distance (near, medium, far), and delivers immediate feedback through synthesized voice and haptic vibration patterns. 

- **Problem Solved:** Traditional tools like white canes fail to offer object identification, while cloud-based AI apps introduce unsafe latency (2–5s) and pose massive data privacy risks.
- **Core Strategy:** Ocular AI operates locally under a specialized **Sense — Think — Act** pipeline to maintain ultra-low latency (<100 ms) and absolute data security.
- **Target Audience:** Visually impaired individuals, low-vision users, and independent mobility rehabilitation therapists.

## App Screenshots


| Startup State | Object Detection Mode | Human Detection Mode |
|:---:|:---:|:---:|
| ![Startup](screenshots/Initilizing.png) | ![Object Scan](screenshots/object%20detection.png) | ![Human Scan](screenshots/human%20detection.png) |

| Miscellaneous Scans | Lens Blocked Warning |
|:---:|:---:|
| ![Things Scan](screenshots/things%20detections.png) | ![Lens Blocked](screenshots/lens%20blocked.png) |


## Features
- **Real-Time Object Detection:** Scans up to 80 COCO object classes locally using embedded ML.
- **Spatial Engine Processing:** Calculates centroid direction (LEFT / CENTER / RIGHT) and boundaries (NEAR / MID / FAR).
- **Priority Alerts Engine:** Sequences warnings, prioritizing critical hazards (persons, moving vehicles).
- **Multi-Modal Feedback:** Combined Android Text-to-Speech (TTS) narration with categorical haptic patterns.
- **Voice Recognition Commands:** SpeechRecognizer triggers commands offline (`help`, `what's around me`, `silence`).
- **Adaptive Safety Modules:** Integrated Walking Mode, Low-Light warnings, and Lens-Blocked detection filters.
- **Fully Offline Execution:** Zero external network calls; total optimization for budget and mid-range devices.

## Architecture (Data Flow Pipeline)
`CameraX (Perception)` ➔ `Frame Processing` ➔ `TensorFlow Lite Inference` ➔ `Spatial Analysis` ➔ `Decision Buffer Engine` ➔ `Output Layer (TTS & Haptics)`

## Technologies Used
- **Language:** Java (Android SDK 34)
- **IDE & Tooling:** Android Studio (Latest Stable Release), Gradle (Kotlin DSL)
- **Camera API:** CameraX Jetpack Library
- **Machine Learning Engine:** TensorFlow Lite + Google ML Kit (On-Device Inference)
- **Pre-trained Model:** EfficientDet-Lite0 / MobileNet SSD v2
- **Core Framework APIs:** Android Text-to-Speech, SpeechRecognizer, SensorManager (Accelerometer), Vibrator API

## APK Download
[Download Ocular AI APK](apk/OcularAI.apk)

## How to Install the APK
1. Download the `OcularAI.apk` file directly onto your Android device from the directory link above.
2. Open the downloaded file from your device file manager.
3. If prompted by your system, enable **"Install from Unknown Sources"** within device security settings.
4. Finalize the installer configuration screen and launch the application.

## How to Run the Project
1. Clone or download this repository locally on your machine.
2. Launch Android Studio and choose **Open an Existing Project**, browsing to the root folder.
3. Allow the automated build setup sequence to complete (Sync Gradle files).
4. Connect an Android smartphone (running Android 7.0 Nougat or above) via USB debugging or boot a local emulator.
5. Click the green **Run** button to assemble and install the workspace.

## Privacy Policy
Ocular AI is fully dedicated to user privacy and secure processing parameters.
[View Signed Privacy Policy Document](docs/privacy_policy.pdf)

## Future Enhancements
- **Depth Sensor Integration:** Integrating Time of Flight (ToF) sensors for metric distance calculations.
- **Multilingual Support:** Porting TTS configurations to support regional languages like Urdu and Arabic.
- **Wearable Extension:** Broadening data output pipelines to smartwatches and wireless bone-conduction audio sets.
- **SOS Location Broadcast:** Voice command integration to automatically push emergency GPS alerts to selected contacts over standard SMS channels.

## Developed By
- **Student Name:** Fazal Hammad Khan
- **Roll Number:** UL-BSITE-23-14
- **Program / Class:** BS Information Technology (Evening) 6th
- **Institution:** University of Layyah
- **Session:** 2023-2027
- **GitHub:** https://github.com/Luffy-Sensei
- **LinkedIn:** https://www.linkedin.com/in/fazal-hammad-khan-814a94407/
