/**
 * WaveMusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavemusic.app.lyrics

import android.content.Context
import com.wavemusic.kugou.KuGou
import com.wavemusic.app.constants.EnableKugouKey
import com.wavemusic.app.utils.dataStore
import com.wavemusic.app.utils.get

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        KuGou.getLyrics(title, artist, duration, album)

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        KuGou.getAllPossibleLyricsOptions(title, artist, duration, album, callback)
    }
}
