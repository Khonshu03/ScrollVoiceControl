# Scroll Voice Control

A minimal Android app: say "down" / "up" / "back" and it swipes the screen
inside whatever app is open (FB, IG, TikTok reels, etc.).

## How it works
- **ScrollAccessibilityService** – an Android Accessibility Service that can
  dispatch swipe gestures and the system "back" action to whatever app is in
  the foreground, without needing that app's permission.
- **VoiceListenerService** – a foreground service (shows a persistent
  notification while active) that keeps `SpeechRecognizer` listening in a
  loop and matches recognized words against `down`, `up`, `back` (plus a
  couple of synonyms).
- **MainActivity** – one toggle switch. Turning it on requests the
  microphone permission, sends you to Settings to enable the accessibility
  service (Android requires this be done manually, apps can't self-enable
  it), then starts the foreground listener service.

## Build it
1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder as a project ("Open" → select the `ScrollVoiceControl` folder).
3. Let Gradle sync (first sync downloads dependencies, needs internet).
4. Connect your phone via USB with Developer Options + USB debugging on,
   or use `Build > Generate Signed Bundle/APK` to make an installable APK.
5. Run it (the green Run button), or install the APK directly on your phone.

## First run on your phone
1. Open the app, flip the switch on.
2. Grant microphone permission when asked.
3. It'll jump to **Settings > Accessibility**. Find "Scroll Voice Control" in
   the list, tap it, and turn it on (Android will show a warning dialog —
   this is normal for any accessibility service, tap Allow).
4. Go back to the app — status should say "Voice control is ON".
5. Open FB/IG/TikTok reels and just say "down", "up", or "back". You'll see
   a small persistent notification while it's listening.

## Tuning it
- **Add more trigger words**: edit the `COMMANDS` map in
  `VoiceListenerService.kt` — e.g. add `"skip" to "down"` if it keeps
  mishearing you.
- **Swipe distance/speed**: adjust the fractions and duration (`250`ms) in
  `ScrollAccessibilityService.swipe()`.
- **Battery**: continuous `SpeechRecognizer` listening does drain battery
  faster than normal use — that's inherent to always-on mic listening on
  Android without a dedicated low-power wake-word chip. Turn the toggle off
  when you're done watching.

## Known limitations
- Works on Android only (no iOS equivalent — Apple doesn't allow system-wide
  gesture injection or this kind of background mic access).
- Uses Google's on-device/cloud speech recognizer via `SpeechRecognizer`, so
  recognition quality depends on your device and network.
- If your phone aggressively kills background services (common on Xiaomi/
  Oppo/Vivo/Huawei), you may need to disable battery optimization for this
  app in Settings so the foreground service doesn't get killed.
