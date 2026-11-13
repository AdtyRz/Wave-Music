/**
 * WaveMusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavemusic.app.models

import com.wavemusic.innertube.models.YTItem
import com.wavemusic.app.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
