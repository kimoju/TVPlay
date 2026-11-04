# TVPlay

[English](README.md) · [简体中文](README.zh-CN.md)

**Dedicated Apple CarPlay receiver for Android TV, TV boxes, projectors, and large-screen displays.**
Independent app ID: `app.tvplay`.

## Overview

TVPlay turns an Android TV, TV box, or smart projector (Android 7.0+ / API 24+) into a full-screen wireless or USB Apple CarPlay display with full TV remote (D-pad) navigation:

- **10-Foot Dual-Pane TV Workbench (`TvPlayWorkbenchActivity`)**: Apple TV-style dark cinema interface with a live connection stage & 3-step TV connection guide on the left, and a 4-tab D-pad-friendly settings workbench (`CONNECTION`, `DISPLAY`, `AUDIO`, `ABOUT`) on the right.
- **Full-Screen Hardware-Accelerated Projection (`TvPlayProjectionActivity`)**: Supports H.264 & HEVC (H.265), 30/60 FPS, Smooth Video frame pacing, Low-Latency hardware decoding, and an on-screen Quick Control Overlay (`KEYCODE_MENU` or long-pressing `KEYCODE_BACK`).
- **TV-Optimized Wireless & Wired Transport**:
  - **Same LAN / Existing Wi-Fi** (recommended for TVs on Ethernet or home Wi-Fi)
  - **Wi-Fi Direct 5GHz P2P**
  - **USB Wired CarPlay**
- **TV Remote & Voice Control**: D-pad directional focus navigation, `OK`/`ENTER` selection, media keys, and microphone/Siri voice trigger (`KEYCODE_SEARCH` / `KEYCODE_VOICE_ASSIST`).

## Architecture

TVPlay is structured as a 2-module Clean Architecture Android project:

- **`:core` (`app.tvplay.core`)**: CarPlay / AirPlay / iAP2 / MFi / Media / Network / Transport protocol engine and native JNI libraries (`lzo_jni`, `opus_jni`).
- **`:app` (`app.tvplay`)**:
  - `app.tvplay.settings`: Domain-oriented settings repository (`TvPlaySettingsRepository` + `AirPlayPersistence`).
  - `app.tvplay.projection`: Modularized projection controllers (`ProjectionSessionManager`, `VideoSurfaceController`, `RemoteInputDispatcher`).
  - `app.tvplay.ui`: 10-foot TV UI components (`WorkbenchStagePane`, `WorkbenchSettingsPane`, `ProjectionWaitingView`, `QuickControlOverlayView`).

## Building & Checks

Run the full unit test, lint, and debug build verification suite:

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

To build a standalone APK with local offline MFi authentication assets (placed in `.private/auth-assets/`):

```sh
./scripts/build-tvplay.sh
```

## Source & Credits

Based on [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) and [DiPlay](https://github.com/shihabal3amri/DiPlay) / [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0); licenses are included in `docs/licenses`. CarPlay is a trademark of Apple Inc.; no affiliation or endorsement is implied.
