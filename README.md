# WallTrace AR

Android app that places an image on a real wall with ARCore so you can trace or paint it.

## How it works

1. **Import** an image from the Gallery (system Photo Picker) or Files. JPG, PNG with transparency, WEBP, and HEIC/HEIF on Android 9+ are supported, with EXIF rotation applied automatically.
2. **Scan**: ARCore vertical plane detection starts with the camera. Tracked feature points show as small dots, and detected walls fade in as a dotted surface. On supported devices the Depth API improves hit-testing on plain walls.
3. **Place**: tap the wall. The image sits flat on the wall, upright. Drag to move, pinch to resize and twist to rotate.
4. **LOCK SKETCH**: creates an ARCore anchor at the sketch centre and turns off gestures. Light pose smoothing reduces jitter. You can **Unlock**, reposition and lock again, or **Reset**.
5. **Adjust**: opacity, brightness, contrast, saturation, grayscale, black & white, invert, outline (edge detection with adjustable strength and thickness), white-background removal, flips and a tracing grid. All of these run in real time on the GPU and don't move the anchor.
6. **Drawing mode** (eye icon) hides everything except the sketch and the opacity slider.

If no wall is detected after a few seconds, a labelled **Place approximately** fallback anchors the sketch about 1.2 m in front of the camera. The app clearly marks this as not attached to a detected wall.

Projects (image + settings) are saved automatically and work offline. ARCore anchors last only for one session, so after reopening a project you place the sketch on the wall again.

## Build

Pushing to `main` runs the **Build Android APK** GitHub Actions workflow. It runs the unit tests, builds the debug APK, runs lint, and uploads the **WallTrace-AR-APK** artifact containing `app-debug.apk`.

To build locally, open the project in Android Studio (JDK 17), or run `gradle assembleDebug` with Gradle 8.13.

## Requirements

- An ARCore-supported Android 7.0+ device with Google Play Services for AR (the app prompts you to install it).
- OpenGL ES 3.0.

## Limitations

No phone AR system detects every wall. Blank, glossy, reflective or poorly lit walls may not be detected; use more light, aim at edges or texture, or use the approximate placement. Some drift is possible during long sessions or after tracking is lost.

## Download

Latest APK (public, no login): https://github.com/futuremintai10-sketch/WallTrace-AR-Pro/releases/latest/download/WallTrace-AR.apk
