# OpenClaw Android Assistant - Installation Guide

## Prerequisites

- **Java 17** (JDK) - required by Android Gradle plugin
- **Android SDK** with:
  - `compileSdk 36` (Android 16)
  - `minSdk 31` (Android 12+)
  - Build Tools 36.x
  - NDK (side by side) if building native code
- **Android Studio** (optional, for IDE builds) or command-line Gradle
- **ADB** (Android Debug Bridge) installed and on PATH
- **Samsung Galaxy** device running Android 12 or later (API 31+)
- **USB debugging** enabled on the device

## Building the APK

### 1. Clone the repository

```bash
git clone https://github.com/jackhamer09-ai/openclaw-android-assistant.git
cd openclaw-android-assistant
```

### 2. Set up signing (release builds)

Create or edit `~/.gradle/gradle.properties`:

```properties
OPENCLAW_ANDROID_STORE_FILE=/path/to/your/keystore.jks
OPENCLAW_ANDROID_STORE_PASSWORD=your_store_password
OPENCLAW_ANDROID_KEY_ALIAS=your_key_alias
OPENCLAW_ANDROID_KEY_PASSWORD=your_key_password
```

For debug builds, skip this step (the debug keystore is used automatically).

### 3. Build the APK

```bash
cd apps/android

# Debug build
./gradlew assembleDebug

# Release build (requires signing config above)
./gradlew assembleRelease
```

The APK will be at:
- Debug: `app/build/outputs/apk/debug/openclaw-<version>-debug.apk`
- Release: `app/build/outputs/apk/release/openclaw-<version>-release.apk`

## Installing via ADB

### 1. Connect your Samsung Galaxy via USB

```bash
# Verify device is connected
adb devices
```

You should see your device listed. If prompted on the phone, accept the USB debugging authorization.

### 2. Install the APK

```bash
adb install -r app/build/outputs/apk/debug/openclaw-<version>-debug.apk
```

Use `-r` to replace an existing installation.

## Setting OpenClaw as Default Assistant (Samsung Galaxy)

### Method 1: Samsung Settings

1. Open **Settings**
2. Go to **Apps**
3. Tap **Default apps** (or **Choose default apps**)
4. Tap **Digital assistant app** (or **Device assistance app**)
5. Select **OpenClaw Node** from the list
6. Confirm your selection

### Method 2: Via ADB (alternative)

```bash
adb shell settings put secure assistant ai.openclaw.app/ai.openclaw.app.assistant.OpenClawVoiceInteractionService
adb shell settings put secure voice_interaction_service ai.openclaw.app/ai.openclaw.app.assistant.OpenClawVoiceInteractionService
```

### Verify it's set

```bash
adb shell settings get secure assistant
# Should output: ai.openclaw.app/ai.openclaw.app.assistant.OpenClawVoiceInteractionService
```

## Connecting to OpenClaw Gateway

### 1. Start the Gateway on your VPS

On your VPS (or local machine), ensure the OpenClaw gateway is running:

```bash
openclaw gateway run --bind 0.0.0.0 --port 18789 --force
```

### 2. Configure the Android app

1. Open the **OpenClaw** app on your Samsung Galaxy
2. Go to **Settings** (gear icon)
3. Enable **Manual gateway**
4. Enter your VPS IP address as the **Host** (e.g., `203.0.113.50`)
5. Enter **18789** as the **Port**
6. Enable **TLS** if your gateway uses TLS (recommended)
7. Enter your gateway **token** if required
8. Tap **Connect**

### 3. Verify connection

The app should show "Connected" status. You can verify in the app's home screen.

## Using the Assistant

Once OpenClaw is set as the default assistant:

- **Long-press the Home button** to trigger the assistant
- The assistant will start listening for your voice
- Speak your request
- The assistant sends your speech to the OpenClaw Gateway
- The response is spoken back via text-to-speech

### Permissions

Make sure the following permissions are granted to OpenClaw:

- **Microphone** - required for voice input
- **Internet** - required for gateway communication

You can grant these in Settings > Apps > OpenClaw Node > Permissions.

## Troubleshooting

### "Speech recognition is not available"
- Ensure Google Speech Services is installed (comes with Google Play Services)
- Check that the device language is supported

### "Gateway is not connected"
- Open the OpenClaw app and verify the connection status
- Check that your VPS is reachable from the phone's network
- Verify the gateway is running: `curl http://<VPS_IP>:18789/health`

### Assistant doesn't appear in default apps list
- Ensure the app is installed (not just sideloaded APK pending)
- Restart the phone after installation
- On some Samsung devices, you may need to disable Bixby first in Settings > Apps > Bixby Voice > Disable

### No audio response
- Check that media volume is not muted
- Verify TTS engine is installed: Settings > General management > Text-to-speech
- The app uses system TTS by default; ElevenLabs is available from the main voice screen
