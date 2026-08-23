# Scroll Voice Control

A minimal Android app that scrolls whatever app is open (FB, IG, TikTok
reels, etc.) hands-free, using one of four detection modes: voice, clap,
phone-tilt motion, or camera hand-swipe.

## How it works
- **ScrollAccessibilityService** – an Android Accessibility Service that can
  dispatch swipe gestures and the system "back" action to whatever app is in
  the foreground, without needing that app's permission.
- **VoiceListenerService** – a foreground service (shows a persistent
  notification while active) that runs one of four detection modes:
  - **Voice** (`SpeechRecognizer`) – listens in a loop and matches recognized
    words against `down`, `up`, `back` (plus a couple of synonyms).
  - **Clap** (`ClapDetector`) – listens to raw mic input for the sharp
    amplitude spike of a clap; 1 clap = down, 2 = up, 3 = back.
  - **Motion** (`MotionDetector`) – uses the phone's accelerometer to detect
    a forward/back *tilt of the phone itself*. No camera, no mic.
  - **Camera** (`CameraGestureDetector`) – watches the *front camera* for a
    hand/finger swiping through the air in front of the phone and reports
    up/down. Uses lightweight frame-to-frame brightness-motion tracking, not
    full hand-skeleton/ML tracking, so it's fast and needs no bundled model.
- **MainActivity** – a mode picker plus one toggle switch. Turning it on
  requests whatever permission the selected mode needs (mic for Voice/Clap,
  camera for Camera, neither for Motion), sends you to Settings to enable
  the accessibility service (Android requires this be done manually, apps
  can't self-enable it), then starts the foreground listener service.

## Build it
1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder as a project ("Open" → select the `ScrollVoiceControl` folder).
3. Let Gradle sync (first sync downloads dependencies, needs internet).
4. Connect your phone via USB with Developer Options + USB debugging on,
   or use `Build > Generate Signed Bundle/APK` to make an installable APK.
5. Run it (the green Run button), or install the APK directly on your phone.

## First run on your phone
1. Open the app, pick a mode, flip the switch on.
2. Grant the mic or camera permission when asked (whichever the mode needs).
3. It'll jump to **Settings > Accessibility**. Find "Scroll Voice Control" in
   the list, tap it, and turn it on (Android will show a warning dialog —
   this is normal for any accessibility service, tap Allow).
4. Go back to the app — status should say "Voice control is ON (<mode> mode)".
5. Open FB/IG/TikTok reels and use your chosen mode. You'll see a small
   persistent notification and an on-screen pulse dot while it's active.

## Tuning it
- **Voice trigger words**: edit the `COMMANDS` map in `VoiceListenerService.kt`
  — e.g. add `"skip" to "down"` if it keeps mishearing you.
- **Swipe distance/speed**: adjust the fractions and duration (`250`ms) in
  `ScrollAccessibilityService.swipe()`.
- **Motion (tilt) sensitivity**: `TILT_THRESHOLD` / `NEUTRAL_BAND` in
  `MotionDetector.kt`.
- **Camera (hand swipe) sensitivity**: `MOTION_START_THRESHOLD`,
  `MOTION_CONTINUE_THRESHOLD`, and `MIN_SWIPE_BAND_DELTA` in
  `CameraGestureDetector.kt` are the main ones to tune per lighting/distance.
  If "up" and "down" feel swapped once you try it, flip `INVERT_DIRECTION`
  in that same file.
- **Battery**: continuous mic listening (Voice/Clap) or camera analysis
  (Camera) drains battery faster than normal use — that's inherent to
  always-on sensing without a dedicated low-power chip. Turn the toggle off
  when you're done watching. Motion mode (accelerometer only) is the
  cheapest of the four.

## Known limitations
- Works on Android only (no iOS equivalent — Apple doesn't allow system-wide
  gesture injection or this kind of background mic/camera access).
- Voice mode's recognition quality depends on your device and network
  (uses Google's on-device/cloud `SpeechRecognizer`).
- Camera mode needs a reasonably lit scene and a fairly deliberate swipe at
  arm's length — it's a lightweight brightness-motion heuristic, not true
  hand tracking, so very subtle finger-only movements from far away may not
  register. Bring your whole hand closer to the lens if it's missing swipes.
- If your phone aggressively kills background services (common on Xiaomi/
  Oppo/Vivo/Huawei), you may need to disable battery optimization for this
  app in Settings so the foreground service doesn't get killed.
