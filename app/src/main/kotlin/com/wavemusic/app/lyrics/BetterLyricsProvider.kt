/**
 * WaveMusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavemusic.app.lyrics

import android.content.Context
import com.wavemusic.app.betterlyrics.BetterLyrics
import com.wavemusic.app.constants.EnableBetterLyricsKey
import com.wavemusic.app.utils.dataStore
import com.wavemusic.app.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = BetterLyrics.getLyrics(title, artist, duration, album)
}
