package com.irregular.xenopowermeter.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration 
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.irregular.xenopowermeter.AppSettings
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.ui.BottomBarInset
import com.irregular.xenopowermeter.ui.BottomBarTopEdge
import com.irregular.xenopowermeter.ui.BottomBarHeight
import com.irregular.xenopowermeter.ui.PageEndGutter
import com.irregular.xenopowermeter.ui.ProgressiveBottomBlur
import com.irregular.xenopowermeter.ui.ProgressiveTopBlur
import com.irregular.xenopowermeter.ui.RailPageStart
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: WaveformViewModel) {
    val cardTextColor = AppColors.buttonTextColor()
    val cardColor = AppColors.cardColor()
    val settingsButtonColor = AppColors.settingsButtonColor()
    val settingsButtonTextColor = AppColors.settingsButtonTextColor()
    val calibration by viewModel.calibration.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val recorder = viewModel.recorder
    val entryCount by recorder.entryCount.collectAsState()
    val duration by recorder.duration.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                AppSettings.updateNotificationEnabled(true)
                snackbarHostState.showSnackbar(context.getString(R.string.notification_enabled))
            } else {
                snackbarHostState.showSnackbar(context.getString(R.string.notification_enable_failed))
            }
        }
    }

    fun generateFileName(ext: String): String {
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = recorder.endMillis }
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = recorder.startMillis }
        val date = String.format("%04d%02d%02d", endCal.get(java.util.Calendar.YEAR), endCal.get(java.util.Calendar.MONTH) + 1, endCal.get(java.util.Calendar.DAY_OF_MONTH))
        val startTime = String.format("%02d%02d%02d", startCal.get(java.util.Calendar.HOUR_OF_DAY), startCal.get(java.util.Calendar.MINUTE), startCal.get(java.util.Calendar.SECOND))
        val endTime = String.format("%02d%02d%02d", endCal.get(java.util.Calendar.HOUR_OF_DAY), endCal.get(java.util.Calendar.MINUTE), endCal.get(java.util.Calendar.SECOND))
        return "XenoPowerMeter_Recording_${date}_${startTime}_${endTime}.$ext"
    }

    // Human-readable directory of a SAF document uri, e.g. "/Download";
    // empty if the provider's path can't be interpreted.
    // Human-readable directory of a SAF document uri, e.g. "/Download"; empty
    // if it can't be resolved. Providers differ: externalstorage hands out
    // real paths, media/downloads only a numeric document id (resolved via
    // MediaStore.DATA) or a raw: filesystem path.
    fun exportDirName(context: android.content.Context, uri: Uri): String {
        return try {
            val decoded = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
            if ("raw:" in decoded) {
                val path = decoded.substringAfter("raw:")
                return path.substringBeforeLast('/')
            } else when (uri.authority) {
                "com.android.externalstorage.documents" ->
                    "/" + decoded.substringAfterLast(':').substringBeforeLast('/')
                else -> {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val path = c.getString(0)
                            if (path.isNullOrBlank()) "" else path.substringBeforeLast('/')
                        } else ""
                    } ?: ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    val saveBinLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        recorder.exportToBin(os)
                    }
                    // Resolve the directory here (ContentResolver.query is
                    // binder IPC) — not on the main thread below.
                    val dirText = exportDirName(context, uri)
                        .ifEmpty { context.getString(R.string.export_dir_unknown) }
                    scope.launch(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.export_bin_success, entryCount, dirText)
                        )
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.export_fail)
                        )
                    }
                }
            }
        }
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        recorder.exportToCsv(os)
                    }
                    val dirText = exportDirName(context, uri)
                        .ifEmpty { context.getString(R.string.export_dir_unknown) }
                    scope.launch(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.export_csv_success, entryCount, dirText)
                        )
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.export_fail)
                        )
                    }
                }
            }
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp
    val settingsScrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            LandscapeSettingsLayout(
                scrollState = settingsScrollState,
                cardTextColor = cardTextColor,
                settingsButtonColor = settingsButtonColor,
                cardColor = cardColor,
                calibration = calibration,
                isConnected = isConnected,
                viewModel = viewModel,
                recorder = recorder,
                entryCount = entryCount,
                duration = duration,
                scope = scope,
                snackbarHostState = snackbarHostState,
                context = context,
                permissionLauncher = permissionLauncher,
                saveBinLauncher = saveBinLauncher,
                saveCsvLauncher = saveCsvLauncher,
                generateFileName = ::generateFileName
            )
        } else {
            PortraitSettingsLayout(
                scrollState = settingsScrollState,
                cardTextColor = cardTextColor,
                settingsButtonColor = settingsButtonColor,
                cardColor = cardColor,
                calibration = calibration,
                isConnected = isConnected,
                viewModel = viewModel,
                recorder = recorder,
                entryCount = entryCount,
                duration = duration,
                scope = scope,
                snackbarHostState = snackbarHostState,
                context = context,
                permissionLauncher = permissionLauncher,
                saveBinLauncher = saveBinLauncher,
                saveCsvLauncher = saveCsvLauncher,
                generateFileName = ::generateFileName
            )
        }

        // Keep the snackbar clear of the bar — above it in portrait, to the
        // right of the rail in landscape.
        val bottomBarZone = BottomBarInset
            .asPaddingValues().calculateBottomPadding() + 60.dp
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = if (isLandscape) {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RailPageStart, bottom = 16.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomBarZone + 16.dp)
            }
        )
    }
}

