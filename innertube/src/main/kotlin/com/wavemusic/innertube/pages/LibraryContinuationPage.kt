package com.wavemusic.innertube.pages

import com.wavemusic.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
