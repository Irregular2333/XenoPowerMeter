package com.irregular.xenopowermeter.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.AppSettings
import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.ui.BarPressState
import com.irregular.xenopowermeter.ui.GlassRimHighlight
import com.irregular.xenopowermeter.ui.RecordingRimHighlight
import com.irregular.xenopowermeter.ui.barPressSource
import com.irregular.xenopowermeter.ui.isTabletLandscape
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel

@Composable
fun MainScreen(viewModel: WaveformViewModel) {
    val isConnected by viewModel.isConnected.collectAsState()
    val isRecording by viewModel.recorder.isRecording.collectAsState()
    val isLandscape = isTabletLandscape()

    LaunchedEffect(isLandscape) {
        viewModel.setOrientation(isLandscape)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // In landscape the pager page already reserves the rail and applies the
        // shared gutters (MainActivity → RailPageStart), so the screen must not
        // add its own on top or the value card would sit 8dp further in than the
        // Settings/About columns. Portrait keeps the original 8dp.
        val edgeGutter = if (isLandscape) 0.dp else 8.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = edgeGutter, end = edgeGutter, top = 4.dp, bottom = 8.dp)
        ) {
            // ValuePanel and WaveformChart collect their own high-frequency
            // state (1Hz values / 4Hz waveform) internally, so those updates
            // recompose only their subtrees — never the button bar or the
            // screen-level layout.
            ValuePanel(viewModel)
            if (!isLandscape) {
                // Landscape moved the three actions onto the rail's glass
                // circles (MainActivity → RailActionButton); the column gives
                // their former rows to the chart.
                Spacer(Modifier.height(8.dp))
                ControlBar(
                    isConnected = isConnected,
                    isRecording = isRecording,
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() },
                    onToggleRecording = { viewModel.toggleRecording() },
                    onClear = { viewModel.clearWaveform() }
                )
            }
            // Keep the action row separated from both the readings and chart.
            Spacer(Modifier.height(8.dp))
            WaveformChart(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }

    }
}

/**
 * A pill of the same glass as the landscape action circles: the rim catches
 * the 45° light, a press dips the pill under a spring and pools light at the
 * finger. No ripple on purpose — glass answers with light, not ink. Disabled
 * pills neither react nor light up: their surface falls back to the muted
 * gray the old FilledTonalButtons used, and their content dims with it.
 */