@Composable
private fun ScrollableSettingsPage(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Landscape hangs the bar off the start edge, so the space the page has to
    // clear moves from the bottom to the side, and there is nothing left to
    // dissolve at the bottom.
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    // The three pages share one start edge (rail + gutter in landscape, the
    // phone's 16dp otherwise). Keep reading it from BottomInset instead of
    // re-deriving it here: the old "16 + rail + 8" made Settings start 16dp
    // further in than Main did.
    val contentStart = if (isLandscape) RailPageStart else 16.dp

    // Last row rests 8dp clear of the pill so it never ends up hidden behind it
    // (and a plain 16dp from the bottom edge when the pill is on the side).
    val bottomRestSpace = if (isLandscape) 16.dp else BottomBarTopEdge + 8.dp

    // The status bar gap lives *inside* the scrolling content rather than
    // around the viewport: at rest the first card still starts below the clock,
    // but as soon as the page scrolls the rows slide up under the status bar
    // and into the blur band. With the old statusBarsPadding outside, the top
    // of the band fell on empty background and its strongest part dissolved
    // nothing.
    val statusBarTop = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    val contentTopSpace = statusBarTop + 8.dp

    // Snapshot of just the scrolling column, for the blur band below to read.
    // It has to sit outside the scroll so it covers the viewport instead of the
    // whole (much taller) content.
    val pageBackdrop = rememberLayerBackdrop()

    Box(modifier = modifier.fillMaxSize()) {
        // The bounce is the app-wide translation-only overscroll (see
        // BouncyOverscroll): it moves the viewport without re-recording the
        // content into a stretched RenderNode, so the backdrop this page hands
        // to the glass stays valid from frame to frame.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
                .verticalScroll(scrollState)
                .padding(start = contentStart, end = PageEndGutter)
        ) {
            Spacer(Modifier.height(contentTopSpace))
            content()
            Spacer(Modifier.height(bottomRestSpace))
        }

        if (!isLandscape) {
            // Flush with the pill's top edge, so content is still sharp when it
            // gets there and only dissolves once it is behind the glass.
            // +10.dp extends the band past the pill's top edge so the dissolve
            // starts a bit higher (72dp pill-equivalent instead of the real 60dp).
            ProgressiveBottomBlur(
                backdrop = pageBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
                bandHeight = BottomBarTopEdge + 10.dp,
                maxBlur = 20.dp
            )
        }

        // Top mirror of the bottom band, same parameters: content dissolves
        // as it scrolls up under the page's top edge.
        //
        // Unlike the bottom band there is nothing sitting on top here, so at
        // rest it would just eat the first row ("语言") while it is still the
        // page's foremost control. Fade it in over the first 24dp of scroll
        // instead: sharp at the top of the list, dissolving only once content
        // actually slides beneath the edge. Read inside graphicsLayer so
        // scrolling only invalidates the draw phase.
        val fadeDistancePx = with(LocalDensity.current) { 24.dp.toPx() }
        ProgressiveTopBlur(
            backdrop = pageBackdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = (scrollState.value / fadeDistancePx).coerceIn(0f, 1f)
                },
            bandHeight = contentTopSpace + BottomBarHeight,
            maxBlur = 20.dp
        )
    }
}

