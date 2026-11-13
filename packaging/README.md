# Packaging

One branch per platform: `windows` produces the portable ZIP, `linux` produces
the `.deb` and `.rpm`.

## Windows

On a Windows host:

```powershell
./gradlew :desktop:packagePortableZip
```

The ZIP is written to `desktop/build/compose/binaries/main/app/` and contains a
`Wvusic/` folder. VLC is bundled, so the ZIP runs without installing anything.

## Linux

On Linux x86_64 with VLC installed:

```bash
./gradlew :desktop:createDistributable
export WAVEMUSIC_VERSION=1.2.0
mkdir -p dist
nfpm package -f packaging/linux/nfpm.yaml -p deb -t dist/
nfpm package -f packaging/linux/nfpm.yaml -p rpm -t dist/
tar -C desktop/build/compose/binaries/main/app -czf "dist/wvusic-$WAVEMUSIC_VERSION-linux-x86_64.tar.gz" Wvusic
```

The packages depend on the distribution's VLC; it is not bundled on Linux.
