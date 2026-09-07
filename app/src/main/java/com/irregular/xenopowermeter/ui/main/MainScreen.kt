package com.irregular.xenopowermeter.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.ui.theme.AvgPowerColor
import com.irregular.xenopowermeter.ui.theme.CurrentColor
import com.irregular.xenopowermeter.ui.theme.PowerColor
import com.irregular.xenopowermeter.ui.theme.VoltageColor
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlin.math.pow

@Composable
fun MainScreen(viewModel: WaveformViewModel) {
    val voltage by viewModel.currentVoltage.collectAsState()
    val current by viewModel.currentCurrent.collectAsState()
    val avgPower by viewModel.averagePower.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isRecording by viewModel.recorder.isRecording.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val visibleTimeMs by viewModel.visibleTimeMs.collectAsState()
    val range by viewModel.currentRange.collectAsState()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isLandscape) {
        viewModel.setOrientation(isLandscape)
    }

    LaunchedEffect(Unit) {
        viewModel.connectionEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            ValuePanel(voltage, current, avgPower)
            Spacer(Modifier.height(4.dp))
            ControlBar(
                isConnected = isConnected,
                isRecording = isRecording, isPaused = isPaused, range = range,
                isLandscape = isLandscape,
                onConnect = { viewModel.connect() },
                onDisconnect = { viewModel.disconnect() },
                onToggleRecording = { viewModel.toggleRecording() },
                onTogglePause = { viewModel.togglePause() },
                onClear = { viewModel.clearWaveform() },
                onRangeChange = { viewModel.setRange(it) }
            )
            Spacer(Modifier.height(4.dp))
            WaveformChart(
                data = waveformData,
                visibleTimeMs = visibleTimeMs,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        )
    }
}

@Composable
fun ControlBar(
    isConnected: Boolean,
    isRecording: Boolean,
    isPaused: Boolean,
    range: RangeMode,
    isLandscape: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onRangeChange: (RangeMode) -> Unit
) {
    var rangeMenuExpanded by remember { mutableStateOf(false) }
    val barColor = AppColors.barColor()
    val buttonTextColor = AppColors.buttonTextColor()
    val connectButtonColor = AppColors.connectButtonColor()

    @Composable
    fun ConnectButton() {
        FilledTonalButton(
            onClick = { if (isConnected) onDisconnect() else onConnect() },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = connectButtonColor)
        ) {
            val icon = if (isConnected) Icons.Default.LinkOff else Icons.Default.Usb
            Icon(icon, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(if (isConnected) "Disconnect" else "Connect", fontSize = 13.sp, color = buttonTextColor)
        }
    }

    @Composable
    fun RecordButton() {
        FilledTonalButton(
            onClick = onToggleRecording, enabled = isConnected,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(if (isRecording) "Stop" else "Record", fontSize = 13.sp, color = buttonTextColor)
        }
    }

    @Composable
    fun PauseButton() {
        FilledTonalButton(
            onClick = onTogglePause, enabled = isConnected,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(if (isPaused) "Resume" else "Pause", fontSize = 13.sp, color = buttonTextColor)
        }
    }

    @Composable
    fun ClearButton() {
        FilledTonalButton(
            onClick = onClear, enabled = !isRecording,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(Icons.Default.Clear, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text("Clear", fontSize = 13.sp, color = buttonTextColor)
        }
    }

    @Composable
    fun RangeButton() {
        Box {
            FilledTonalButton(
                onClick = { rangeMenuExpanded = true }, enabled = !isRecording,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
            ) {
                Text("Range", fontSize = 13.sp, color = buttonTextColor)
            }
            DropdownMenu(expanded = rangeMenuExpanded, onDismissRequest = { rangeMenuExpanded = false }) {
                RangeMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(mode.name) }, onClick = { onRangeChange(mode); rangeMenuExpanded = false })
                }
            }
        }
    }

    if (isLandscape) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ConnectButton(); RecordButton(); PauseButton(); ClearButton(); RangeButton()
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConnectButton(); RecordButton()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PauseButton(); ClearButton(); RangeButton()
            }
        }
    }
}