@Composable
private fun PortraitSettingsLayout(
    scrollState: ScrollState,
    cardTextColor: androidx.compose.ui.graphics.Color,
    settingsButtonColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    calibration: com.irregular.xenopowermeter.data.model.Calibration,
    isConnected: Boolean,
    viewModel: WaveformViewModel,
    recorder: com.irregular.xenopowermeter.recording.Recorder,
    entryCount: Int,
    duration: Double,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveBinLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    generateFileName: (String) -> String
) {
    ScrollableSettingsPage(
        scrollState = scrollState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val valueColor = AppColors.linkColor()

            DisplaySectionTitle(stringResource(R.string.display))
            DisplayCard(cardTextColor, valueColor, viewModel, context) { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }

            DisplaySectionTitle(stringResource(R.string.data_record))
            DataRecordCard(cardTextColor, valueColor, settingsButtonColor, viewModel, recorder, entryCount, duration, scope, snackbarHostState, context, permissionLauncher, saveBinLauncher, saveCsvLauncher, generateFileName)

            DisplaySectionTitle(stringResource(R.string.calibration))
            CalibrationCard(settingsButtonColor, calibration, isConnected, viewModel)
        }
    }
}

@Composable
private fun LandscapeSettingsLayout(
    scrollState: ScrollState,
    cardTextColor: androidx.compose.ui.graphics.Color,
    settingsButtonColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    calibration: com.irregular.xenopowermeter.data.model.Calibration,
    isConnected: Boolean,
    viewModel: WaveformViewModel,
    recorder: com.irregular.xenopowermeter.recording.Recorder,
    entryCount: Int,
    duration: Double,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveBinLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    generateFileName: (String) -> String
) {
    val valueColor = AppColors.linkColor()

    ScrollableSettingsPage(
        scrollState = scrollState,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DisplaySectionTitle(stringResource(R.string.display))
                DisplayCard(cardTextColor, valueColor, viewModel, context) { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }

                DisplaySectionTitle(stringResource(R.string.data_record))
                DataRecordCard(cardTextColor, valueColor, settingsButtonColor, viewModel, recorder, entryCount, duration, scope, snackbarHostState, context, permissionLauncher, saveBinLauncher, saveCsvLauncher, generateFileName)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DisplaySectionTitle(stringResource(R.string.calibration))
                CalibrationCard(settingsButtonColor, calibration, isConnected, viewModel)
            }
        }
    }
}

@Composable
private fun DisplaySectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun DisplayCard(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    viewModel: WaveformViewModel,
    context: android.content.Context,
    onSnackbar: (String) -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val lang = AppSettings.language
    val cardColor = AppColors.cardColor()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            LanguageSetting(labelColor, valueColor) { msg -> onSnackbar(msg) }
            ColorModeSetting(labelColor, valueColor) {
                onSnackbar(context.getString(R.string.color_mode_changed))
            }
            ChartScaleModeSetting(labelColor, valueColor)
            RangeSetting(labelColor, valueColor) { range -> viewModel.setRange(range) }
            AutoConnectSetting(labelColor, valueColor)
        }
    }
}

