# Personal intercom v0.2.14

The personal Android client contains only native intercom audio, receive gain, headset button controls, and background session handling. Speech recognition, captions, conversation history, speech commands, audio probes, Vosk and JNA have been removed.

The package remains `jp.es.staffintercom.personalspeech` to allow updating v0.2.13 without uninstalling. Version code is 28. The personal access asset and signing key are private and must not be committed. A local authorized build restores `android/app/src/main/assets/personal-access.txt` and signs with the existing personal speech key.

Build using JDK 17, Android SDK 35, and Gradle 8.13:

```sh
gradle -p android :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

CI produces a credential-free unsigned-for-update debug build. On-device Bluetooth and screen-off audio must still be verified on both phones.
