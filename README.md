# Cinefy Enhancer

Xposed module for the Cinefy app (`com.smarttbr.cinefy`).

Cinefy locks itself to portrait, so when you put a video in fullscreen the screen stays upright and you end up watching a tiny letterboxed picture. On top of that the app never asks the system to keep the screen on, so the phone dims and locks halfway through an episode. This module fixes both.

## What it does

- When the player goes fullscreen, the screen rotates to landscape (follows the sensor, so either side works).
- While in fullscreen, the screen stays on.
- When you leave fullscreen, everything goes back to normal: portrait, and the screen timeout works as usual again.

Nothing else in the app is touched.

## Requirements

- An Xposed framework that supports the modern libxposed API, version 102. Tested with LSPatch. A recent LSPosed build should work too.
- Cinefy 1.0.0 (versionCode 8). Other versions will probably work as long as the player is still the react-native-webview one.

## Usage

### LSPatch (no root)

1. Install the Cinefy Enhancer APK.
2. Open LSPatch, go to Apps, tap the add button and pick Cinefy (installed app or APK).
3. Choose local patch mode, enable Cinefy Enhancer in the module list and start the patch.
4. Uninstall the original Cinefy and install the patched APK that LSPatch generated. The signature changes, so Android won't let you install it on top of the original.
5. Open Cinefy, start any video and hit the fullscreen button.

In local mode the module is loaded from the installed Cinefy Enhancer APK, so updating the module only needs a reinstall of the module and a restart of Cinefy, not a new patch.

### LSPosed (root)

1. Install the APK.
2. Enable the module in LSPosed. The scope is already set to Cinefy.
3. Force stop Cinefy and open it again.

## Checking that it works

Run:

```
adb logcat | grep CinefyEnhancer
```

When Cinefy starts you should see `Hooks installed in com.smarttbr.cinefy`. If you see `RNC WebChromeClient class not found` instead, Cinefy changed its player in an update and the hooks need to be adjusted.

## Building

```
./gradlew :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

## How it works

The Cinefy player is an HTML page running inside react-native-webview. When the video enters fullscreen, the WebView calls `onShowCustomView` on the WebChromeClient that react-native-webview sets up, and `onHideCustomView` when it leaves.

The module hooks those two methods:

- After `onShowCustomView`, it requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` and adds `FLAG_KEEP_SCREEN_ON` to the activity window.
- Before `onHideCustomView`, it clears the flag and lets react-native-webview restore the original orientation.

It also hooks `Activity.setRequestedOrientation`. While the video is in fullscreen, any request for a non-landscape orientation is swapped for landscape, so the app's JS side can't lock it back to portrait in the middle of playback.