@Composable
private fun GlassPill(
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    recording: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val pressState = remember(scope) { BarPressState(scope) }
    // A disabled pill drops its tint for the Material3 disabled-container
    // gray, so e.g. "record" without a device still reads as off — the old
    // FilledTonalButton look — instead of a pale but colored pill.
    // The red recording glass is blended upstream (RecordButton cross-fades
    // the tint), so the pill never hard-swaps between teal and red.
    val isRecordingPill = recording
    val surfaceTint = if (enabled) tint
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    // Deep surfaces take a stronger halo; on a pale one the same alpha clips
    // to outright white instead of reading as light. These pills are small,
    // so the pool runs hotter and tighter than the bar's — at the bar's
    // levels the light measured +5 brightness, invisible under a thumb.
    val haloPeak = if (surfaceTint.luminance() < 0.5f) 0.13f else 0.085f
    val surface = remember(surfaceTint, haloPeak, isRecordingPill) {
        Modifier.drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { RoundedCornerShape(50) },
            effects = {},
            highlight = if (isRecordingPill) RecordingRimHighlight else GlassRimHighlight,
            shadow = null,
            innerShadow = null,
            onDrawSurface = {
                drawRect(surfaceTint)
            },
            onDrawFront = {
                val progress = pressState.progress.value
                if (progress > 0.001f) {
                    val center = pressState.finger.value
                    val radius = size.minDimension * 1.3f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = haloPeak * progress),
                            0.5f to Color.White.copy(alpha = haloPeak * progress),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                }
            }
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // The scale wraps the glass too, so the pill grows as one piece.
            .graphicsLayer {
                val scale = 1f + 0.05f * pressState.progress.value
                scaleX = scale
                scaleY = scale
            }
            .then(surface)
            // Disabled pills stay inert: no press tracking, no light.
            .then(if (enabled) Modifier.barPressSource(pressState) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minHeight = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun ControlBar(
    isConnected: Boolean,
    isRecording: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleRecording: () -> Unit,
    onClear: () -> Unit
) {
    val barColor = AppColors.barColor()
    val buttonTextColor = AppColors.buttonTextColor()
    val connectButtonColor = AppColors.connectButtonColor()

    @Composable
    fun ConnectButton(modifier: Modifier = Modifier) {
        GlassPill(
            onClick = { if (isConnected) onDisconnect() else onConnect() },
            tint = connectButtonColor,
            modifier = modifier
        ) {
            val icon = if (isConnected) Icons.Default.LinkOff else Icons.Default.Usb
            AutoSizeButtonContent(
                icon = icon,
                text = if (isConnected) stringResource(R.string.disconnect) else stringResource(R.string.connect),
                color = buttonTextColor
            )
        }
    }

    @Composable
    fun RecordButton(modifier: Modifier = Modifier) {
        // Cross-fade the pill between the regular teal glass and the red
        // recording glass, so start/stop reads as a colour shift instead of
        // an instant swap.
        val transition by animateFloatAsState(
            targetValue = if (isRecording) 1f else 0f,
            animationSpec = tween(durationMillis = 500),
            label = "recordColorTransition"
        )
        val pillTint = lerp(barColor, lerp(barColor, Color(0xFFE53935), 0.62f), transition)
        val contentColor by animateColorAsState(
            targetValue = if (isRecording) Color.White else buttonTextColor,
            animationSpec = tween(durationMillis = 500),
            label = "recordContentColor"
        )
        val content: @Composable RowScope.() -> Unit = {
            AutoSizeButtonContent(
                icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                text = if (isRecording) stringResource(R.string.stop) else stringResource(R.string.record),
                // The recording pill is red glass — white content keeps the
                // contrast on both the light and dark red base.
                color = contentColor
            )
        }
        GlassPill(
            onClick = onToggleRecording,
            enabled = isConnected,
            tint = pillTint,
            modifier = modifier,
            recording = isRecording,
            content = content
        )
    }

    @Composable
    fun ClearButton(modifier: Modifier = Modifier) {
        var showClearDialog by remember { mutableStateOf(false) }

        if (showClearDialog) {
            // Resolve the strings here in the page composition — where the
            // hot language switch provably re-resolves — and pass them in as
            // plain values. A Dialog captures its locals when it opens, so
            // stringResource inside the dialog can lag a language switch.
            val title = stringResource(R.string.clear_waveform_confirm_title)
            val message = stringResource(R.string.clear_waveform_confirm_message)
            val confirmLabel = stringResource(R.string.confirm_clear)
            val cancelLabel = stringResource(R.string.cancel)
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        onClear()
                    }) {
                        Text(confirmLabel)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(cancelLabel)
                    }
                }
            )
        }

        GlassPill(
            onClick = { showClearDialog = true },
            enabled = !isRecording,
            tint = barColor,
            modifier = modifier
        ) {
            AutoSizeButtonContent(
                icon = Icons.Default.Clear,
                text = stringResource(R.string.clear_waveform),
                color = buttonTextColor
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ConnectButton(modifier = Modifier.weight(1f))
        ClearButton(modifier = Modifier.weight(1f))
        RecordButton(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AutoSizeButtonContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val textMaxWidth = (maxWidth - 17.dp).coerceAtLeast(0.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            // Platform auto-size (BasicText + TextAutoSize): the font size is
            // stepped down inside the real layout pipeline, so the label is
            // guaranteed to fit its constraints. The hand-rolled TextMeasurer
            // estimator this replaced could disagree with the actual render
            // and let "Connect Device" overflow the pill's right edge on
            // narrow phones.
            BasicText(
                text = text,
                modifier = Modifier.widthIn(max = textMaxWidth),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 7.sp,
                    maxFontSize = 13.sp,
                    stepSize = 0.5.sp
                ),
                style = TextStyle(
                    color = color,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Start
                )
            )
        }
    }
}

