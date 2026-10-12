# 💉 Meal Detection — Galaxy Watch 7

> Automatic meal detection through gesture recognition on a smartwatch, to **remind people with diabetes to inject their insulin**.

![Platform](https://img.shields.io/badge/platform-Wear%20OS-4285F4)
![Device](https://img.shields.io/badge/device-Galaxy%20Watch%207-1428A0)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF)
![TensorFlow Lite](https://img.shields.io/badge/TensorFlow%20Lite-2.6.0-FF6F00)
![Python](https://img.shields.io/badge/Python-training-3776AB)

---

## 📖 Background

When you live with insulin-treated diabetes, **forgetting your mealtime bolus** happens a lot: you get distracted, you eat out, you're busy talking… Missing it can cause significant post-meal hyperglycemia.

**Meal Detection** is a **standalone Wear OS app** for the **Samsung Galaxy Watch 7**. During mealtime windows it continuously analyzes wrist movements. When it recognizes a series of **"hand → mouth"** gestures typical of a meal, it sends a **local notification with vibration** reminding you to inject your insulin.

- 🧠 **100% on-device inference**: a TensorFlow Lite convolutional neural network (CNN) runs directly on the watch.
- 📵 **No phone dependency**: the app runs standalone, including with an **iPhone** (no Android companion app needed).
- 🔒 **No data sent over the Internet**: sensor data stays on the watch.

---

## ⌚ Hardware and how the watch is worn

| Item | Details |
|---|---|
| Watch | **Samsung Galaxy Watch 7** (Wear OS 5 / One UI Watch) |
| Wrist | **RIGHT hand**, the hand that brings food to the mouth |
| Sensors | 3-axis accelerometer + 3-axis gyroscope |
| Sampling rate | ~50 Hz (`SENSOR_DELAY_GAME`) |
| Phone | Not required (tested with an iPhone) |

> ⚠️ **Important**: the model was trained with the watch worn on the **right wrist**, the one that makes the gesture toward the mouth (fork, spoon, sandwich, glass…). If the watch is worn on the left wrist, the axes are mirrored and the model **will not detect meals correctly**. You would then need to collect new data and retrain the model.

---

## ⚙️ How it works

```
 IMU sensors (6 axes, 50 Hz)
            │
            ▼
 Sliding buffer of 400 samples (8 s)
            │
            ▼
 Per-axis z-score normalization
            │
            ▼
 TFLite CNN  ──►  [P(other), P(meal)]
            │
            ▼
 P(meal) ≥ confidence threshold (0.97)?  ──► +1 timestamped detection
            │
            ▼
 ≥ N detections (9) within the last 90 seconds?
            │
            ▼
 🔔 Notification "Remember your insulin!"
      ├── ✅ Injection done  → detection paused for 45 min, then resumes automatically
      └── ❌ Not eating      → counter reset, detection continues
```

### Episode aggregation
A single positive detection is not enough (drinking a glass of water, scratching your face…). A **meal** is confirmed when **N detections** with confidence ≥ threshold occur within a **90-second sliding window**. Detections that have aged out of the window are purged at every inference.

### Time windows
To save battery and avoid false positives, automatic detection **only runs during mealtime windows**. Exact alarms (`setExactAndAllowWhileIdle`) start and stop the service, even in Doze mode.

| Mode | Days | Breakfast | Lunch | Dinner |
|---|---|---|---|---|
| Normal | Mon → Fri | 06:20 – 08:30 | 12:30 – 15:00 | 19:30 – 21:30 |
| Normal | Sat → Sun | 09:00 – 11:00 | 13:00 – 15:30 | 19:30 – 22:00 |
| 🌴 Vacation | Every day | 09:00 – 11:00 | 13:00 – 15:30 | 19:30 – 22:00 |

Outside these windows, a **manual mode** (switch) lets you start detection on demand (e.g. for a late meal).

---

## 📱 User interface

- **Status**: `✅ Auto detection active` with the current window, or `⏳ Next detection: tomorrow at 06:20`.
- **Manual detection switch**: greyed out during automatic windows.
- **⚙ SETTINGS**: an expandable panel with the settings below, saved in `SharedPreferences`.

| Setting | Default | Range | Effect |
|---|---|---|---|
| Vacation mode | OFF | ON / OFF | Switches to the vacation schedule |
| CNN confidence | **0.97** | 0.75 – 1.00 | Minimum probability for a detection to count |
| Detections / 90 s | **9** | 3 – 15 | Number of detections needed to trigger the notification |

> 🔧 There is a hidden **data collection** mode: **tap the title 5 times** to show it.

> ℹ️ The app UI itself is in French.

---

## 🗂️ Project structure

```
MealDetection/
├── wear/                                   # Wear OS module (Android Studio)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── meal_detection_cnn.tflite   # Embedded model (~365 KB)
│       └── java/com/example/mealdetection/
│           ├── WearMainActivity.kt         # UI + state machine (AUTO / WAITING / MANUAL)
│           ├── MealDetectionService.kt     # Sensors → CNN → aggregation → notification
│           ├── MealScheduleReceiver.kt     # Time windows + exact alarms + boot
│           └── WatchDataCollectionService.kt # CSV recording for training
├── scripts/                                # Python pipeline
│   ├── train_cnn_lstm.py                   # CNN training + TFLite export
│   ├── generate_kotlin_cnn.py              # Generates the Kotlin normalization constants
│   └── clean_csv.py                        # CSV cleanup
├── dataset/                                # Labeled CSVs (not versioned, see .gitignore)
├── docs/
│   ├── MealDetection_Guide_Maintenance.docx
│   └── MealDetection_Annexe_Logcat.docx
└── README.md
```

---

## 🧪 Training pipeline

### 1. Data collection
1. Open the app, then **tap the title 5 times** to show the collection section.
2. Pick a label (`repas_fourchette`, `repas_sandwich`, `boire_verre`, `autre_clavier`, `autre_repos`…) and start recording. Labels use French prefixes: `repas` = meal, `boire` = drink, `autre` = other.
3. Files are written to `watch_recordings/` as `label.timestamp.csv`:
   ```
   timestamp,accX,accY,accZ,gyrX,gyrY,gyrZ
   ```
4. Copy the files to your computer:
   ```bash
   adb -s <ip:port> pull /sdcard/Android/data/com.example.mealdetection/files/watch_recordings ./dataset
   ```

> 💡 **Long sessions** are essential to reduce false positives: a full 20–30 min meal, computer work, resting, housework…

### 2. Training
```bash
cd scripts
pip install numpy pandas tensorflow scikit-learn
python train_cnn_lstm.py
```

**CNN architecture** (input `[1, 400, 6]`):

```
Conv1D(32) → BatchNorm → MaxPool
Conv1D(64) → BatchNorm → MaxPool
Conv1D(128) → BatchNorm → GlobalAveragePooling1D
Dense(32) → Dropout(0.4) → Dense(2, softmax)
```

- Windows of 400 samples with a stride of 25 (`STRIDE_TRAIN`).
- Data augmentation + class weighting.
- No LSTM: it needs *Select TF ops*, which the watch's TFLite interpreter does not support.

Generated outputs:
- `meal_detection_cnn.tflite`: model embedded in the app
- `meal_detection_cnn_model.keras`: Keras model
- `cnn_norm_params.json`: per-axis means and standard deviations

### 3. Results (latest training run)

| | Predicted other | Predicted meal |
|---|---|---|
| **Actual other** | 8,817 (TN) | 36 (FP) |
| **Actual meal** | 8 (FN) | 2,711 (TP) |

**Accuracy ≈ 99.6%** · false positive rate ≈ 0.4% · false negative rate ≈ 0.3%

### 4. Deploying to the watch
```bash
python generate_kotlin_cnn.py      # → kotlin_cnn_constants.txt
```
1. Copy `meal_detection_cnn.tflite` into `wear/src/main/assets/`.
2. In `MealDetectionService.kt`, replace `NORM_MEAN_AXIS` and `NORM_STD_AXIS` with the values from `kotlin_cnn_constants.txt`.
3. Build the signed APK (*Build → Generate Signed App Bundle / APK*), then:
   ```bash
   adb -s <ip:port> install -r wear-release.apk
   ```

---

## 🐞 Debugging

```bash
# Detection and scheduling logs
adb -s <ip:port> logcat -s MealDetection:D MealSchedule:D

# Check scheduled alarms
adb -s <ip:port> shell dumpsys alarm | findstr mealdetection     # Windows
adb -s <ip:port> shell dumpsys alarm | grep mealdetection        # Linux / macOS
```

Sample inference log (logs are in French; `compteur` = counter):
```
Inférence → repas=0.98 autre=0.02 | compteur=6/9
```

**Samsung tip**: set the app's battery usage to **"Unrestricted"** (*Settings → Apps → Meal Detection → Battery*) so One UI doesn't block the service from starting in the background.

---

## 📦 Dependencies

**Android (wear)**
```kotlin
implementation("org.tensorflow:tensorflow-lite:2.6.0")
implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.6.0")
```

**Main permissions**: `BODY_SENSORS`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`.

**Python**: `numpy`, `pandas`, `tensorflow`, `scikit-learn`

---

## ⚠️ Medical disclaimer

This is an **experimental personal tool**. It is **not a medical device** and has not been clinically validated. It may **miss a meal** or trigger **false alerts**. It is in no way a substitute for the patient's own attention, glucose monitoring, or the advice of a healthcare professional.

---

## 🙏 Inspiration

- Research from the United States on automatic meal detection using wrist-worn inertial sensors for people with diabetes.
- The open-source diabetes ecosystem (AndroidAPS, xDrip+…).

---

## 📄 License

Personal project, shared for educational purposes. License to be decided (e.g. MIT).

## 👤 Author

**Xavier** — Madrid 🇪🇸
