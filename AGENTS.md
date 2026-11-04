# AGENTS.md

Instructions for coding agents that work on TVPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Architecture

TVPlay is a dedicated Apple CarPlay receiver for Android TV, TV boxes, projectors, and large-screen displays, organized into two modules:
- **`:core` (`app.tvplay.*`)**: CarPlay / AirPlay / iAP2 / MFi / Media / Network / Transport protocol engine and native JNI libraries.
- **`:app` (`app.tvplay`)**:
  - **10-Foot TV Workbench (`TvPlayWorkbenchActivity`)**: Dual-pane Apple TV-style layout with a live connection stage & 3-step connection guide on the left (`WorkbenchStagePane`), and a 4-tab D-pad-friendly settings panel (`CONNECTION`, `DISPLAY`, `AUDIO`, `ABOUT`) on the right (`WorkbenchSettingsPane`).
  - **Fullscreen CarPlay Projection (`TvPlayProjectionActivity`)**: Hardware-accelerated H.264/HEVC projection decomposed into `ProjectionSessionManager`, `VideoSurfaceController`, `RemoteInputDispatcher`, `ProjectionWaitingView`, and `QuickControlOverlayView` (`KEYCODE_MENU` or long-pressing `KEYCODE_BACK`).
  - **Settings Repository (`TvPlaySettingsRepository` / `AirPlayPersistence`)**: Structured domain settings (`connection`, `display`, `audio`, `identity`).