@Composable
fun ValuePanel(viewModel: WaveformViewModel) {
    val voltage by viewModel.currentVoltage.collectAsState()
    val current by viewModel.currentCurrent.collectAsState()
    val avgPower by viewModel.averagePower.collectAsState()
    val cardColor = AppColors.cardColor()
    val labelColor = AppColors.labelColor()
    val dividerColor = AppColors.dividerColor()
    val voltageColor = AppColors.voltageColor()
    val currentColor = AppColors.currentColor()
    val powerColor = AppColors.powerColor()
    val avgPowerColor = AppColors.avgPowerColor()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isLandscape = isTabletLandscape()
    val valueFontSize = when {
        isLandscape -> 20.sp
        screenWidthDp < 360 -> 13.sp
        else -> 15.sp
    }
    val labelFontSize = when {
        isLandscape -> 12.sp
        screenWidthDp < 360 -> 10.sp
        else -> 11.sp
    }
    val readingLineHeightMultiplier = if (isLandscape) 1.25f else 1.08f
    val readingPairGap = if (isLandscape) 4.dp else 5.dp
    // Keep the card's original height while compacting the label/value pair.
    val readingColumnHeight = with(LocalDensity.current) {
        MaterialTheme.typography.bodyLarge.lineHeight.toDp() * 2f
    }
    // The reading card is its own piece of glass, same as the changelog's
    // back disc: an empty backdrop, the card colour drawn as the surface and
    // the shared rim highlight catching the 45° light along the top-left arc.
    // That whisper of light lifts the card off the page without the drawn
    // outline an accent border made.
    //
    // Pressing answers like the bar does: the card dips to the finger under a
    // spring, and a pool of light follows the finger while it's down. The
    // press values are read inside draw, so a press only invalidates the draw
    // phase — not this modifier, and not the composition.
    val scope = rememberCoroutineScope()
    val pressState = remember(scope) { BarPressState(scope) }
    // Deep surfaces take a stronger halo; on a pale one the same alpha clips
    // to outright white instead of reading as light.
    val haloPeak = if (cardColor.luminance() < 0.5f) 0.10f else 0.045f
    val surface = remember(cardColor, haloPeak) {
        Modifier.drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { RoundedCornerShape(12.dp) },
            effects = {},
            highlight = GlassRimHighlight,
            shadow = null,
            innerShadow = null,
            onDrawSurface = { drawRect(cardColor) },
            onDrawFront = {
                val progress = pressState.progress.value
                if (progress > 0.001f) {
                    // The card brightens a touch...
                    drawRect(
                        Color.White.copy(alpha = 0.03f * progress),
                        blendMode = BlendMode.Plus
                    )
                    // ...and a soft disc sits under the finger, solid to half
                    // its radius with a long falloff — light pooling in the
                    // glass, not a dot painted on top.
                    val center = pressState.finger.value
                    val radius = size.minDimension * 1.5f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = haloPeak * progress),
                            0.5f to Color.White.copy(alpha = haloPeak * progress),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                }
            }
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The scale wraps the glass too, so the card grows as one piece.
            .graphicsLayer {
                val scale = 1f + 0.03f * pressState.progress.value
                scaleX = scale
                scaleY = scale
            }
            .then(surface)
            // Tracks the press without consuming it; the card stays
            // non-clickable, it just answers the touch like glass.
            .barPressSource(pressState)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = if (isLandscape) 8.dp else 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(
                modifier = Modifier.weight(1f).height(readingColumnHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(readingPairGap, Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.voltage_label), fontSize = labelFontSize, lineHeight = labelFontSize * readingLineHeightMultiplier, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatVoltage(voltage), fontSize = valueFontSize, lineHeight = valueFontSize * readingLineHeightMultiplier, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = voltageColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatVoltage(voltage), maxFontSize = valueFontSize, minFontSize = 8.sp, color = voltageColor, lineHeight = valueFontSize * readingLineHeightMultiplier)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(
                modifier = Modifier.weight(1f).height(readingColumnHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(readingPairGap, Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.current_label), fontSize = labelFontSize, lineHeight = labelFontSize * readingLineHeightMultiplier, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatCurrent(current), fontSize = valueFontSize, lineHeight = valueFontSize * readingLineHeightMultiplier, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = currentColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatCurrent(current), maxFontSize = valueFontSize, minFontSize = 8.sp, color = currentColor, lineHeight = valueFontSize * readingLineHeightMultiplier)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(
                modifier = Modifier.weight(1f).height(readingColumnHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(readingPairGap, Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.power_label), fontSize = labelFontSize, lineHeight = labelFontSize * readingLineHeightMultiplier, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatPower(voltage * current / 1_000_000f), fontSize = valueFontSize, lineHeight = valueFontSize * readingLineHeightMultiplier, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = powerColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatPower(voltage * current / 1_000_000f), maxFontSize = valueFontSize, minFontSize = 8.sp, color = powerColor, lineHeight = valueFontSize * readingLineHeightMultiplier)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(
                modifier = Modifier.weight(1f).height(readingColumnHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(readingPairGap, Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.avg_power_label), fontSize = labelFontSize, lineHeight = labelFontSize * readingLineHeightMultiplier, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatPower(avgPower), fontSize = valueFontSize, lineHeight = valueFontSize * readingLineHeightMultiplier, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = avgPowerColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatPower(avgPower), maxFontSize = valueFontSize, minFontSize = 8.sp, color = avgPowerColor, lineHeight = valueFontSize * readingLineHeightMultiplier)
                }
            }
        }
    }
}

