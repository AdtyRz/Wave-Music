<div align="center">

<img src="desktop/src/main/resources/icon.png" width="112" alt="Wvusic logo">

# Wave Music (Wvusic) 1.2.1 for Linux

**A free, open-source YouTube Music player for Linux.**

Your library, playlists, synced lyrics and offline downloads. No ads, no telemetry.

</div>

## About

Wave Music is a rebranded desktop build based on the open-source
[Lyrenne](https://github.com/Doctordefector/Lyrenne) project, which is itself a
desktop port of Metrolist. It uses Compose Desktop with VLC for playback. On
Linux the app keeps its data in `~/.local/share/wavemusic`, and downloads go to
`~/Music/Wave Music` by default.

This branch publishes the Linux desktop build. The other platforms live on their own branches:

| Branch | Platform | Version |
| --- | --- | --- |
| `android` | Android | 1.0.0 |
| `windows` | Windows 10/11 (portable ZIP) | 1.1.1 |
| `linux` | Linux x86_64 (`.deb`, `.rpm`, tarball) | **1.2.1** (this branch) |

## System requirements

- 64-bit Linux (x86_64) with glibc 2.17 or newer
- X11 or Wayland desktop session (Wayland apps run through XWayland)
- VLC 3.0 or newer: `libvlc5` and `vlc-plugin-base` on Debian/Ubuntu,
  `vlc` on Fedora/openSUSE
- 4 GB RAM minimum, 8 GB recommended
- 500 MB free disk space for the app, plus room for downloads and cache
- 1280×720 display or larger
- PipeWire, PulseAudio or ALSA audio output

On older integrated GPUs, if the interface lags, start Wave Music with software
rendering:

```bash
_JAVA_OPTIONS="-Dskiko.renderApi=SOFTWARE -Dskiko.vsync.enabled=false" wvusic
```

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
