package com.irregular.xenopowermeter.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
        ValuePanel(voltage, current, avgPower)
        Spacer(Modifier.height(4.dp))
        ControlBar(
            isConnected = isConnected,
            isRecording = isRecording, isPaused = isPaused, range = range,
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
}

@Composable
fun ControlBar(
    isConnected: Boolean,
    isRecording: Boolean,
    isPaused: Boolean,
    range: RangeMode,
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { if (isConnected) onDisconnect() else onConnect() },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = connectButtonColor)
        ) {
            val icon = if (isConnected) Icons.Default.LinkOff else Icons.Default.Usb
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (isConnected) "Disconnect" else "Connect", color = buttonTextColor)
        }

        FilledTonalButton(
            onClick = onToggleRecording, enabled = isConnected,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (isRecording) "Stop" else "Record", color = buttonTextColor)
        }
        FilledTonalButton(
            onClick = onTogglePause, enabled = isConnected,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (isPaused) "Resume" else "Pause", color = buttonTextColor)
        }
        FilledTonalButton(
            onClick = onClear, enabled = !isRecording,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
        ) {
            Icon(Icons.Default.Clear, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Clear", color = buttonTextColor)
        }
        Box {
            FilledTonalButton(
                onClick = { rangeMenuExpanded = true }, enabled = !isRecording,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = barColor)
            ) {
                Text("Range: ${range.name}", color = buttonTextColor)
            }
            DropdownMenu(expanded = rangeMenuExpanded, onDismissRequest = { rangeMenuExpanded = false }) {
                RangeMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(mode.name) }, onClick = { onRangeChange(mode); rangeMenuExpanded = false })
                }
            }
        }
    }
}

@Composable
fun ValuePanel(voltage: Float, current: Float, avgPower: Float) {
    val cardColor = AppColors.cardColor()
    val labelColor = AppColors.labelColor()
    val dividerColor = AppColors.dividerColor()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOLTAGE", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text(DataConverter.formatVoltage(voltage), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = VoltageColor)
            }
            Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CURRENT", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text(DataConverter.formatCurrent(current), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CurrentColor)
            }
            Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POWER", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text(DataConverter.formatPower(voltage * current / 1_000_000f), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = PowerColor)
            }
            Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(dividerColor))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AVG POWER", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text(DataConverter.formatPower(avgPower), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AvgPowerColor)
            }
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
                val leftAxisW = 120f
                val rightAxisW = 120f
                val topPad = 36f
                val bottomPad = 80f
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
                    color = VoltageColor.hashCode(); textSize = 30f
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
                    color = CurrentColor.hashCode(); textSize = 30f
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

                val numTimeTicks = 8

                val timePaint = android.graphics.Paint().apply {
                    color = axisLabelColor.hashCode(); textSize = 28f
                    isAntiAlias = true; isFakeBoldText = true
                }

                for (i in 0..numTimeTicks) {
                    val fraction = i.toFloat() / numTimeTicks
                    val x = chartLeft + fraction * drawWidth
                    val timeMs = windowStart + (fraction * visibleTimeMs).toLong()
                    drawContext.canvas.nativeCanvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
                    val totalSec = timeMs / 1000
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%d:%02ds", min, sec), x - 20f, chartBottom + 54f, timePaint
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