@Composable
private fun DataRecordCard(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    settingsButtonColor: androidx.compose.ui.graphics.Color,
    viewModel: WaveformViewModel,
    recorder: com.irregular.xenopowermeter.recording.Recorder,
    entryCount: Int,
    duration: Double,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveBinLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    saveCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    generateFileName: (String) -> String
) {
    val cardColor = AppColors.cardColor()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp)) {
            NotificationSetting(labelColor, valueColor, permissionLauncher) { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
            RecordingDurationLimitSetting(labelColor, valueColor)

            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.record_count_label), fontSize = 15.sp, color = labelColor)
                Text(String.format(stringResource(R.string.recording_value), entryCount), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.record_duration_label), fontSize = 15.sp, color = labelColor)
                Text(String.format(stringResource(R.string.duration_value), duration), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
            }

            Spacer(modifier = Modifier.height(8.dp))

            var showExportMenu by remember { mutableStateOf(false) }
            var showClearDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = {
                            if (recorder.isRecording.value) {
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.export_need_pause)) }
                            } else {
                                showExportMenu = true
                            }
                        },
                        enabled = entryCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = settingsButtonColor,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            disabledContainerColor = settingsButtonColor.copy(alpha = 0.38f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.export_recorded_data))
                    }
                    val exportCtx = AppSettings.localizedContext(context)
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(exportCtx.getString(R.string.export_option_bin)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showExportMenu = false
                                    saveBinLauncher.launch(generateFileName("bin"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(exportCtx.getString(R.string.export_option_csv)) },
                                leadingIcon = {
                                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showExportMenu = false
                                    saveCsvLauncher.launch(generateFileName("csv"))
                                }
                            )
                        }
                    }
                }
                Box {
                    Button(
                        onClick = { showClearDialog = true },
                        enabled = entryCount > 0 && !recorder.isRecording.value,
                        modifier = Modifier.fillMaxHeight(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = settingsButtonColor,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            disabledContainerColor = settingsButtonColor.copy(alpha = 0.38f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            if (showClearDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    title = { Text(stringResource(R.string.clear_confirm_title)) },
                    text = { Text(stringResource(R.string.clear_recorded_data_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearDialog = false
                            recorder.clearRecording()
                        }) { Text(stringResource(R.string.confirm_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    settingsButtonColor: androidx.compose.ui.graphics.Color,
    calibration: com.irregular.xenopowermeter.data.model.Calibration,
    isConnected: Boolean,
    viewModel: WaveformViewModel
) {
    val cardColor = AppColors.cardColor()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp)) {
            CalibrationRow(stringResource(R.string.calibration_low_scale), calibration.lowScaleMultiplier)
            CalibrationRow(stringResource(R.string.calibration_low_offset), calibration.lowOffsetUa, "\u03BCA")
            CalibrationRow(stringResource(R.string.calibration_mid_scale), calibration.midScaleMultiplier)
            CalibrationRow(stringResource(R.string.calibration_mid_offset), calibration.midOffsetUa, "\u03BCA")
            CalibrationRow(stringResource(R.string.calibration_high_scale), calibration.highScaleMultiplier)
            CalibrationRow(stringResource(R.string.calibration_high_offset), calibration.highOffsetUa, "\u03BCA")

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.loadCalibration() },
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = settingsButtonColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = settingsButtonColor.copy(alpha = 0.38f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.read))
            }
        }
    }
}

/**
 * A label / value row whose value opens a dropdown.
 *
 * The menu is anchored to the 130dp value slot instead of to the whole row. A
 * row-wide anchor plus the old -80dp nudge parked the popup against the
 * window's leading edge — which in landscape is exactly where the navigation
 * rail floats, so opening "Language" dropped the list on top of the glass.
 * Anchored to the value, the menu opens under the chevron, inside the card.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable { expanded = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = labelColor)
        Box(
            modifier = Modifier.width(130.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(value, fontSize = 15.sp, color = valueColor)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = valueColor, modifier = Modifier.size(18.dp))
            }

            // Inside the value Box on purpose: the popup positions itself
            // against its parent layout node, so this is what keeps it on the
            // page rather than over the rail.
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                menu { expanded = false }
            }
        }
    }
}

@Composable
private fun LanguageSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val langCtx = AppSettings.localizedContext(context)
    val currentLanguage = AppSettings.language

    val languageText = when (currentLanguage) {
        AppSettings.Language.SYSTEM -> stringResource(R.string.system_default)
        AppSettings.Language.CHINESE -> stringResource(R.string.chinese)
        AppSettings.Language.ENGLISH -> stringResource(R.string.english)
        AppSettings.Language.JAPANESE -> stringResource(R.string.japanese)
    }
    val sysText = langCtx.getString(R.string.system_default)
    val zhText = langCtx.getString(R.string.chinese)
    val enText = langCtx.getString(R.string.english)
    val jaText = langCtx.getString(R.string.japanese)

    SettingRow(
        label = stringResource(R.string.language),
        value = languageText,
        labelColor = labelColor,
        valueColor = valueColor
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text(sysText) },
            onClick = {
                AppSettings.updateLanguage(AppSettings.Language.SYSTEM)
                dismiss()
                onChanged(langCtx.getString(R.string.language_changed_to_system))
            }
        )
        DropdownMenuItem(
            text = { Text(zhText) },
            onClick = {
                AppSettings.updateLanguage(AppSettings.Language.CHINESE)
                dismiss()
                onChanged(langCtx.getString(R.string.language_changed_to_chinese))
            }
        )
        DropdownMenuItem(
            text = { Text(enText) },
            onClick = {
                AppSettings.updateLanguage(AppSettings.Language.ENGLISH)
                dismiss()
                onChanged(langCtx.getString(R.string.language_changed_to_english))
            }
        )
        DropdownMenuItem(
            text = { Text(jaText) },
            onClick = {
                AppSettings.updateLanguage(AppSettings.Language.JAPANESE)
                dismiss()
                onChanged(langCtx.getString(R.string.language_changed_to_japanese))
            }
        )
    }
}

@Composable
private fun ColorModeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onChanged: () -> Unit
) {
    val currentMode = AppSettings.colorMode
    val localizedCtx = AppSettings.localizedContext(LocalContext.current)

    val modeText = when (currentMode) {
        AppSettings.ColorMode.SYSTEM -> stringResource(R.string.system_default)
        AppSettings.ColorMode.LIGHT -> stringResource(R.string.always_light)
        AppSettings.ColorMode.DARK -> stringResource(R.string.always_dark)
    }
    val systemText = localizedCtx.getString(R.string.system_default)
    val lightText = localizedCtx.getString(R.string.always_light)
    val darkText = localizedCtx.getString(R.string.always_dark)

    SettingRow(
        label = stringResource(R.string.color_mode),
        value = modeText,
        labelColor = labelColor,
        valueColor = valueColor
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text(systemText) },
            onClick = {
                AppSettings.updateColorMode(AppSettings.ColorMode.SYSTEM)
                dismiss()
                onChanged()
            }
        )
        DropdownMenuItem(
            text = { Text(lightText) },
            onClick = {
                AppSettings.updateColorMode(AppSettings.ColorMode.LIGHT)
                dismiss()
                onChanged()
            }
        )
        DropdownMenuItem(
            text = { Text(darkText) },
            onClick = {
                AppSettings.updateColorMode(AppSettings.ColorMode.DARK)
                dismiss()
                onChanged()
            }
        )
    }
}

@Composable
private fun ChartScaleModeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val currentMode = AppSettings.chartScaleMode
    val localizedCtx = AppSettings.localizedContext(LocalContext.current)

    val modeText = when (currentMode) {
        AppSettings.ChartScaleMode.AUTO -> stringResource(R.string.chart_scale_auto)
        AppSettings.ChartScaleMode.FULL_RANGE -> stringResource(R.string.chart_scale_full_range)
    }
    val autoText = localizedCtx.getString(R.string.chart_scale_auto)
    val fullRangeText = localizedCtx.getString(R.string.chart_scale_full_range)

    SettingRow(
        label = stringResource(R.string.chart_scale_mode),
        value = modeText,
        labelColor = labelColor,
        valueColor = valueColor
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text(autoText) },
            onClick = {
                AppSettings.updateChartScaleMode(AppSettings.ChartScaleMode.AUTO)
                dismiss()
            }
        )
        DropdownMenuItem(
            text = { Text(fullRangeText) },
            onClick = {
                AppSettings.updateChartScaleMode(AppSettings.ChartScaleMode.FULL_RANGE)
                dismiss()
            }
        )
    }
}

@Composable
private fun NotificationSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val enabled = AppSettings.notificationEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.notification_push), fontSize = 15.sp, color = labelColor)
        Box(
            modifier = Modifier.width(130.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            val thumbOffset by animateDpAsState(if (enabled) 39.dp else 3.dp, label = "thumb")
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) valueColor else labelColor.copy(alpha = 0.3f))
                    .clickable {
                        if (!enabled) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                AppSettings.updateNotificationEnabled(true)
                                onMessage(context.getString(R.string.notification_enabled))
                            } else {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            AppSettings.updateNotificationEnabled(false)
                            onMessage(context.getString(R.string.notification_disabled))
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = thumbOffset)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White)
                )
            }
        }
    }
}

@Composable
private fun AutoConnectSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val enabled = AppSettings.autoConnect

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.auto_connect), fontSize = 15.sp, color = labelColor)
        Box(
            modifier = Modifier.width(130.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            val thumbOffset by animateDpAsState(if (enabled) 39.dp else 3.dp, label = "autoConnectThumb")
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) valueColor else labelColor.copy(alpha = 0.3f))
                    .clickable { AppSettings.updateAutoConnect(!enabled) },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = thumbOffset)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White)
                )
            }
        }
    }
}

@Composable
private fun RecordingDurationLimitSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val currentLimit = AppSettings.recordingDurationLimitMin
    val limitCtx = AppSettings.localizedContext(LocalContext.current)

    val limitText = String.format(limitCtx.getString(R.string.recording_duration_value), currentLimit)

    SettingRow(
        label = stringResource(R.string.recording_duration_limit),
        value = limitText,
        labelColor = labelColor,
        valueColor = valueColor
    ) { dismiss ->
        AppSettings.RECORDING_DURATION_OPTIONS.forEach { minutes ->
            val label = String.format(limitCtx.getString(R.string.recording_duration_value), minutes)
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    AppSettings.updateRecordingDurationLimit(minutes)
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun RangeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onRangeChange: (RangeMode) -> Unit
) {
    val currentRange = AppSettings.range
    val rangeCtx = AppSettings.localizedContext(LocalContext.current)

    val rangeText = when (currentRange) {
        RangeMode.AUTO -> stringResource(R.string.range_auto)
        RangeMode.LOW -> stringResource(R.string.range_low)
        RangeMode.MID -> stringResource(R.string.range_mid)
        RangeMode.HIGH -> stringResource(R.string.range_high)
    }
    val autoText = rangeCtx.getString(R.string.range_auto)
    val lowText = rangeCtx.getString(R.string.range_low)
    val midText = rangeCtx.getString(R.string.range_mid)
    val highText = rangeCtx.getString(R.string.range_high)

    SettingRow(
        label = stringResource(R.string.range),
        value = rangeText,
        labelColor = labelColor,
        valueColor = valueColor
    ) { dismiss ->
        RangeMode.entries.forEach { mode ->
            val label = when (mode) {
                RangeMode.AUTO -> autoText
                RangeMode.LOW -> lowText
                RangeMode.MID -> midText
                RangeMode.HIGH -> highText
            }
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    AppSettings.updateRange(mode)
                    onRangeChange(mode)
                    dismiss()
                }
            )
        }
    }
}

@Composable
fun CalibrationRow(label: String, value: Float, unit: String = "") {
    val labelColor = AppColors.buttonTextColor()
    val valueColor = AppColors.linkColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = labelColor)
        Text(
            "${String.format("%.6f", value)} $unit",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
