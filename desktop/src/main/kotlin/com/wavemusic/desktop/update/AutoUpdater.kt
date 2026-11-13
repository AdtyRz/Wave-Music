package com.wavemusic.desktop.update

import com.wavemusic.desktop.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Auto-updater for Wave Music.
 *
 * Supports two modes, auto-detected:
 * - **Installed mode**: Downloads EXE installer and launches it. The installer handles everything.
 * - **Portable mode**: Downloads portable ZIP, extracts to staging, uses PowerShell script to
 *   swap files while the app is closed, then restarts.
 *
 * Detection: if the app directory contains an uninstaller (unins000.exe from NSIS) or is under
 * Program Files, it's installed. Otherwise it's portable.
 */
object AutoUpdater {
    /**
     * Generated from `wavemusicVersion` in build.gradle.kts (version.properties), so the version is
     * defined once. A missing resource means a broken build, and "0.0.0" would make every release
     * look newer, so it fails loudly instead.
     */
    val CURRENT_VERSION: String = AutoUpdater::class.java.getResourceAsStream("/version.properties")
        ?.use { java.util.Properties().apply { load(it) }.getProperty("version") }
        ?: error("version.properties missing from the build")

    /**
     * Windows replaces itself. A package-managed Linux install downloads the signed .rpm/.deb
     * and hands it to the package manager through pkexec; a user-writable install (such as
     * ~/.local/opt/wvusic) swaps its own directory from the release tarball. A read-only or
     * dev copy only points at the release page.
     */
    val canSelfUpdate: Boolean get() = Platform.isWindows || linuxPackage != null || linuxPortable
    val requiresPackageManager: Boolean get() = linuxPackage != null

    /** "rpm" or "deb" when this copy is the one the wvusic package installed in /opt, else null. */
    private val linuxPackage: String? by lazy {
        if (!Platform.isLinux) return@lazy null
        val jar = runCatching { AutoUpdater::class.java.protectionDomain.codeSource.location.path }.getOrNull()
        if (jar?.startsWith("/opt/wvusic/") != true) return@lazy null
        fun owns(vararg cmd: String) = runCatching {
            ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)
        when {
            owns("rpm", "-q", "wvusic") -> "rpm"
            owns("dpkg-query", "-W", "wvusic") -> "deb"
            else -> null
        }
    }

    /** A user-writable Linux install (e.g. ~/.local/opt/wvusic) can replace itself. */
    private val linuxPortable: Boolean by lazy {
        if (!Platform.isLinux || linuxPackage != null) return@lazy false
        findAppDirectory()?.let { it.canWrite() && File(it, "bin/Wvusic").exists() } == true
    }

    /**
     * Ed25519 public key (base64 X.509) that release ZIPs are signed with. The private half stays
     * offline, so a compromised GitHub account cannot ship code: HTTPS-to-GitHub alone only proves
     * the file came from GitHub. Generate the pair with `./gradlew :desktop:generateUpdateSigningKey`
     * and sign each release with `:desktop:signPortableZip`.
     *
     * While this is blank, updates install unverified as before (and say so in the log). Once set,
     * a release without a valid `<zip>.sig` asset is refused.
     */
    internal const val UPDATE_PUBLIC_KEY = "MCowBQYDK2VwAyEAO/Mj6HSdZ0QXebmmO/gXuvb+QYnll0E/Xy5aF7wSI/4="
    private const val GITHUB_OWNER = "AdtyRz"
    private const val GITHUB_REPO = "Wave-Music"

