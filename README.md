# Alphy

Alphy is an Android assistant with cloud chat, local voice processing, generated tools, and automations. It is an early development project. The current interface is primarily in Russian.

## Features

- Chat with DeepSeek using your own API key. Each request requires confirmation before content is sent.
- Create interactive tools with native controls and isolated JavaScript execution.
- Use widgets and approved device capabilities, including notifications and calendar access.
- Configure local automations for schedules, location, charging, and selected notifications.
- Use T-one or Whisper for speech recognition and Silero for speech synthesis when the required model files are present.

Alphy stores conversations and tool data on the device. It stores the provider key with Android Keystore. Cloud requests send the confirmed content to DeepSeek and can incur provider charges. Device actions and permissions require user approval.

## Requirements

- Android 10 or later on an ARM64 device.
- Git LFS, JDK 17, Android SDK platform 37, Android NDK 27.0.12077973, and CMake 3.22.1.
- A DeepSeek API key for cloud chat and tool generation.

## Build

Clone the repository with its native dependency:

```sh
git clone --recurse-submodules https://github.com/egavrin/Alphy.git
cd Alphy
git lfs pull
```

Set `ANDROID_HOME` to the Android SDK directory, or set `sdk.dir` in `local.properties`. Then build the debug APK:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app currently retains the application ID `com.alpha.assistant` and the device label `Alpha`.

## Voice models

The repository excludes the Silero model bundle because its license restricts commercial use. A fresh clone can build, but the complete local voice workflow does not work without that bundle. The historical ONNX export is not provided here. Local voice requires the exact files listed in [`app/src/main/assets/voice/manifest.json`](app/src/main/assets/voice/manifest.json), including Silero. The app verifies each file's size and SHA-256 before use. See [`docs/voice-integration.md`](docs/voice-integration.md) for source and license details.

## Tests

Run the Android instrumentation tests on an ARM64 device or emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Some workflows require device permissions, Google Play services, or the local voice models. See the documents in [`docs/`](docs/) for feature details and test limits.

## License

Alphy's own source code is available under the [MIT License](LICENSE). Third-party components and models retain their own licenses. See [`app/src/main/assets/licenses/VOICE-NOTICE.txt`](app/src/main/assets/licenses/VOICE-NOTICE.txt) for voice component notices.
