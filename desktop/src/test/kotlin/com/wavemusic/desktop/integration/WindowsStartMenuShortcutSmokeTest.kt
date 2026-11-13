package com.wavemusic.desktop.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Calls the real shell COM objects, because a wrong vtable slot does not throw: it returns an
 * HRESULT nobody checks, or reads garbage. The ownership check that stops one copy of WaveMusic
 * deleting another copy's shortcut depends on reading the target back exactly.
 */
class WindowsStartMenuShortcutSmokeTest {

    @Test
    fun `a written shortcut reads back the executable it launches`() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val dir = Files.createTempDirectory("wavemusic shortcut").toFile()
        try {
            val exe = File(File(dir, "Fake App ${'$'}(x)"), "WaveMusic.exe").apply { parentFile.mkdirs(); writeText("") }
            val link = File(dir, "WaveMusic.lnk")
            assertTrue(WindowsStartMenuShortcut.write(link, exe))
            assertTrue(link.isFile)
            assertEquals(exe.absoluteFile, File(WindowsStartMenuShortcut.readTargetPath(link)!!).absoluteFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A thread already in the multi-threaded apartment answers CoInitializeEx(STA) with
     * RPC_E_CHANGED_MODE. This was treated as fatal, so the shortcut went unwritten whenever
     * WinRT had been on the thread first; this test only failed when run after the media
     * session smoke test, which is exactly that.
     */
    @Test
    fun `a thread already in the multi-threaded apartment can still write it`() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        var ok = false
        val worker = Thread {
            com.sun.jna.platform.win32.Ole32.INSTANCE.CoInitializeEx(null, 0x0) // COINIT_MULTITHREADED
            val dir = Files.createTempDirectory("wavemusic shortcut mta").toFile()
            try {
                val exe = File(dir, "WaveMusic.exe").apply { writeText("") }
                val link = File(dir, "WaveMusic.lnk")
                ok = WindowsStartMenuShortcut.write(link, exe) &&
                    File(WindowsStartMenuShortcut.readTargetPath(link)!!).absoluteFile == exe.absoluteFile
            } finally {
                dir.deleteRecursively()
                com.sun.jna.platform.win32.Ole32.INSTANCE.CoUninitialize()
            }
        }
        worker.start()
        worker.join()
        assertTrue(ok)
    }
}
