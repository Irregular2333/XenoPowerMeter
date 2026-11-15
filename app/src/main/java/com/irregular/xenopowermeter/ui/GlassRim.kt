package com.irregular.xenopowermeter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle

/**
 * A whisper of a rim. The library default is a hard 0.5 dp hairline at full
 * layer alpha, which reads as a drawn white stroke. Widened and softened, then
 * dialled back, so it only catches along the 45° top-left arc — just enough to
 * lift the piece off the page without becoming an outline.
 *
 * Shared by every glass surface in the app — the bottom bar, the landscape
 * action circles and the changelog's back disc — so the light in the room
 * falls the same way on all of them.
 *
 * Top-level so the lambda keeps a stable identity across recompositions.
 */
internal val GlassRimHighlight: () -> Highlight? = {
    Highlight(
        width = 0.9.dp,
        blurRadius = 2.2.dp,
        alpha = 0.32f,
        style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.32f))
    )
}

/**
 * The recording pill's rim: same geometry as [GlassRimHighlight] but in a
 * warm red, so the whole pill — base and rim — reads as one red piece of
 * glass while a measurement is running. Breathing is drawn separately in
 * the pill's draw phase; this stays static while recording.
 */
internal val RecordingRimHighlight: () -> Highlight? = {
    Highlight(
        width = 0.9.dp,
        blurRadius = 2.2.dp,
        alpha = 0.38f,
        style = HighlightStyle.Default(color = Color(0xFFFFC9C2).copy(alpha = 0.55f))
    )
}
