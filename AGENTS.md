# AGENTS.md

Instructions for coding agents that work on TVPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Architecture

TVPlay is a pure Kotlin/Java Apple CarPlay receiver purpose-built for Android TV, TV boxes, projectors, and large-screen displays, organized into two modules with zero JNI/NDK code:

- **`:core` (`app.tvplay.engine.*`)**:
  - **`auth`**: `AccessoryIdentity`, `HomeKitPairingCipher`, `MfiAuthProvider`, `MfiSapHandshake`, `UsbLockdownClient`
  - **`protocol.iap2`**: `Iap2FrameCodec`, `Iap2LinkLayer`, `Iap2ControlHandler`
  - **`protocol.rtsp`**: `RtspBinaryCodec`, `RtspHidDispatcher`, `RtspStreamChannels`, `RtspControlSession`
  - **`network`**: `BonjourServiceAdvertiser`, `LanNetworkLink`, `WifiDirectGroupLink`, `WirelessLinkManager`, `BluetoothRfcommAcceptor`, `UsbGadgetTransport`, `UsbTunnelVpnService`
  - **`av`**: `ViewportLayoutMath`, `HardwareVideoDecoder`, `PcmAudioRenderer`, `VoiceMicRecorder`, `TvAvRenderPipeline`
  - **`session`**: `TvSessionConfig`, `TvSessionState`, `TvSessionEngine`
- **`:app` (`app.tvplay.*`)**:
  - **`config`**: `MfiAssetInstaller`, `TvDeviceCapabilities`, `TvPreferencesStore`, `TvSettingsRepository`
  - **`remote`**: `TvRemoteKeyMap`, `TvRemoteInputController`, `TvVideoColorFilter`
  - **`runtime`**: `TvDiagnosticsLogger`, `TvLanOtaManager`, `TvLaunchReceivers`, `TvMediaKeyRouter`, `TvProjectionSessionHost`, `TvSessionForegroundService`
  - **`ui.theme`**: `TvColorScheme`, `TvUiComponents`
  - **`ui.workbench`**: `TvWorkbenchActivity`, `WorkbenchHeroStage`, `WorkbenchSettingsTabs`
  - **`ui.stage`**: `TvStageActivity`, `TvVideoSurfaceHost`, `StageWaitingOverlay`, `StageQuickControlOverlay`, `StagePictureTuningOverlay`, `StageFpsHudOverlay`
