package com.irregular.xenopowermeter.ui.about

import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.ArrayRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.ui.GlassRimHighlight
import com.irregular.xenopowermeter.ui.ProgressiveTopBlur
import com.irregular.xenopowermeter.ui.rememberTopEdgeProgress
import com.irregular.xenopowermeter.ui.scrollEdgeContentFade
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One release. The bullet list is a string array instead of a single block of
 * text so each line can be laid out with a hanging indent, and so the three
 * locales stay aligned entry-for-entry.
 *
 * Version numbers and the order here are the page's only source of truth; when
 * `versionName` in build.gradle.kts changes, add its array and put it first.
 */
private data class ChangelogRelease(
    val version: String,
    @ArrayRes val itemsRes: Int,
    val isLatest: Boolean = false
)

/** Newest first — the newest card sits at the top of the page. */
private val changelogReleases = listOf(
    ChangelogRelease("2.1.0", R.array.changelog_2_1_0, isLatest = true),
    ChangelogRelease("2.0.3", R.array.changelog_2_0_3),
    ChangelogRelease("2.0.2", R.array.changelog_2_0_2),
    ChangelogRelease("2.0.1", R.array.changelog_2_0_1),
    ChangelogRelease("2.0.0", R.array.changelog_2_0_0),
    ChangelogRelease("1.4.0", R.array.changelog_1_4_0),
    ChangelogRelease("1.3.0", R.array.changelog_1_3_0),
    ChangelogRelease("1.2.0", R.array.changelog_1_2_0)
)

/** Widest the column(s) are allowed to get on a very large tablet. */
private val ContentMaxWidth = 1080.dp

/**
 * Strength of the scrim over the page behind. Deep enough that the revealed
 * strip clearly sits one level down, light enough that the page is still
 * recognisable while the gesture is held.
 */
private const val ScrimAlpha = 0.4f

/**
 * How long the page takes to arrive, and to leave once the way out has been
 * chosen. Leaving is quicker than arriving: the arrival is the page
 * introducing itself, the exit has already been decided and must not make the
 * user wait for it.
 *
 * The arrival started at the reference project's 600ms, which is tuned for a
 * page that carries its own content in; this one is a short list over a page
 * the user was just looking at, and at 600ms the settle felt like waiting.
 * 440ms keeps the same shape but cuts the tail short.
 */
private const val OpenDurationMillis = 440
private const val CloseDurationMillis = 200

/**
 * Arrival curve, lifted from the schedule app's page transition: a Hermite
 * lead-in that brings the initial velocity to zero, blended into a strong
 * quart-out. The sheet eases off the edge instead of being flung from it, and
 * spends the last stretch creeping the final few dp into place — which is what
 * makes the push read as a page being laid down rather than a slide that snaps
 * shut. A plain decelerate curve here (which is what this used to be)
 * front-loads all the movement into the first 100ms and looks abrupt next to
 * the dismissal.
 */
private val OpenEasing = Easing { fraction ->
    val t = fraction.coerceIn(0f, 1f)
    // 0..k is the soft start; from k on the plain curve takes over, and the two
    // halves meet with matching value and slope at k so there is no kink.
    // The reference uses 0.48 — nearly half of its 600ms goes by before the
    // curve is fully under way. With the shorter duration that dead zone is
    // what reads as "not responding yet", so it is pulled in to 0.38.
    val k = 0.38f
    val shaped = if (t <= k) {
        -t * t * t / (k * k) + 2f * t * t / k
    } else {
        t
    }
    val inverse = 1f - shaped
    1f - inverse.pow(4.5f)
}

/** Tablet rule: two cards side by side from 600dp of window width up. */
private const val TwoColumnWidthDp = 600

/**
 * Full-screen release notes, opened from the About page's changelog row.
 *
 * A page rather than the old AlertDialog: the history is long enough that a
 * dialog would scroll its own text inside a box inside the page. Phones get one
 * column of cards; anything 600dp wide and up gets a staggered two-column grid,
 * which also covers a phone held sideways.
 *
 * The whole page is a single sheet that is pushed in from, and back out to, the
 * right edge, dimming the page behind it as it travels.
 */