@Composable
private fun AutoSizeText(
    text: String,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    fontFamily: FontFamily = FontFamily.Monospace,
    textAlign: TextAlign = TextAlign.Center,
    // Tight leading like the label/landscape texts; without it the value
    // inherits the theme's roomy bodyLarge line height, which pads the pair
    // asymmetrically and makes the column look off-centre.
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    // Direct TextMeasurer shrink: a linear estimate from one full-size layout
    // plus a step-down verify — at most 2-3 plain text layouts per text change.
    // The subcompose-based linear search this replaces re-ran up to 8
    // sub-compositions per value at 1 Hz forever.
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val measurer = rememberTextMeasurer()
        val style = TextStyle(
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            textAlign = textAlign
        )
        val fontSize = remember(text, maxWidthPx) {
            var size = maxFontSize
            val full = measurer.measure(
                text, style.copy(fontSize = size), maxLines = 1, constraints = Constraints()
            )
            if (full.size.width > maxWidthPx) {
                val estimated = (maxFontSize.value * maxWidthPx / full.size.width)
                    .coerceIn(minFontSize.value, maxFontSize.value)
                size = TextUnit(estimated, TextUnitType.Sp)
                while (size > minFontSize) {
                    val layout = measurer.measure(
                        text, style.copy(fontSize = size), maxLines = 1, constraints = Constraints()
                    )
                    if (layout.size.width <= maxWidthPx) break
                    size = TextUnit(size.value - 1f, TextUnitType.Sp)
                }
            }
            size
        }
        Text(
            text,
            modifier = Modifier,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            color = color,
            textAlign = textAlign,
            maxLines = 1
        )
    }
}

