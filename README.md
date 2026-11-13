# Wave Music (Wvusic)

A free, open-source YouTube Music client for Android.

Wave Music is a rebranded and cleaned-up build based on the open-source
[Metrolist](https://github.com/MetrolistGroup/Metrolist) project. It keeps the
core YouTube Music experience: streaming, background playback, offline
downloads, synced lyrics, library sync, Listen Together, widgets and more.

## Build

Requirements: JDK 21, Android SDK 37, Gradle wrapper included.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleFossRelease
```

The APK is written to `app/build/outputs/apk/foss/release/`.

## Branches

- `android` - Android application (this branch)
- `windows` - Windows desktop build
- `linux` - Linux desktop build

## License

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
