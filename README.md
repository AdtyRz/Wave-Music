<div align="center">

<img src="desktop/src/main/resources/icon.png" width="112" alt="Wvusic logo">

# Wave Music (Wvusic) 1.1.1 for Windows

**A free, open-source YouTube Music player for Windows 10 and 11.**

Your library, playlists, synced lyrics and offline downloads. No ads, no telemetry.

</div>

## About

Wave Music is a rebranded desktop build based on the open-source
[Lyrenne](https://github.com/Doctordefector/Lyrenne) project, which is itself a
desktop port of Metrolist. It uses Compose Desktop with VLC for playback and keeps
everything in its own portable folder on Windows.

This branch publishes the Windows desktop build. The other platforms live on their own branches:

| Branch | Platform | Version |
| --- | --- | --- |
| `android` | Android | 1.0.0 |
| `windows` | Windows 10/11 (portable ZIP) | **1.1.1** (this branch) |
| `linux` | Linux x86_64 (`.deb`, `.rpm`, tarball) | 1.2.1 |

## System requirements

- Windows 10 64-bit (version 1809 or newer) or Windows 11
- 64-bit x86 processor; ARM64 is not supported yet
- 4 GB RAM minimum, 8 GB recommended
- About 600 MB free disk space (the unpacked folder is about 410 MB), plus
  room for downloads and cache
- 1280×720 display or larger
- VLC and ffmpeg are bundled; no separate install is needed

## Build

Requirements: JDK 21 and a Windows x64 host. The portable ZIP bundles its own
runtime, VLC and ffmpeg, so the target machine needs no Java install.

```bash
# Run from source
./gradlew :desktop:run

# Windows portable ZIP
./gradlew :desktop:packagePortableZip
```

## Data location

- Windows portable: everything stays inside the app folder.
- Downloads default to the `Downloads` folder inside the app folder.

## License

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
