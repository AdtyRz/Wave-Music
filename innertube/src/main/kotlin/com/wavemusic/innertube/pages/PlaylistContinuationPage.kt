package com.wavemusic.innertube.pages

import com.wavemusic.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
