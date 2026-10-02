# Dance Mashup (Android)

Drop in funny dance videos and an MP3 — the app chops the clips up on the beat, shuffles them,
throws in stutters / mirror flips / zooms / upside-down and black-and-white cuts, mutes the
original audio and lays your song underneath. Output: one 720×1280 MP4.

## Use it
1. **Add videos** (gallery picker) — or share videos straight to *Dance Mashup* from your gallery.
2. **Pick MP3** — or share an audio file to the app.
3. Choose length, craziness and the song's BPM (cuts land on the beat).
4. **Make it funny!** → preview, **Remix** for a new random version, **Save to gallery** or **Share**.

## Get the APK
Every push touching `DanceMashup/` runs the *Dance Mashup Android* GitHub Action, which runs the
unit tests and uploads `DanceMashup-apk` as a build artifact. Download it, unzip, and install the
APK on your phone (allow "install unknown apps").

Or build locally with Android Studio / `./gradlew assembleRelease` (JDK 17, Android SDK 34).

## How it works
- `MashupPlanner` — pure Kotlin: picks beat-length cuts from each clip, round-robin shuffled so every
  clip appears, adds stutters/effects based on the craziness slider.
- `MashupRenderer` — Media3 Transformer: one video sequence (clipped, audio removed, effects, scaled
  to portrait) plus a looping audio sequence with the song, encoded to H.264/AAC on-device.

Requires Android 8.0+.