    /** Launcher path inside the app directory, per platform. */
    private val LAUNCHER_NAMES = if (Platform.isWindows) listOf("Wvusic.exe") else listOf("bin/Wvusic")
    private val TAG_PREFIX = if (Platform.isWindows) "windows-v" else "linux-v"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _state.asStateFlow()

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
        val html_url: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long = 0
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class UpdateAvailable(
            val version: String,
            val releaseNotes: String?,
            val downloadUrl: String,
            val downloadSize: Long,
            val releaseUrl: String?,
            val isPortable: Boolean,
            val signatureUrl: String? = null
        ) : UpdateState()
        data class Downloading(val progress: Float, val version: String) : UpdateState()
        data class ReadyToInstall(val version: String, val updateFile: File, val isPortable: Boolean) : UpdateState()
        data class Error(val message: String) : UpdateState()
        data object UpToDate : UpdateState()
    }

    // ==================== Public API ====================

    fun checkForUpdates() {
        checkForUpdatesFromGitHub()
    }

    private fun checkForUpdatesFromGitHub() {
        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    _state.value = UpdateState.Error("Could not reach GitHub")
                    return@launch
                }
                if (release.draft || release.prerelease) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                val latestVersion = release.tag_name.removePrefix(TAG_PREFIX)
                Timber.i("Update check: current=$CURRENT_VERSION, latest=$latestVersion")

                if (!isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                if (!canSelfUpdate) {
                    _state.value = UpdateState.UpdateAvailable(
                        version = latestVersion,
                        releaseNotes = release.body,
                        downloadUrl = release.html_url ?: "",
                        downloadSize = 0,
                        releaseUrl = release.html_url,
                        isPortable = false
                    )
                    return@launch
                }

                val portable = isPortableInstall()
                Timber.i("Install mode: ${linuxPackage ?: if (portable) "portable" else "installed"}")

                val asset = when {
                    linuxPackage != null -> release.assets.find { it.name.endsWith(".$linuxPackage") }
                    // Linux portable installs replace their own directory from a tarball.
                    Platform.isLinux -> release.assets.find {
                        it.name.endsWith(".tar.gz") && it.name.contains("linux", ignoreCase = true)
                    }
                    portable -> release.assets.find {
                        it.name.contains("portable", ignoreCase = true) && it.name.endsWith(".zip")
                    }
                    else -> release.assets.find { it.name.endsWith(".exe", ignoreCase = true) }
                        ?: release.assets.find { it.name.endsWith(".msi", ignoreCase = true) }
                }

                if (asset == null) {
                    val kind = when {
                        linuxPackage != null -> ".$linuxPackage package"
                        Platform.isLinux -> "Linux tarball"
                        portable -> "portable ZIP"
                        else -> "installer"
                    }
                    Timber.w("No $kind in release assets: ${release.assets.map { it.name }}")
                    _state.value = UpdateState.Error("No $kind found for v$latestVersion")
                    return@launch
                }

                Timber.i("Update available: v$latestVersion, asset=${asset.name} (${asset.size} bytes)")
                _state.value = UpdateState.UpdateAvailable(
                    version = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = asset.browser_download_url,
                    downloadSize = asset.size,
                    releaseUrl = release.html_url,
                    isPortable = portable,
                    signatureUrl = release.assets.find { it.name == asset.name + ".sig" }?.browser_download_url
                )
            } catch (e: Exception) {
                Timber.e("Update check failed: ${e.message}")
                _state.value = UpdateState.Error("Update check failed: ${e.message}")
            }
        }
    }

    fun downloadAndInstall() {
        val current = _state.value
        if (current !is UpdateState.UpdateAvailable || !canSelfUpdate) return

        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f, current.version)

                val updateDir = getUpdateDirectory()
                Files.createDirectories(updateDir.toPath())

                val fileName = current.downloadUrl.substringAfterLast("/")
                val downloadFile = File(updateDir, fileName)

                Timber.i("Downloading ${current.downloadUrl} → ${downloadFile.name}")
                downloadFile(current.downloadUrl, downloadFile, current.downloadSize) { progress ->
                    _state.value = UpdateState.Downloading(progress, current.version)
                }
                Timber.i("Download complete: ${downloadFile.length()} bytes")

                if (!verifyDownload(downloadFile, current.signatureUrl, updateDir)) {
                    downloadFile.delete()
                    _state.value = UpdateState.Error(
                        "The update's signature is missing or invalid, so it was not installed. " +
                            "Download it from the release page instead."
                    )
                    return@launch
                }

                if (current.isPortable) {
                    // Extract to a fresh staging dir; the old files stay untouched until the swap.
                    val stagingDir = File(updateDir, "staging-${System.currentTimeMillis()}")
                    // Clean up any old staging dirs
                    updateDir.listFiles()?.filter { it.name.startsWith("staging") }?.forEach { old ->
                        try { old.deleteRecursively() } catch (_: Exception) {}
                    }
                    Files.createDirectories(stagingDir.toPath())

                    Timber.i("Extracting to ${stagingDir.absolutePath}")
                    if (current.downloadUrl.endsWith(".tar.gz")) {
                        extractTarGz(downloadFile, stagingDir)
                    } else {
                        extractZip(downloadFile, stagingDir)
                    }
                    downloadFile.delete()

                    // Both release archives wrap everything in one top-level "Wvusic" directory.
                    val contents = stagingDir.listFiles() ?: emptyArray()
                    val sourceDir = if (contents.size == 1 && contents[0].isDirectory) {
                        Timber.i("Archive root dir: ${contents[0].name}")
                        contents[0]
                    } else {
                        stagingDir
                    }

                    val hasLauncher = LAUNCHER_NAMES.any { File(sourceDir, it).exists() }
                    if (!hasLauncher) {
                        Timber.e("No launcher in: ${sourceDir.listFiles()?.map { it.name }}")
                        _state.value = UpdateState.Error("Invalid update package, no ${LAUNCHER_NAMES.first()} found")
                        return@launch
                    }

                    _state.value = UpdateState.ReadyToInstall(current.version, sourceDir, isPortable = true)
                } else {
                    // Installer mode: just keep the downloaded EXE/MSI
                    _state.value = UpdateState.ReadyToInstall(current.version, downloadFile, isPortable = false)
                }
            } catch (e: Exception) {
                Timber.e("Download/extract failed: ${e.message}")
                _state.value = UpdateState.Error("Download failed: ${e.message}")
            }
        }
    }

    /** False only when a key is configured and the signature is missing or wrong. */
    private fun verifyDownload(file: File, signatureUrl: String?, dir: File): Boolean {
        if (UPDATE_PUBLIC_KEY.isBlank()) {
            Timber.w("No update signing key configured; installing unverified")
            return true
        }
        if (signatureUrl == null) {
            Timber.e("Release has no signature asset")
            return false
        }
        val sigFile = File(dir, file.name + ".sig")
        return try {
            downloadFile(signatureUrl, sigFile, 0) {}
            verifySignature(file, sigFile.readText().trim(), UPDATE_PUBLIC_KEY)
        } catch (e: Exception) {
            Timber.e("Signature check failed: ${e.message}")
            false
        } finally {
            sigFile.delete()
        }
    }

    /**
     * Ed25519 over the file's SHA-256. Pure Ed25519 needs the whole message in memory, and a
     * release ZIP is ~200 MB against a 512 MB heap; the digest streams.
     */
    internal fun verifySignature(file: File, signatureB64: String, publicKeyB64: String): Boolean = try {
        val key = java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKeyB64)))
        java.security.Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(sha256(file))
            verify(java.util.Base64.getDecoder().decode(signatureB64))
        }
    } catch (e: Exception) {
        Timber.e("Signature verification error: ${e.message}")
        false
    }

    internal fun sha256(file: File): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    fun applyUpdate() {
        val current = _state.value
        if (current !is UpdateState.ReadyToInstall) return

        try {
            if (linuxPackage != null) {
                applyLinuxPackageUpdate(current)
            } else if (Platform.isLinux && current.isPortable) {
                applyLinuxPortableUpdate(current)
            } else if (current.isPortable) {
                applyPortableUpdate(current)
            } else {
                applyInstallerUpdate(current)
            }
        } catch (e: Exception) {
            Timber.e("Failed to apply update: ${e.message}")
            _state.value = UpdateState.Error("Failed to start update: ${e.message}")
        }
    }

    private fun applyInstallerUpdate(state: UpdateState.ReadyToInstall) {
        val installer = state.updateFile
        if (!installer.exists()) {
            _state.value = UpdateState.Error("Installer file not found")
            return
        }

        Timber.i("Launching installer: ${installer.absolutePath}")
        if (installer.name.endsWith(".msi", ignoreCase = true)) {
            ProcessBuilder("msiexec", "/i", installer.absolutePath).start()
        } else {
            ProcessBuilder(installer.absolutePath).start()
        }

        Timber.i("Installer launched, exiting app...")
        Runtime.getRuntime().exit(0)
    }

    /**
     * pkexec shows the desktop's own password dialog, so this blocks until the user answers: it
     * runs off the UI thread. The package manager swaps /opt/wavemusic by rename, so the running
     * copy is unharmed, and the new one starts only after this process is gone, since
     * SingleInstance would otherwise hand the launch straight back to us.
     */
    private fun applyLinuxPackageUpdate(state: UpdateState.ReadyToInstall) {
        val pkg = state.updateFile.absolutePath
        val cmd = when {
            linuxPackage == "deb" -> listOf("pkexec", "apt-get", "install", "-y", pkg)
            File("/usr/bin/dnf").exists() -> listOf("pkexec", "dnf", "install", "-y", pkg)
            else -> listOf("pkexec", "rpm", "-U", pkg)
        }
        scope.launch {
            try {
                Timber.i("Installing update: ${cmd.joinToString(" ")}")
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                if (code != 0) {
                    Timber.e("Package install failed ($code): ${output.takeLast(500)}")
                    // 126/127: the password dialog was dismissed or refused.
                    _state.value = UpdateState.Error(
                        if (code == 126 || code == 127) "Update cancelled. Click Check for updates to try again."
                        else "The package manager could not install the update (exit $code)."
                    )
                    return@launch
                }
                state.updateFile.delete()
                val pid = ProcessHandle.current().pid()
                // Output goes nowhere: a pipe back to this JVM would SIGPIPE the new copy once we exit.
                ProcessBuilder("sh", "-c", "while kill -0 $pid 2>/dev/null; do sleep 0.3; done; exec /usr/bin/wvusic")
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Timber.i("Update installed, restarting...")
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) {
                Timber.e("Failed to install update: ${e.message}")
                _state.value = UpdateState.Error("Failed to install update: ${e.message}")
            }
        }
    }

    /**
     * User-local Linux installs (no root-owned package) swap their own directory after the app
     * exits. The new tree is copied first, so a failed copy leaves the old install untouched.
     */
    private fun applyLinuxPortableUpdate(state: UpdateState.ReadyToInstall) {
        val sourceDir = state.updateFile
        val appDir = findAppDirectory()
        if (appDir == null) {
            _state.value = UpdateState.Error("Cannot determine app directory")
            return
        }

        val updateDir = getUpdateDirectory()
        Files.createDirectories(updateDir.toPath())
        val scriptFile = File(updateDir, "wvusic-update.sh")
        scriptFile.writeText(
            buildLinuxPortableUpdateScript(
                pid = ProcessHandle.current().pid(),
                sourcePath = sourceDir.absolutePath,
                destPath = appDir.absolutePath,
                stagingPath = sourceDir.parentFile?.absolutePath ?: sourceDir.absolutePath,
                logPath = File(updateDir, "update.log").absolutePath
            )
        )
        scriptFile.setExecutable(true, true)

        Timber.i("Portable Linux update: ${sourceDir.absolutePath} -> ${appDir.absolutePath}")
        ProcessBuilder("/bin/sh", scriptFile.absolutePath)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        Runtime.getRuntime().exit(0)
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun buildLinuxPortableUpdateScript(
        pid: Long,
        sourcePath: String,
        destPath: String,
        stagingPath: String,
        logPath: String
    ): String = """
        #!/bin/sh
        # Written by the Wvusic updater. Copies the new tree, swaps it in, and restarts.
        exec >>${shQuote(logPath)} 2>&1
        echo "=== Wvusic portable update ${'$'}(date) ==="
        while kill -0 $pid 2>/dev/null; do sleep 0.3; done
        sleep 1
        DEST=${shQuote(destPath)}
        SOURCE=${shQuote(sourcePath)}
        NEW="${'$'}DEST.new.${'$'}${'$'}"
        OLD="${'$'}DEST.old.${'$'}${'$'}"
        rm -rf "${'$'}NEW"
        if cp -a "${'$'}SOURCE" "${'$'}NEW"; then
            if mv "${'$'}DEST" "${'$'}OLD"; then
                if mv "${'$'}NEW" "${'$'}DEST"; then
                    rm -rf "${'$'}OLD"
                    rm -rf ${shQuote(stagingPath)}
                    echo "Update installed"
                else
                    mv "${'$'}OLD" "${'$'}DEST"
                    echo "ERROR: could not move the new tree into place"
                fi
            else
                rm -rf "${'$'}NEW"
                echo "ERROR: could not move the old install aside"
            fi
        else
            rm -rf "${'$'}NEW"
            echo "ERROR: could not copy the update"
        fi
        exec "${'$'}DEST/bin/Wvusic"
    """.trimIndent()

    private fun applyPortableUpdate(state: UpdateState.ReadyToInstall) {
        val sourceDir = state.updateFile
        val appDir = findAppDirectory()
        if (appDir == null) {
            _state.value = UpdateState.Error("Cannot determine app directory")
            return
        }

        Timber.i("Portable update: ${sourceDir.absolutePath} → ${appDir.absolutePath}")

        val updateDir = getUpdateDirectory()
        val pid = ProcessHandle.current().pid()
        val logFile = File(updateDir, "update.log").absolutePath
        val scriptFile = File(updateDir, "wavemusic-update.ps1")

        scriptFile.writeText(buildPortableUpdateScript(
            pid = pid,
            sourcePath = sourceDir.absolutePath,
            destPath = appDir.absolutePath,
            exePath = LAUNCHER_NAMES.map { File(appDir, it) }.firstOrNull { it.exists() }
                ?.absolutePath ?: File(appDir, LAUNCHER_NAMES.first()).absolutePath,
            logPath = logFile,
            stagingRoot = sourceDir.parentFile.absolutePath
        ))

        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", scriptFile.absolutePath
        ).directory(updateDir).redirectErrorStream(true).start()

        Timber.i("Update script launched, exiting...")
        Runtime.getRuntime().exit(0)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    // ==================== Portable update script ====================

    /**
     * Quote a Windows path for a PowerShell *single*-quoted literal.
     *
     * These paths are wherever the user unzipped the app, and `$` is legal in a Windows folder
     * name. Inside "..." PowerShell would expand `$Music` to nothing, so the update would copy to
     * the wrong place and only say so in a log nobody reads. And `$(...)`, also a legal folder
     * name, would be executed outright. Single quotes suppress both; the only escape a
     * single-quoted string needs is a doubled quote.
     */
    private fun psQuote(path: String): String = "'" + path.replace("'", "''") + "'"

    // Quoting the assignment is only half of it: the value still has to survive being handed to
    // a native exe. Start-Process -ArgumentList concatenates without quoting, so robocopy is
    // invoked directly below, where PowerShell quotes each argument itself.

    private fun buildPortableUpdateScript(
        pid: Long, sourcePath: String, destPath: String,
        exePath: String, logPath: String, stagingRoot: String
    ): String = """
        ${'$'}ErrorActionPreference = "Continue"
        ${'$'}SourcePath = ${psQuote(sourcePath)}
        ${'$'}DestPath = ${psQuote(destPath)}
        ${'$'}ExePath = ${psQuote(exePath)}
        ${'$'}LogPath = ${psQuote(logPath)}
        ${'$'}StagingRoot = ${psQuote(stagingRoot)}
        function Log(${'$'}msg) {
            ${'$'}ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            "${'$'}ts  ${'$'}msg" | Out-File -FilePath ${'$'}LogPath -Append -Encoding UTF8
        }
        Log "=== Wave Music Portable Update ==="
        Log "Source: ${'$'}SourcePath"
        Log "Destination: ${'$'}DestPath"

        # Wait for app to exit (max 60s)
        ${'$'}waited = 0
        while (${'$'}waited -lt 60) {
            ${'$'}proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if (-not ${'$'}proc) { Log "Process exited after ${'$'}waited seconds"; break }
            Start-Sleep -Seconds 1
            ${'$'}waited++
        }
        Start-Sleep -Seconds 2

        # Copy with robocopy
        Log "Copying files..."
        & robocopy.exe ${'$'}SourcePath ${'$'}DestPath /E /IS /IT /R:5 /W:2 /NFL /NDL /NP |
            Out-File -FilePath ${'$'}LogPath -Append -Encoding UTF8
        ${'$'}exitCode = ${'$'}LASTEXITCODE

        if (${'$'}exitCode -lt 8) {
            Log "Copy succeeded (exit code: ${'$'}exitCode)"
        } else {
            Log "ERROR: Copy failed (exit code: ${'$'}exitCode)"
        }

        # Restart
        Log "Starting Wvusic..."
        Start-Process -FilePath ${'$'}ExePath

        Start-Sleep -Seconds 3
        Remove-Item -Path ${'$'}StagingRoot -Recurse -Force -ErrorAction SilentlyContinue
        Log "=== Update Complete ==="
        Remove-Item -Path ${'$'}MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
    """.trimIndent()

    // ==================== Detection ====================

    /**
     * Installed only when an installer left its uninstaller behind. Releases ship no Windows
     * installer, so through 2.18.1 guessing from the folder (Program Files, %LOCALAPPDATA%) sent
     * portable copies living there hunting for an .exe/.msi asset: "No installer found".
     */
    private fun isPortableInstall(): Boolean {
        if (Platform.isLinux) {
            if (linuxPackage != null) return false
            val appDir = findAppDirectory() ?: return true
            return appDir.canWrite()
        }
        val appDir = findAppDirectory() ?: return true // default to portable if unknown
        return !File(appDir, "unins000.exe").exists()
    }

    // ==================== Network ====================

    /**
     * Releases are published per platform (`android-v1.0.0`, `windows-v1.1.0`, `linux-v1.2.0`)
     * so each app can move at its own version. Fetch the list and pick this platform's newest.
     */
    private fun fetchLatestRelease(): GitHubRelease? {
        val connection = URI("$API_URL?per_page=30").toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Wvusic/$CURRENT_VERSION")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<List<GitHubRelease>>(body)
                    .firstOrNull {
                        !it.draft && !it.prerelease && it.tag_name.startsWith(TAG_PREFIX)
                    }
            } else {
                val error = try { connection.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                Timber.w("GitHub API HTTP ${connection.responseCode}: $error")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Every hop of the update download has to stay on HTTPS and on GitHub.
     *
     * What arrives here is extracted over the app directory and then executed, so the transport
     * is the whole trust chain, since there is no signature to fall back on. The redirect loop below
     * follows `Location` by hand, and without this check a single hop answering with an
     * `http://` target would turn the updater into a cleartext delivery channel for code that
     * runs as the user. Anything not on github.com is refused for the same reason.
     */
    private fun requireTrustedUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw SecurityException("Malformed update URL: $url")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw SecurityException("Refusing non-HTTPS update URL (scheme: ${uri.scheme})")
        }
        val host = uri.host?.lowercase()
            ?: throw SecurityException("Update URL has no host: $url")
        val trusted = host == "github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
        if (!trusted) {
            throw SecurityException("Refusing update download from untrusted host: $host")
        }
    }

    private fun downloadFile(url: String, target: File, totalSize: Long, onProgress: (Float) -> Unit) {
        var currentUrl = url
        var redirects = 0

        requireTrustedUrl(currentUrl)

        val connection: HttpURLConnection
        while (true) {
            val conn = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Wvusic/$CURRENT_VERSION")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw Exception("Redirect without Location header (HTTP $code)")
                conn.disconnect()
                // Resolve relative Locations against the current URL before checking, so a
                // relative hop cannot slip past the scheme test by having no scheme at all.
                currentUrl = URI(currentUrl).resolve(location).toString()
                requireTrustedUrl(currentUrl)
                if (++redirects > 10) throw Exception("Too many redirects ($redirects)")
                continue
            }
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { "" }
                conn.disconnect()
                throw Exception("HTTP $code: $err")
            }
            connection = conn
            break
        }

        val actualSize = if (totalSize > 0) totalSize else connection.contentLengthLong.coerceAtLeast(1)
        var downloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    onProgress((downloaded.toFloat() / actualSize).coerceIn(0f, 0.99f))
                }
            }
        }
        connection.disconnect()
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Normalize backslashes — PowerShell Compress-Archive uses \ in entry names,
                // but Java's ZipEntry.isDirectory() only checks for trailing /
                val name = entry.name.replace('\\', '/')
                val outFile = File(targetDir, name)

                // Zip slip protection
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip slip: $name")
                }

                try {
                    if (entry.isDirectory || name.endsWith("/")) {
                        Files.createDirectories(outFile.toPath())
                    } else {
                        // Always ensure parent dirs exist (ZIP may lack intermediate dir entries)
                        outFile.parentFile?.let { Files.createDirectories(it.toPath()) }
                        if (outFile.exists()) outFile.delete()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos, bufferSize = 65536) }
                        count++
                    }
                } catch (e: Exception) {
                    Timber.e("Failed to extract: $name → ${e.message}")
                    throw Exception("Cannot extract '$name': ${e.message}", e)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Timber.i("Extracted $count files to ${targetDir.absolutePath}")
    }

    /** Linux releases ship a .tar.gz, and GNU tar is always present there. */
    private fun extractTarGz(archive: File, targetDir: File) {
        Files.createDirectories(targetDir.toPath())
        val process = ProcessBuilder(
            "tar", "-xzf", archive.absolutePath,
            "-C", targetDir.absolutePath, "--no-same-owner"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) throw Exception("Cannot extract update (tar exit $code): ${output.takeLast(300)}")
        Timber.i("Extracted ${archive.name} to ${targetDir.absolutePath}")
    }

    // ==================== Helpers ====================

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val lParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(lParts.size, cParts.size)) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun getUpdateDirectory(): File {
        // Linux: /opt is root's. The data dir is ours alone, unlike a guessable /tmp path that
        // another user could pre-create and swap the package in between the check and the install.
        if (Platform.isLinux) return File(com.wavemusic.desktop.AppPaths.dataDir, "updates")

        // Put updates next to the app, not in Roaming
        val appDir = findAppDirectory()
        if (appDir != null && appDir.canWrite()) {
            return File(appDir, "updates")
        }

        // Fallback: next to working directory
        val userDir = File(System.getProperty("user.dir", "."))
        if (userDir.canWrite()) {
            return File(userDir, "updates")
        }

        // Last resort: temp directory (always writable)
        return File(System.getProperty("java.io.tmpdir"), "wavemusic-updates")
    }

    private fun findAppDirectory(): File? {
        // Strategy 1: compose.application.resources.dir → walk up to find exe
        System.getProperty("compose.application.resources.dir")?.let { resDir ->
            var dir = File(resDir)
            repeat(4) {
                dir = dir.parentFile ?: return@let
                if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
            }
        }

        // Strategy 2: user.dir
        File(System.getProperty("user.dir")).let { dir ->
            if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
        }

        // Strategy 3: code source location
        try {
            val codeSource = AutoUpdater::class.java.protectionDomain.codeSource
            if (codeSource != null) {
                var dir = File(codeSource.location.toURI())
                repeat(4) {
                    dir = dir.parentFile ?: return@repeat
                    if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
                }
            }
        } catch (_: Exception) {}

        // Strategy 4: common install locations
        val common = if (Platform.isLinux) {
            listOf(
                File(System.getProperty("user.home"), ".local/opt/wvusic"),
                File("/opt/wvusic")
            )
        } else {
            listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { File(it, "Wvusic") },
                File("C:/Program Files/Wvusic"),
                File("C:/Program Files (x86)/Wvusic")
            )
        }
        common.forEach { dir ->
            if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
        }

        return null
    }
}