@Composable
fun ValuePanel(voltage: Float, current: Float, avgPower: Float) {
    val cardColor = AppColors.cardColor()
    val labelColor = AppColors.labelColor()
    val dividerColor = AppColors.dividerColor()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp
    val valueFontSize = when {
        isLandscape -> 20.sp
        screenWidthDp < 360 -> 13.sp
        else -> 15.sp
    }
    val labelFontSize = when {
        isLandscape -> 12.sp
        screenWidthDp < 360 -> 8.sp
        else -> 9.sp
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOLTAGE", fontSize = labelFontSize, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatVoltage(voltage), fontSize = valueFontSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = VoltageColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatVoltage(voltage), maxFontSize = valueFontSize, minFontSize = 8.sp, color = VoltageColor)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CURRENT", fontSize = labelFontSize, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatCurrent(current), fontSize = valueFontSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CurrentColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatCurrent(current), maxFontSize = valueFontSize, minFontSize = 8.sp, color = CurrentColor)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POWER", fontSize = labelFontSize, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatPower(voltage * current / 1_000_000f), fontSize = valueFontSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = PowerColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatPower(voltage * current / 1_000_000f), maxFontSize = valueFontSize, minFontSize = 8.sp, color = PowerColor)
                }
            }
            Box(Modifier.width(1.dp).height(32.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AVG P", fontSize = labelFontSize, color = labelColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (isLandscape) {
                    Text(DataConverter.formatPower(avgPower), fontSize = valueFontSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AvgPowerColor, textAlign = TextAlign.Center)
                } else {
                    AutoSizeText(DataConverter.formatPower(avgPower), maxFontSize = valueFontSize, minFontSize = 8.sp, color = AvgPowerColor)
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
    fontWeight: FontWeight = FontWeight.Bold,
    fontFamily: FontFamily = FontFamily.Monospace
) {
    SubcomposeLayout { constraints ->
        var currentSize = maxFontSize
        val unconstrained = constraints.copy(maxWidth = Int.MAX_VALUE)

        while (currentSize > minFontSize) {
            val testStyle = TextStyle(
                fontSize = currentSize,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                color = color,
                textAlign = TextAlign.Center
            )
            val measured = subcompose("measure_$currentSize") {
                Text(text = text, style = testStyle, maxLines = 1)
            }.first().measure(unconstrained)
            if (measured.width <= constraints.maxWidth) break
            currentSize = TextUnit(currentSize.value - 1f, currentSize.type)
        }

        val finalStyle = TextStyle(
            fontSize = currentSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            color = color,
            textAlign = TextAlign.Center
        )
        val placeable = subcompose("content") {
            Text(text = text, style = finalStyle, maxLines = 1)
        }.first().measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

@Composable
fun WaveformChart(
    data: List<Triple<Float, Float, Long>>,
    visibleTimeMs: Long,
    modifier: Modifier = Modifier
) {
    val gridColor = AppColors.gridColor()
    val axisLabelColor = AppColors.axisLabelColor()
    val chartBg = AppColors.chartBg()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val isLandscape = screenWidth > screenHeight
    val axisTextSize = if (isLandscape) 30f else if (screenWidth < 360) 22f else 26f
    val numTimeTicks = if (isLandscape) 8 else 5

    val windowEnd = if (data.isNotEmpty()) data.last().third else 0L
    val windowStart = windowEnd - visibleTimeMs

    val niceRange = remember(data) {
        if (data.isEmpty()) {
            NiceAxisRange(0f, 1f, 0.1f, 10) to NiceAxisRange(0f, 1000f, 200f, 5)
        } else {
            val vDataMin = data.minOf { it.first }
            val vDataMax = data.maxOf { it.first }
            val iDataMin = data.minOf { it.second }
            val iDataMax = data.maxOf { it.second }
            computeNiceRange(vDataMin, vDataMax, 2.00f) to computeNiceRange(iDataMin, iDataMax, 2.00f)
        }
    }

    val (vRange, iRange) = niceRange
    val vMin = vRange.min; val vMax = vRange.max; val vStep = vRange.step; val vSteps = vRange.steps
    val iMin = iRange.min; val iMax = iRange.max; val iStep = iRange.step; val iSteps = iRange.steps

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
                val leftAxisW = if (isLandscape) 120f else if (screenWidth < 360) 80f else 100f
                val rightAxisW = if (isLandscape) 120f else if (screenWidth < 360) 80f else 100f
                val topPad = if (isLandscape) 36f else 24f
                val bottomPad = if (isLandscape) 80f else if (screenWidth < 360) 56f else 64f
                val chartLeft = leftAxisW
                val chartRight = size.width - rightAxisW
                val chartTop = topPad
                val chartBottom = size.height - bottomPad
                val drawWidth = chartRight - chartLeft
                val drawHeight = chartBottom - chartTop

                val gridPaint = android.graphics.Paint().apply {
                    color = gridColor.hashCode(); strokeWidth = 1f
                    style = android.graphics.Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
                }

                val vTickPaint = android.graphics.Paint().apply {
                    color = VoltageColor.hashCode(); textSize = axisTextSize
                    isAntiAlias = true; isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.RIGHT
                }

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
                    val vVal = vMin + vStep * i
                    drawContext.canvas.nativeCanvas.drawText(
                        formatAxisVoltage(vVal), chartLeft - 15f, y + 10f, vTickPaint
                    )
                }

                val iTickPaint = android.graphics.Paint().apply {
                    color = CurrentColor.hashCode(); textSize = axisTextSize
                    isAntiAlias = true; isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.LEFT
                }

                fun formatAxisCurrent(value: Float): String {
                    val abs = kotlin.math.abs(value)
                    return when {
                        abs >= 1_000_000f -> String.format("%.1f A", value / 1_000_000f)
                        abs >= 1_000f -> String.format("%.2f A", value / 1_000_000f)
                        else -> String.format("%.0f \u03BCA", value)
                    }
                }

                for (i in 0..iSteps) {
                    val y = chartTop + drawHeight * (1f - i.toFloat() / iSteps)
                    val iVal = iMin + iStep * i
                    drawContext.canvas.nativeCanvas.drawText(
                        formatAxisCurrent(iVal), chartRight + 15f, y + 10f, iTickPaint
                    )
                }

                if (data.isEmpty()) return@Canvas

                val numTimeTicksLocal = numTimeTicks

                val timePaint = android.graphics.Paint().apply {
                    color = axisLabelColor.hashCode(); textSize = axisTextSize + 2f
                    isAntiAlias = true; isFakeBoldText = true
                }

                for (i in 0..numTimeTicksLocal) {
                    val fraction = i.toFloat() / numTimeTicksLocal
                    val x = chartLeft + fraction * drawWidth
                    val timeMs = windowStart + (fraction * visibleTimeMs).toLong()
                    drawContext.canvas.nativeCanvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
                    val totalSec = timeMs / 1000
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%d:%02ds", min, sec), x - 20f, chartBottom + (bottomPad - 10f), timePaint
                    )
                }

                val voltagePath = Path()
                val currentPath = Path()

                data.forEachIndexed { index, (v, i, t) ->
                    val xFraction = (t - windowStart).toFloat() / visibleTimeMs
                    val x = chartLeft + xFraction * drawWidth
                    val yV = chartTop + drawHeight * (1f - (v - vMin) / (vMax - vMin))
                    val yI = chartTop + drawHeight * (1f - (i - iMin) / (iMax - iMin))
                    val yVC = yV.coerceIn(chartTop, chartBottom)
                    val yIC = yI.coerceIn(chartTop, chartBottom)
                    if (index == 0) { voltagePath.moveTo(x, yVC); currentPath.moveTo(x, yIC) }
                    else { voltagePath.lineTo(x, yVC); currentPath.lineTo(x, yIC) }
                }

                drawPath(voltagePath, VoltageColor, style = Stroke(3f))
                drawPath(currentPath, CurrentColor, style = Stroke(3f))
            }
        }
    }
}

data class NiceAxisRange(val min: Float, val max: Float, val step: Float, val steps: Int)

private fun computeNiceRange(dataMin: Float, dataMax: Float, minMarginFraction: Float): NiceAxisRange {
    val span = (dataMax - dataMin).coerceAtLeast(0.0001f)
    val margin = span * minMarginFraction
    val rawMin = dataMin - margin
    val rawMax = dataMax + margin
    val niceSpan = rawMax - rawMin
    
    val exponent = kotlin.math.floor(kotlin.math.log10(niceSpan.toDouble())).toInt()
    val fraction = niceSpan / 10.0.pow(exponent.toDouble()).toFloat()
    
    val niceFraction = when {
        fraction <= 1.5f -> 1.0f
        fraction <= 3.0f -> 2.0f
        fraction <= 7.0f -> 5.0f
        else -> 10.0f
    }
    
    val step = (niceFraction * 10.0.pow(exponent.toDouble())).toFloat() / 5f
    val niceMin = (kotlin.math.floor(rawMin / step) * step).toFloat()
    val niceMax = (kotlin.math.ceil(rawMax / step) * step).toFloat()
    val steps = ((niceMax - niceMin) / step).toInt()
    
    return NiceAxisRange(niceMin, niceMax, step, steps)
}