@Composable
fun ChangelogScreen(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val onBackground = MaterialTheme.colorScheme.onBackground
    val cardColor = AppColors.cardColor()
    val accent = AppColors.linkColor()

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val twoColumns = screenWidthDp >= TwoColumnWidthDp

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // One line of title, so the bar only needs the compact app-bar height.
    val headerHeight = statusBarTop + 48.dp

    // Snapshot of the scrolling grid alone, for the material that softens the
    // seam under the header.
    val pageBackdrop = rememberLayerBackdrop()
    val gridState = rememberLazyStaggeredGridState()
    // The header material only exists once cards actually reach it.
    val topEdgeProgress = rememberTopEdgeProgress(gridState)

    // 1 = at rest, 0 = off the right edge. One value drives the movement in
    // both directions: opening animates it up from 0, the predictive back
    // gesture writes it down straight from the finger, and every other way out
    // (the back disc, a plain system back tap) animates it to 0 and only then
    // asks to be removed — so the page never pops out from under the gesture.
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // On a restore the page is already open: snap instead of replaying.
    var hasEntered by rememberSaveable { mutableStateOf(false) }

    // Opened: the sheet is pushed in from the right edge, the exact reverse of
    // the way the gesture pushes it back out, so the two directions read as one
    // movement rather than an entrance animation followed by a dismissal.
    //
    // Keyed on [visible] rather than on the first composition: the host mounts
    // this page while the About tab is on screen so its (measured ~60ms) first
    // layout is paid before the user taps, and only the tap starts the motion.
    LaunchedEffect(visible) {
        if (!visible) {
            // The page stays composed (pre-warmed) after it closes, so the flag
            // has to be cleared here: otherwise the next open would take the
            // restore branch below and snap into place instead of pushing in.
            hasEntered = false
            return@LaunchedEffect
        }
        if (hasEntered) {
            reveal.snapTo(1f)
        } else {
            hasEntered = true
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = OpenDurationMillis,
                    easing = OpenEasing
                )
            )
        }
    }

    fun close() {
        scope.launch {
            reveal.animateTo(0f, tween(CloseDurationMillis))
            onBack()
        }
    }

    // Disabled while the page sits composed but closed (pre-warmed on the About
    // tab): a hidden page must not eat the app's back gestures.
    PredictiveBackHandler(enabled = visible) { backEvents ->
        try {
            backEvents.collect { event ->
                reveal.snapTo(1f - event.progress.coerceIn(0f, 1f))
            }
            // Committed: the finger already carried the page part of the way,
            // so only the remainder is animated. A plain back tap arrives here
            // with no events at all, which is why this is an animation rather
            // than an immediate dismissal.
            reveal.animateTo(0f, tween(CloseDurationMillis))
            onBack()
        } catch (cancelled: CancellationException) {
            // Abandoned: spring back to rest and let the gesture be undone.
            // Launched on the page's own scope — this coroutine was just
            // cancelled along with the gesture, so animating here would die on
            // the first frame and leave the page stranded half-slid.
            scope.launch {
                reveal.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = 380f)
                )
            }
            throw cancelled
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The page is kept composed after it closes (see the host), which
            // leaves its whole tree in the accessibility hierarchy — TalkBack
            // would happily read out release notes that are parked off-screen.
            // Clearing the subtree's semantics while hidden keeps the pre-warm
            // invisible to a11y.
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            // Dims the page behind by how far the sheet has come on: on the way
            // in it deepens as the sheet covers more, on the way out it lifts as
            // the page is revealed again. It is drawn under the sheet, so at
            // rest it is invisible.
            .drawBehind {
                val dim = ScrimAlpha * reveal.value
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The sheet travels one to one with the finger while its
                // corners round off and a shadow picks it up off the page
                // behind — the way a card gets pushed aside. It used to fade
                // instead, which let the text underneath bleed through the copy
                // whenever the gesture was held halfway.
                .graphicsLayer {
                    val progress = 1f - reveal.value
                    translationX = size.width * progress
                    // Every property is written on every pass: this block runs
                    // against one long-lived layer, so a value left behind by
                    // the last gesture would stay applied at rest.
                    clip = progress > 0f
                    if (clip) {
                        // Corners and lift reach full strength while the card
                        // is still mostly on screen: growing them at the same
                        // rate as the slide left the sheet looking square and
                        // pasted on for the whole first half of the gesture.
                        shape = RoundedCornerShape(
                            20.dp * (progress * 2.5f).coerceAtMost(1f)
                        )
                        shadowElevation = 16.dp.toPx() * (progress * 2f).coerceAtMost(1f)
                    } else {
                        shadowElevation = 0f
                    }
                }
                .background(backgroundColor)
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(if (twoColumns) 2 else 1),
                state = gridState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .layerBackdrop(pageBackdrop)
                    // Opaque inside the recorded layer, so the material above
                    // covers the page instead of letting sharp cards through.
                    .background(backgroundColor)
                    // Cards fade out as they approach the header, before they
                    // are recorded; the blur then never sits over sharp text.
                    .scrollEdgeContentFade(
                        progress = { topEdgeProgress.value },
                        headerHeight = headerHeight,
                        headerTop = statusBarTop
                    ),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = headerHeight + 16.dp,
                    bottom = navigationBarBottom + 24.dp
                ),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = changelogReleases.size,
                    key = { changelogReleases[it].version }
                ) { index ->
                    ReleaseCard(
                        release = changelogReleases[index],
                        cardColor = cardColor,
                        accent = accent
                    )
                }
            }

            // Cards scroll all the way to the top of the window, state bar
            // included, and melt on their way there instead of meeting a solid
            // plate that cut them off mid-card. The slab reaches full strength
            // at the status bar and fades to nothing 34dp under the header,
            // where the cards' own fade has already brought sharpness back.
            ProgressiveTopBlur(
                backdrop = pageBackdrop,
                progress = { topEdgeProgress.value },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth),
                bandHeight = headerHeight,
                maxBlur = 18.dp
            )

            // Title row, no plate behind it: content runs underneath and the
            // band above is what keeps the two apart.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(headerHeight)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = statusBarTop)
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChangelogBackButton(onClick = { close() })

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = stringResource(R.string.changelog_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: ChangelogRelease,
    cardColor: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color
) {
    val items = stringArrayResource(release.itemsRes)
    val onBackground = MaterialTheme.colorScheme.onBackground

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        // The current release gets a hairline rim so it reads as the top of
        // the list even when it has already scrolled half out of frame.
        border = if (release.isLatest) {
            BorderStroke(1.dp, accent.copy(alpha = 0.35f))
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = release.version,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = onBackground
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items.forEach { item ->
                    ReleaseItem(text = item, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun ReleaseItem(text: String, accent: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // 5dp dot, nudged down to the optical centre of the first text line.
        // A real column instead of a "•" prefix keeps wrapped lines indented.
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.7f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The header's back disc — the same piece of glass as the landscape action
 * circles: the rim catches the 45° light, a press scales the whole disc, and a
 * pool of light follows the finger. No haptic on purpose: the app only buzzes
 * on the bottom bar, the rail and those three circles.
 */
@Composable
private fun ChangelogBackButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "changelogBackPress"
    )
    val discColor = AppColors.cardColor()
    val iconTint = AppColors.linkColor()
    // The pale disc takes a gentler pool: the alpha that reads as light on the
    // dark card clips to flat white here.
    val haloPeak = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.26f else 0.18f

    // The disc is its own piece of glass. Nothing but the solid header sits
    // behind it, so the backdrop is empty and the tint fills the shape — the
    // rim is the part that makes it read as glass.
    val surface = remember(discColor) {
        Modifier.drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { CircleShape },
            effects = {},
            highlight = GlassRimHighlight,
            shadow = null,
            innerShadow = null,
            onDrawSurface = { drawRect(discColor) }
        )
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            // The scale wraps the glass too, so the disc grows as one piece.
            .graphicsLayer {
                val scale = 1f + 0.10f * progress
                scaleX = scale
                scaleY = scale
            }
            .then(surface)
            // The pool stays inside the circle; unclipped it would spill onto
            // the header and read as the page reacting instead of the button.
            .clip(CircleShape)
            .drawWithContent {
                if (progress > 0.001f) {
                    // The disc lifts a touch, then a core of light pools in the
                    // middle with a long falloff towards the rim.
                    drawRect(
                        Color.White.copy(alpha = 0.05f * progress),
                        blendMode = BlendMode.Plus
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = haloPeak * progress),
                            0.38f to Color.White.copy(alpha = haloPeak * 0.7f * progress),
                            1f to Color.Transparent
                        ),
                        radius = size.minDimension * 0.95f,
                        blendMode = BlendMode.Plus
                    )
                }
                drawContent()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}