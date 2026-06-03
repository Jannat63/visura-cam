# Visura Cam 📷
### Full-featured Android camera app — optimised for Realme 8 Pro (RMX3081)

---

## Why this app exists

The Realme 8 Pro's Samsung HM2 sensor was damaged by water exposure, causing
a permanent **yellow cast** in all photos. The stock camera app has no way to
fix this. Visura Cam fixes it at the hardware level via Camera2 API — on every
single frame, before JPEG encoding.

---

## Core Fix — How it works

```
Camera2 API
    │
    ├── AWB DISABLED (broken ISP auto white balance bypassed)
    │
    ├── Manual RggbChannelVector applied every frame:
    │       R gain:  0.82  (reduce — too hot from water damage)
    │       G gain:  0.91  (reduce slightly)
    │       B gain:  1.18  (boost — restore blue channel)
    │
    ├── OpenGL ES 3.1 LUT shader on live preview (Adreno 618 GPU)
    │       → What you see = what you get, corrected in real time
    │
    └── Same correction on still capture → clean JPEG output
```

Per-lens calibration (each lens may be affected differently):
- `LENS_MAIN_108MP`  → R:0.82 G:0.91 B:1.18  (primary fix)
- `LENS_ULTRAWIDE`   → R:0.88 G:0.94 B:1.10
- `LENS_MACRO`       → R:0.85 G:0.92 B:1.15
- `LENS_SELFIE`      → R:1.00 G:1.00 B:1.00  (Sony IMX471 unaffected)

To recalibrate: point camera at white paper → tap "Calibrate WB" in settings.

---

## Features

### Primary fix
| Feature | Description |
|---------|-------------|
| Live WB correction | OpenGL LUT on Adreno 618 GPU — zero lag |
| Capture WB correction | Camera2 RggbChannelVector on every still frame |
| Per-lens profiles | Separate correction for all 4 rear cameras |
| White reference calibration | Point at white paper for exact correction |
| Correction toggle | Tap WB badge to compare fixed vs unfixed live |
| Batch retroactive fix | Fix all past photos from gallery in background |

### Capture modes
| Mode | Notes |
|------|-------|
| Photo (12MP default) | 9-in-1 pixel binning — best IQ |
| 108MP full res | Samsung HM2 full resolution — large files |
| Night mode | 10-frame multi-frame NR + tone mapping |
| Portrait | Bokeh simulation via depth sensor |
| Macro | 2MP macro lens — distance guide + stacking |
| Pro / Manual | Full ISO/shutter/WB/focus control |
| RAW DNG | Bypass ISP entirely — best for post-processing |
| HDR | 3-frame bracket merge |
| Panorama | Horizontal sweep |
| Slow motion | 1080p 480fps |
| Time-lapse | Configurable interval |

### Pro controls (Camera2 API)
- **ISO**: 50 – 3200 (Samsung HM2 native range)
- **Shutter**: 1/4000s – 30s
- **White balance**: 2000K – 10000K Kelvin slider
- **Focus**: Manual pull with focus peaking overlay
- **EV**: -3 to +3 EV compensation
- **Metering**: Spot / Centre / Matrix

### Macro mode
- Distance guide: real-time sweet-spot indicator (turns green at 4cm)
- Stabilisation assist: waits for hand to be steady, then auto-captures
- Focus stacking: 6-frame merge for full depth-of-field sharpness
- HDR bracket: 3-frame merge for high-contrast subjects
- Per-lens color correction (separate from main camera)

### AI features
- Scene detector: 15 categories, TFLite on Adreno 618 GPU
- Per-scene optimised settings applied automatically
- Filter suggestions per scene type

### Viewfinder overlays
- Rule-of-thirds / golden ratio / square grid
- Live RGB histogram
- Focus peaking (colour edges on sharp areas)
- Level indicator (horizon)
- Zebra stripes (overexposure warning)

---

## Project structure

```
visura-cam/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/visura/cam/
│           ├── MainActivity.kt
│           ├── VisuraApp.kt
│           ├── camera/
│           │   ├── VisuraCameraController.kt   ← Camera2 main controller
│           │   ├── PreviewCorrectionRenderer.kt ← OpenGL LUT preview
│           │   ├── MacroController.kt          ← Macro distance/stacking
│           │   └── NightModeController.kt      ← Multi-frame night
│           ├── correction/
│           │   ├── ColorCorrectionEngine.kt    ← Core yellow cast fix
│           │   └── CalibrationDataStore.kt     ← Persist profiles
│           ├── ui/
│           │   └── viewfinder/
│           │       ├── ViewfinderScreen.kt     ← Main camera UI (Compose)
│           │       └── ViewfinderViewModel.kt  ← UI logic
│           ├── ai/
│           │   └── SceneDetector.kt            ← TFLite scene AI
│           ├── utils/
│           │   └── BatchColorFixer.kt          ← Fix past photos
│           └── di/
│               └── AppModule.kt               ← Hilt DI
├── build.gradle
└── settings.gradle
```

---

## Build & run

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Kotlin 1.9.20

### Steps
```bash
# 1. Open in Android Studio
File → Open → select visura-cam/

# 2. Sync Gradle
# Android Studio will prompt — click "Sync Now"

# 3. Connect Realme 8 Pro via USB
# Enable Developer Options → USB Debugging

# 4. Run
# Click ▶ Run or press Shift+F10
```

### First launch
1. Grant Camera, Microphone, Storage permissions
2. App opens with color correction **active by default**
3. Compare: tap the green **WB Fix** badge to toggle on/off
4. Calibrate: Settings → Color Correction → Calibrate (point at white paper)
5. Batch fix: Gallery → ⋮ → Fix all yellow cast photos

---

## Calibration guide (recommended first step)

For best results, do a white reference calibration per lens:

1. Open Visura Cam
2. Go to Settings → Color Correction → Calibrate
3. Point camera at a **white piece of paper** in natural daylight
4. Fill the frame with the paper
5. Tap **Calibrate now**
6. App calculates exact RGB gains for your specific damage
7. Repeat for ultrawide (0.6×) and macro lenses separately

Default gains (R:0.82, G:0.91, B:1.18) work for typical water damage.
Calibration gives you the exact values for your specific phone.

---

## Tech stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 |
| Camera | Camera2 API (direct — full manual control) |
| Preview correction | OpenGL ES 3.1 + GLSL fragment shader |
| AI / ML | TensorFlow Lite + GPU delegate (Adreno 618) |
| Image processing | RenderScript / GPGPU |
| DI | Hilt |
| Navigation | Jetpack Navigation Compose |
| Storage | DataStore Preferences + Room |
| Async | Kotlin Coroutines + Flow |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |

---

## Realme 8 Pro hardware reference

| Camera | Sensor | MP | Aperture | Focus |
|--------|--------|----|----------|-------|
| Main | Samsung ISOCELL HM2 | 108MP (binned 12MP) | f/1.88 | PDAF |
| Ultrawide | Unknown | 8MP | f/2.25 | Fixed |
| Macro | Unknown | 2MP | f/2.4 | Fixed 4cm |
| Depth | Unknown | 2MP B&W | f/2.4 | Fixed |
| Selfie | Sony IMX471 | 16MP | f/2.45 | FF |

SoC: Snapdragon 720G · GPU: Adreno 618 · RAM: 8GB
Display: 6.4" Super AMOLED, 60Hz · Android 11 → upgradeable to 12

---

*Built specifically for Realme 8 Pro (RMX3081) water damage color correction.*
*Core fix works on any Android 10+ device via Camera2 API.*
