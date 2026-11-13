<div align="center">

<img src="desktop/src/main/resources/icon.png" width="112" alt="Wvusic logo">

# Wave Music (Wvusic) 1.2.0 for Linux

**A free, open-source YouTube Music player for Linux.**

Your library, playlists, synced lyrics and offline downloads. No ads, no telemetry.

</div>

## About

Wave Music is a rebranded desktop build based on the open-source
[Lyrenne](https://github.com/Doctordefector/Lyrenne) project, which is itself a
desktop port of Metrolist. It uses Compose Desktop with VLC for playback and keeps
everything in its own portable folder on Windows.

This branch publishes the Linux desktop build. The other platforms live on their own branches:

| Branch | Platform | Version |
| --- | --- | --- |
| `android` | Android | 1.0.0 |
| `windows` | Windows 10/11 (portable ZIP) | 1.1.0 |
| `linux` | Linux x86_64 (`.deb`, `.rpm`, tarball) | **1.2.0** (this branch) |

## Build

Requirements: JDK 21. Linux playback needs VLC from your distribution.

```bash
# Run from source
./gradlew :desktop:run

# Windows portable ZIP (on Windows)
./gradlew :desktop:packagePortableZip

# Linux distributable and packages
./gradlew :desktop:createDistributable
nfpm package -f packaging/linux/nfpm.yaml -p deb -t dist/
nfpm package -f packaging/linux/nfpm.yaml -p rpm -t dist/
```

## Data location

- Windows portable: everything stays inside the app folder.
- Linux: `~/.local/share/wavemusic`, downloads in `~/Music/Wave Music` by
  default.

## License

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
