package com.irregular.xenopowermeter.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration 
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.irregular.xenopowermeter.AppSettings
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The bottom fade covers the whole floating bar and spills 0.5 × a above it,
 * where a = distance from the bar's vertical center to the screen bottom
 * (nav inset + 4dp gap + half of the 56dp bar) — so the wash visibly climbs
 * over the content above the bar.
 */
private val BottomFadeSpillFactor = 0.5f
private val BottomFadeFixedDistance = 32.dp

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
                    scope.launch(Dispatchers.Main) {
                        val dirText = exportDirName(context, uri)
                            .ifEmpty { context.getString(R.string.export_dir_unknown) }
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
                    scope.launch(Dispatchers.Main) {
                        val dirText = exportDirName(context, uri)
                            .ifEmpty { context.getString(R.string.export_dir_unknown) }
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

        // Bottom fade: full-bleed with the exact same geometry and stops as
        // the About page's, so both pages read identically. Fades out with a
        // short transition once the list rests at the very bottom (nothing
        // left to wash there), back in as soon as it scrolls away from it.
        val backgroundColor = MaterialTheme.colorScheme.background
        val navBottomInset = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val bottomFadeHeight = navBottomInset + 60.dp +
            (navBottomInset + BottomFadeFixedDistance) * BottomFadeSpillFactor
        val bottomObscured by remember {
            derivedStateOf { settingsScrollState.value < settingsScrollState.maxValue }
        }
        val bottomFadeAlpha by animateFloatAsState(
            targetValue = if (bottomObscured) 1f else 0f,
            animationSpec = tween(durationMillis = 200)
        )
        if (bottomFadeAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomFadeHeight)
                    .align(Alignment.BottomCenter)
                    .alpha(bottomFadeAlpha)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.25f to backgroundColor.copy(alpha = 0.7f),
                                0.5f to backgroundColor
                            )
                        )
                    )
            )
        }

        // Keep the snackbar above the floating bottom bar.
        val bottomBarZone = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding() + 60.dp
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarZone + 16.dp)
        )
    }
}

@Composable
private fun ScrollableWithFadeEdges(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    val topAlpha by animateFloatAsState(
        targetValue = if (scrollState.value > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    // Lets the last row rest 8dp above the floating bottom bar at full scroll
    // (the viewport bottom sits 8dp up because of the container's padding).
    val bottomRestSpace = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding() + 60.dp

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            content()
            Spacer(Modifier.height(bottomRestSpace))
        }

        if (topAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.TopCenter)
                    .alpha(topAlpha)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(backgroundColor, Color.Transparent)
                        )
                    )
            )
        }
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
    ScrollableWithFadeEdges(
        scrollState = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val valueColor = AppColors.linkColor()

            DisplaySectionTitle(stringResource(R.string.display))
            DisplayCard(cardTextColor, valueColor, context) { msg ->
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

    ScrollableWithFadeEdges(
        scrollState = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                DisplayCard(cardTextColor, valueColor, context) { msg ->
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
    context: android.content.Context,
    onSnackbar: (String) -> Unit
) {
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
            RangeSetting(labelColor, valueColor) { range -> viewModel.setRange(range) }
            NotificationSetting(labelColor, valueColor, permissionLauncher) { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
            AutoConnectSetting(labelColor, valueColor)

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
            Box(modifier = Modifier.fillMaxWidth()) {
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
                // Zero-size anchor at the button's bottom-right corner: the
                // menu popup positions itself from here, so it opens at the
                // right edge instead of the left.
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_option_bin)) },
                            leadingIcon = {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showExportMenu = false
                                saveBinLauncher.launch(generateFileName("bin"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_option_csv)) },
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

@Composable
private fun LanguageSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onChanged: (String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val currentLanguage = AppSettings.language

    val languageText = when (currentLanguage) {
        AppSettings.Language.SYSTEM -> stringResource(R.string.system_default)
        AppSettings.Language.CHINESE -> stringResource(R.string.chinese)
        AppSettings.Language.ENGLISH -> stringResource(R.string.english)
        AppSettings.Language.JAPANESE -> stringResource(R.string.japanese)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.language), fontSize = 15.sp, color = labelColor)
            Box(
                modifier = Modifier.width(130.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(languageText, fontSize = 15.sp, color = valueColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = valueColor, modifier = Modifier.size(18.dp))
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset((-80).dp, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.system_default)) },
                onClick = {
                    AppSettings.updateLanguage(AppSettings.Language.SYSTEM)
                    expanded = false
                    onChanged(AppSettings.localizedContext(context).getString(R.string.language_changed_to_system))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chinese)) },
                onClick = {
                    AppSettings.updateLanguage(AppSettings.Language.CHINESE)
                    expanded = false
                    onChanged(AppSettings.localizedContext(context).getString(R.string.language_changed_to_chinese))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.english)) },
                onClick = {
                    AppSettings.updateLanguage(AppSettings.Language.ENGLISH)
                    expanded = false
                    onChanged(AppSettings.localizedContext(context).getString(R.string.language_changed_to_english))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.japanese)) },
                onClick = {
                    AppSettings.updateLanguage(AppSettings.Language.JAPANESE)
                    expanded = false
                    onChanged(AppSettings.localizedContext(context).getString(R.string.language_changed_to_japanese))
                }
            )
        }
    }
}

@Composable
private fun ColorModeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onChanged: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentMode = AppSettings.colorMode

    val modeText = when (currentMode) {
        AppSettings.ColorMode.SYSTEM -> stringResource(R.string.system_default)
        AppSettings.ColorMode.LIGHT -> stringResource(R.string.always_light)
        AppSettings.ColorMode.DARK -> stringResource(R.string.always_dark)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.color_mode), fontSize = 15.sp, color = labelColor)
            Box(
                modifier = Modifier.width(130.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(modeText, fontSize = 15.sp, color = valueColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = valueColor, modifier = Modifier.size(18.dp))
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset((-80).dp, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.system_default)) },
                onClick = {
                    AppSettings.updateColorMode(AppSettings.ColorMode.SYSTEM)
                    expanded = false
                    onChanged()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.always_light)) },
                onClick = {
                    AppSettings.updateColorMode(AppSettings.ColorMode.LIGHT)
                    expanded = false
                    onChanged()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.always_dark)) },
                onClick = {
                    AppSettings.updateColorMode(AppSettings.ColorMode.DARK)
                    expanded = false
                    onChanged()
                }
            )
        }
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
private fun RangeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onRangeChange: (RangeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentRange = AppSettings.range

    val rangeText = when (currentRange) {
        RangeMode.AUTO -> stringResource(R.string.range_auto)
        RangeMode.LOW -> stringResource(R.string.range_low)
        RangeMode.MID -> stringResource(R.string.range_mid)
        RangeMode.HIGH -> stringResource(R.string.range_high)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.range), fontSize = 15.sp, color = labelColor)
            Box(
                modifier = Modifier.width(130.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(rangeText, fontSize = 15.sp, color = valueColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = valueColor, modifier = Modifier.size(18.dp))
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset((-80).dp, 0.dp)
        ) {
            RangeMode.entries.forEach { mode ->
                val label = when (mode) {
                    RangeMode.AUTO -> stringResource(R.string.range_auto)
                    RangeMode.LOW -> stringResource(R.string.range_low)
                    RangeMode.MID -> stringResource(R.string.range_mid)
                    RangeMode.HIGH -> stringResource(R.string.range_high)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        AppSettings.updateRange(mode)
                        onRangeChange(mode)
                        expanded = false
                    }
                )
            }
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
