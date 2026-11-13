# Wave Music (Wvusic)

A free, open-source YouTube Music client for Android.

Wave Music is a rebranded and cleaned-up build based on the open-source
[Metrolist](https://github.com/MetrolistGroup/Metrolist) project. It keeps the
core YouTube Music experience: streaming, background playback, offline
downloads, synced lyrics, library sync, Listen Together, widgets and more.

## Device requirements

- Android 8.0 Oreo (API 26) or newer
- 64-bit or 32-bit ARM, or x86/x86_64 (the universal APK contains all four ABIs)
- 2 GB RAM minimum; 3 GB or more recommended for background playback and downloads
- About 150 MB free storage for the app, plus space for offline downloads and cache
- Internet connection for streaming, search and library sync
- A Google account with YouTube Music to sign in

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