@Composable
fun WaveformChart(
    viewModel: WaveformViewModel,
    modifier: Modifier = Modifier
) {
    val data by viewModel.waveformData.collectAsState()
    val visibleTimeMs by viewModel.visibleTimeMs.collectAsState()
    val gridColor = AppColors.gridColor()
    val axisLabelColor = AppColors.axisLabelColor()
    val chartBg = AppColors.chartBg()
    val voltageColor = AppColors.voltageColor()
    val currentColor = AppColors.currentColor()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val isLandscape = isTabletLandscape()
    val numTimeTicks = if (isLandscape) 8 else 5

    // The window rides on the newest sample so the trace scrolls under a fixed
    // "now" at the right edge. A session that was just started has no full
    // window of history behind it though, and anchoring on its first samples
    // pushed the axis into negative time ("-7s ... 0s"). While the session is
    // younger than one window the axis therefore stays at [0, span] and the
    // trace grows in from the left edge; at the moment elapsed reaches the span
    // both rules give the same window, so the hand-over is seamless rather than
    // a jump.
    val sessionEnd = if (data.isNotEmpty()) data.last().timeMs else 0L
    val windowEnd = maxOf(sessionEnd, visibleTimeMs)
    val windowStart = windowEnd - visibleTimeMs

    val niceRange = remember(data, AppSettings.chartScaleMode) {
        if (AppSettings.chartScaleMode == AppSettings.ChartScaleMode.FULL_RANGE) {
            FullRangeVoltageAxis to FullRangeCurrentAxis
        } else if (data.isEmpty()) {
            NiceAxisRange(0f, 0.1f, 10) to NiceAxisRange(0f, 200f, 5)
        } else {
            val vDataMin = data.minOf { it.voltage }
            val vDataMax = data.maxOf { it.voltage }
            val iDataMin = data.minOf { it.currentUa }
            val iDataMax = data.maxOf { it.currentUa }
            computeNiceRange(vDataMin, vDataMax, 2.00f) to
                computeNiceRange(iDataMin, iDataMax, 2.00f)
        }
    }

    val (vRange, iRange) = niceRange
    val vSteps = vRange.steps
    val iSteps = iRange.steps

    // Sizes were tuned in raw px on a ~2.75x-density screen and are re-expressed
    // in dp so text and layout scale with display density.
    val density = LocalDensity.current
    val axisTextSizePx = with(density) {
        (if (isLandscape) 11f else if (screenWidth < 360) 8f else 9.5f).dp.toPx()
    }
    val timeTextSizePx = axisTextSizePx + with(density) { 1.dp.toPx() }

    // Reused across draws; recreated only when theme colors or sizing change.
    val gridPaint = remember(gridColor) {
        android.graphics.Paint().apply {
            color = gridColor.hashCode(); strokeWidth = 1f
            style = android.graphics.Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
    }
    val vTickPaint = remember(voltageColor, axisTextSizePx) {
        android.graphics.Paint().apply {
            color = voltageColor.hashCode(); textSize = axisTextSizePx
            isAntiAlias = true; isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val iTickPaint = remember(currentColor, axisTextSizePx) {
        android.graphics.Paint().apply {
            color = currentColor.hashCode(); textSize = axisTextSizePx
            isAntiAlias = true; isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    val timePaint = remember(axisLabelColor, timeTextSizePx) {
        android.graphics.Paint().apply {
            color = axisLabelColor.hashCode(); textSize = timeTextSizePx
            isAntiAlias = true; isFakeBoldText = true
            // Centred on its gridline: the old LEFT alignment compensated for
            // a two-character "0s" with a fixed 7dp nudge, which only happens
            // to be half a label. Once the axis reaches "2m05s" that nudge is
            // nowhere near half a label and both end ticks ran into the plot.
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = chartBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Sizes were tuned in raw px on a ~2.75x-density screen and are
                // re-expressed in dp so text and layout scale with display density.
                val leftAxisW = (if (isLandscape) 44f else if (screenWidth < 360) 29f else 36f).dp.toPx()
                val rightAxisW = (if (isLandscape) 44f else if (screenWidth < 360) 29f else 36f).dp.toPx()
                val topPad = (if (isLandscape) 13f else 9f).dp.toPx()
                val bottomPad = (if (isLandscape) 29f else if (screenWidth < 360) 20f else 23f).dp.toPx()
                val chartLeft = leftAxisW
                val chartRight = size.width - rightAxisW
                val chartTop = topPad
                val chartBottom = size.height - bottomPad
                val drawWidth = chartRight - chartLeft
                val drawHeight = chartBottom - chartTop

                fun formatAxisVoltage(value: Float): String {
                    val abs = kotlin.math.abs(value)
                    return when {
                        abs >= 10f -> String.format("%.1fV", value)
                        abs >= 1f -> String.format("%.2fV", value)
                        else -> String.format("%.2fV", value)
                    }
                }

                for (i in 0..vSteps) {
                    val y = chartTop + drawHeight * (1f - i.toFloat() / vSteps)
                    drawContext.canvas.nativeCanvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                    val vVal = vRange.tickValue(i)
                    drawContext.canvas.nativeCanvas.drawText(
                        formatAxisVoltage(vVal), chartLeft - 5.dp.toPx(), y + 4.dp.toPx(), vTickPaint
                    )
                }

                fun formatAxisCurrent(value: Float): String {
                    val abs = kotlin.math.abs(value)
                    return when {
                        abs >= 1_000_000f -> String.format("%.1f A", value / 1_000_000f)
                        abs >= 1_000f -> String.format("%.1f mA", value / 1_000f)
                        else -> String.format("%.0f \u03BCA", value)
                    }
                }

                for (i in 0..iSteps) {
                    val y = chartTop + drawHeight * (1f - i.toFloat() / iSteps)
                    val iVal = iRange.tickValue(i)
                    drawContext.canvas.nativeCanvas.drawText(
                        formatAxisCurrent(iVal), chartRight + 5.dp.toPx(), y + 4.dp.toPx(), iTickPaint
                    )
                }

                val numTimeTicksLocal = numTimeTicks

                for (i in 0..numTimeTicksLocal) {
                    val fraction = i.toFloat() / numTimeTicksLocal
                    val x = chartLeft + fraction * drawWidth
                    val timeMs = windowStart + (fraction * visibleTimeMs).toLong()
                    drawContext.canvas.nativeCanvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
                    val totalSec = timeMs / 1000
                    // Seconds while the tick is still in the first minute,
                    // minutes once it is past it ("59s", "1m00s"). The old test
                    // looked at the window *length* — always 5 s or 8 s — so a
                    // two-minute session kept labelling its axis "125s".
                    val timeLabel = if (totalSec < 60L) {
                        String.format("%ds", totalSec)
                    } else {
                        String.format("%dm%02ds", totalSec / 60, totalSec % 60)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        timeLabel, x, chartBottom + (bottomPad - 4.dp.toPx()), timePaint
                    )
                }

                if (data.isEmpty()) return@Canvas

                val voltagePath = Path()
                val currentPath = Path()

                var previousSegment: Long? = null
                data.forEach { (v, i, t, segmentId) ->
                    val xFraction = (t - windowStart).toFloat() / visibleTimeMs
                    val x = chartLeft + xFraction * drawWidth
                    val yV = chartTop + drawHeight * (1f - vRange.fractionOf(v))
                    val yI = chartTop + drawHeight * (1f - iRange.fractionOf(i))
                    val yVC = yV.coerceIn(chartTop, chartBottom)
                    val yIC = yI.coerceIn(chartTop, chartBottom)
                    if (segmentId != previousSegment) {
                        // A missing run of original packets is empty space,
                        // not a measured ramp between two sampled values.
                        voltagePath.moveTo(x, yVC)
                        currentPath.moveTo(x, yIC)
                    } else {
                        voltagePath.lineTo(x, yVC)
                        currentPath.lineTo(x, yIC)
                    }
                    previousSegment = segmentId
                }

                drawPath(voltagePath, voltageColor, style = Stroke(1.dp.toPx()))
                drawPath(currentPath, currentColor, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
