package com.irregular.xenopowermeter.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import androidx.core.content.ContextCompat
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import com.irregular.xenopowermeter.AppSettings
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.notification.IslandHelper
import com.irregular.xenopowermeter.notification.RecordingForegroundService
import com.irregular.xenopowermeter.ui.BottomBarInset
import com.irregular.xenopowermeter.ui.BottomBarTopEdge
import com.irregular.xenopowermeter.ui.BottomMeltOverhang
import com.irregular.xenopowermeter.ui.bottomEdgeContentFade
import com.irregular.xenopowermeter.ui.BottomBarHeight
import com.irregular.xenopowermeter.ui.PageEndGutter
import com.irregular.xenopowermeter.ui.RailPageStart
import com.irregular.xenopowermeter.ui.isTabletLandscape
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.irregular.xenopowermeter.ui.ProgressiveBottomBlur
import com.irregular.xenopowermeter.ui.ProgressiveTopBlur
import com.irregular.xenopowermeter.ui.TopBarSkirt
import com.irregular.xenopowermeter.ui.rememberTopEdgeProgress
import com.irregular.xenopowermeter.ui.scrollEdgeContentFade
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

@Composable
fun SettingsScreen(viewModel: WaveformViewModel) {
    val cardTextColor = AppColors.buttonTextColor()
    val cardColor = AppColors.cardColor()
    // Settings card buttons share the home pill's colour logic: the deeper
    // teal connect-button fill, home text colour, and the home pill's gray
    // disabled state (onSurface 12% with content dimmed to 45%).
    val settingsButtonColor = AppColors.connectButtonColor()
    val settingsButtonTextColor = AppColors.settingsButtonTextColor()
    val calibration by viewModel.calibration.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolve resources in composition, then keep asynchronous permission and
    // export results in sync with hot language/configuration changes.
    val notificationEnableFailed by rememberUpdatedState(stringResource(R.string.notification_enable_failed))
    val exportDirectoryUnknown by rememberUpdatedState(stringResource(R.string.export_dir_unknown))
    val exportBinSuccess by rememberUpdatedState(stringResource(R.string.export_bin_success))
    val exportCsvSuccess by rememberUpdatedState(stringResource(R.string.export_csv_success))
    val exportFailed by rememberUpdatedState(stringResource(R.string.export_fail))

    val recorder = viewModel.recorder
    val entryCount by recorder.entryCount.collectAsState()
    val duration by recorder.duration.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Granting needs no announcement — the switch has already moved to
        // "on" in plain sight. A refusal does: the switch stays off and the
        // system sheet has gone, so this is the only thing that explains why.
        if (granted) {
            AppSettings.updateNotificationEnabled(true)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(notificationEnableFailed)
            }
        }
    }

    fun generateFileName(ext: String): String {
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = recorder.endMillis }
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = recorder.startMillis }
        val date = String.format(Locale.US, "%04d%02d%02d", endCal.get(java.util.Calendar.YEAR), endCal.get(java.util.Calendar.MONTH) + 1, endCal.get(java.util.Calendar.DAY_OF_MONTH))
        val startTime = String.format(Locale.US, "%02d%02d%02d", startCal.get(java.util.Calendar.HOUR_OF_DAY), startCal.get(java.util.Calendar.MINUTE), startCal.get(java.util.Calendar.SECOND))
        val endTime = String.format(Locale.US, "%02d%02d%02d", endCal.get(java.util.Calendar.HOUR_OF_DAY), endCal.get(java.util.Calendar.MINUTE), endCal.get(java.util.Calendar.SECOND))
        return "XenoPowerMeter_Recording_${date}_${startTime}_${endTime}.$ext"
    }

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
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("The document provider did not open an output stream")
                    output.use { os ->
                        recorder.exportToBin(os)
                    }
                    // Resolve the directory here (ContentResolver.query is
                    // binder IPC) — not on the main thread below.
                    val directory = exportDirName(context, uri)
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            String.format(Locale.getDefault(), exportBinSuccess, entryCount,
                                directory.ifEmpty { exportDirectoryUnknown })
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(exportFailed)
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
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("The document provider did not open an output stream")
                    output.use { os ->
                        recorder.exportToCsv(os)
                    }
                    val directory = exportDirName(context, uri)
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            String.format(Locale.getDefault(), exportCsvSuccess, entryCount,
                                directory.ifEmpty { exportDirectoryUnknown })
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(exportFailed)
                    }
                }
            }
        }
    }

    val isLandscape = isTabletLandscape()
    val settingsScrollState = rememberScrollState()

    // The miuix Scaffold only acts as the popup host (the dropdowns render
    // their glass menus through MiuixPopupHost). Transparent background and
    // zero insets keep it invisible; the app's own layout below is untouched.
    //
    // The glass menus blur a backdrop layer of this page: background color +
    // cards, drawn here INSIDE the scaffold content so the popup host (drawn
    // by the scaffold itself, a sibling above this box) is excluded — the
    // panel must never sample itself.
    val backgroundColor = MaterialTheme.colorScheme.background
    val backgroundColorState = rememberUpdatedState(backgroundColor)
    val glassBackdropOnDraw: ContentDrawScope.() -> Unit = remember {
        {
            drawRect(backgroundColorState.value)
            drawContent()
        }
    }
    val glassBackdrop = rememberMiuixLayerBackdrop(onDraw = glassBackdropOnDraw)
    val glassDropdownHost = remember { mutableStateListOf<GlassDropdownEntry>() }

    MiuixSettingsTheme {
        CompositionLocalProvider(
            LocalGlassBackdrop provides glassBackdrop,
            LocalGlassDropdownHost provides glassDropdownHost
        ) {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .miuixLayerBackdrop(glassBackdrop)
            ) {
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

            // Glass dropdown popups registered by the rows, rendered here as a
            // layer ABOVE the backdrop-sampled content (so the panels never
            // sample themselves). No PopupLayout wrapper: the glass surface
            // handles outside taps itself and leaves no touch interceptor
            // behind after dismissal.
            glassDropdownHost.forEach { entry ->
                key(entry) {
                    entry.content()
                }
            }
        }
        } // CompositionLocalProvider(LocalGlassBackdrop)
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
    val isLandscape = isTabletLandscape()
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

    // Snapshot of just the scrolling column, for the blur slab below to read.
    // It has to sit outside the scroll so it covers the viewport instead of the
    // whole (much taller) content.
    val pageBackdrop = rememberLayerBackdrop()
    // The top material only exists while the page is scrolled.
    val topEdgeProgress = rememberTopEdgeProgress(scrollState)
    // Shorter than the shared 56dp bar height: this page has no title living
    // in the band, so a shallow slab covers the status bar gap plus a sliver
    // of breathing room and stays out of the first card's way.
    val topMaterialHeight = contentTopSpace + 24.dp

    Box(modifier = modifier.fillMaxSize()) {
        // The bounce is the app-wide translation-only overscroll (see
        // BouncyOverscroll): it moves the viewport without re-recording the
        // content into a stretched RenderNode, so the backdrop this page hands
        // to the glass stays valid from frame to frame.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
                // Inside the recorded layer on purpose: the slab above must be
                // an opaque copy of this, or the sharp page shows through the
                // blur and ghosts while scrolling.
                .background(MaterialTheme.colorScheme.background)
                // Content fades towards the status bar before it is recorded,
                // so the material never has to cover sharp text with a
                // half-transparent copy.
                .scrollEdgeContentFade(
                    progress = { topEdgeProgress.value },
                    headerHeight = topMaterialHeight + TopBarSkirt
                )
                // Bottom mirror: rows dissolve into the page as they slide
                // under the pill, the same dissolve-to-background the top
                // edge does — the mask spans exactly the bottom band and the
                // opaque column background stays outside the buffer. Always
                // on, like the band's blur: content rests inside the band
                // even before the first scroll.
                .then(
                    if (!isLandscape) {
                        Modifier.bottomEdgeContentFade(
                            bandHeight = BottomBarTopEdge + BottomMeltOverhang
                        )
                    } else {
                        Modifier
                    }
                )
                .verticalScroll(scrollState)
                .padding(start = contentStart, end = PageEndGutter)
        ) {
            Spacer(Modifier.height(contentTopSpace))
            content()
            Spacer(Modifier.height(bottomRestSpace))
        }

        if (!isLandscape) {
            // Anchored below the pill's crown: the radius ramps from exactly
            // zero at the band's top edge, so the overhang draws no line of
            // its own — it just moves the melt start higher, so a row is
            // already slightly soft by the time it meets the glass instead of
            // changing state at the pill's top edge.
            ProgressiveBottomBlur(
                backdrop = pageBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
                bandHeight = BottomBarTopEdge + BottomMeltOverhang,
                maxBlur = 20.dp
            )
        }

        ProgressiveTopBlur(
            backdrop = pageBackdrop,
            progress = { topEdgeProgress.value },
            modifier = Modifier.align(Alignment.TopCenter),
            bandHeight = topMaterialHeight,
            maxBlur = 18.dp
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
            DisplayCard(cardTextColor, valueColor, viewModel)

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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DisplaySectionTitle(stringResource(R.string.display))
                DisplayCard(cardTextColor, valueColor, viewModel)

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
    viewModel: WaveformViewModel
) {
    @Suppress("UNUSED_VARIABLE")
    val lang = AppSettings.language
    val cardColor = AppColors.cardColor()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        // No padding at all: the four rows span the full card and each is
        // exactly one quarter of the card height, so the miuix press
        // highlight covers a perfect quarter of the card, clipped to the
        // rounded corners. Rows inset their own content by 16dp
        // (MiuixRowInsideMargin).
        Column {
            // Everything on this card shows its new value the moment it is
            // picked, so none of it announces itself with a snackbar.
            LanguageSetting(labelColor, valueColor)
            ColorModeSetting(labelColor, valueColor)
            ChartScaleModeSetting(labelColor, valueColor)
            RangeSetting(labelColor, valueColor) { range -> viewModel.setRange(range) }
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
    val isRecording by recorder.isRecording.collectAsState()
    val exportNeedPause by rememberUpdatedState(stringResource(R.string.export_need_pause))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        // Same recipe as DisplayCard: no card padding — every row spans the
        // full card width at a uniform 44dp (the duration-limit row's miuix
        // press highlight covers its whole line, clipped to the rounded
        // corners), each row insets its own content by 16dp, and the button
        // row carries its own vertical spacing. Every row is miuix-demo
        // pressable: dropdown/switch rows act on tap, the pure read-out rows
        // (count / duration) show the highlight but trigger nothing.
        Column {
            Column {
                AutoConnectSetting(labelColor, valueColor)
                NotificationSetting(labelColor, valueColor, permissionLauncher)
                RecordingDurationLimitSetting(labelColor, valueColor)

                // Pure read-outs, but still miuix-demo pressable rows: the
                // tap target covers the whole line and the MiuixIndication
                // highlight plays on press, while the empty onClick makes
                // sure nothing actually happens on release.
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                        .clickable(onClick = {})
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.record_count_label), fontSize = 15.sp, color = labelColor)
                    Text(String.format(stringResource(R.string.recording_value), entryCount), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                        .clickable(onClick = {})
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.record_duration_label), fontSize = 15.sp, color = labelColor)
                    Text(String.format(stringResource(R.string.duration_value), duration), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
                }
            }

            var showExportMenu by remember { mutableStateOf(false) }
            var showClearDialog by remember { mutableStateOf(false) }
            var clearSessionId by remember { mutableLongStateOf(0L) }
            Row(
                modifier = Modifier.fillMaxWidth()
                    // Perceived margin, not box margin: the row above centers
                    // its 15sp/24dp text in 44dp, leaving ~10dp of whitespace
                    // under the text. 2dp here makes the VISIBLE gap between
                    // that text and the button 12dp — matching the left/right
                    // and bottom insets exactly.
                    .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 12.dp)
                    .height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Button(
                        onClick = {
                            if (isRecording) {
                                scope.launch { snackbarHostState.showSnackbar(exportNeedPause) }
                            } else {
                                showExportMenu = true
                            }
                        },
                        enabled = entryCount > 0,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = settingsButtonColor,
                            contentColor = labelColor,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            disabledContentColor = labelColor.copy(alpha = 0.45f)
                        )
                    ) {
                        // Home GlassPill keeps its icon neutral (ambient
                        // LocalContentColor) while the label takes the teal —
                        // the disabled icon reads as plain gray there. The M3
                        // Button paints both from disabledContentColor, which
                        // tints the icon teal at 45%, so mirror the home rule:
                        // neutral onSurface gray when disabled, inherit when on.
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            tint = if (entryCount > 0) LocalContentColor.current
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.export_data),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
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
                                    // Match the menu label's onSurface dark gray; the
                                    // M3 default leadingIcon tint (onSurfaceVariant)
                                    // renders visibly lighter next to the text.
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showExportMenu = false
                                    saveBinLauncher.launch(generateFileName("bin"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(exportCtx.getString(R.string.export_option_csv)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.TableChart,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showExportMenu = false
                                    saveCsvLauncher.launch(generateFileName("csv"))
                                }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Button(
                        onClick = {
                            clearSessionId = recorder.sessionId
                            showClearDialog = true
                        },
                        enabled = entryCount > 0 && !isRecording,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = settingsButtonColor,
                            contentColor = labelColor,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            disabledContentColor = labelColor.copy(alpha = 0.45f)
                        )
                    ) {
                        // Neutral gray when disabled — see the export button.
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = if (entryCount > 0 && !isRecording) LocalContentColor.current
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.clear),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
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
                            val expectedSessionId = clearSessionId
                            scope.launch(Dispatchers.IO) {
                                recorder.clearRecordingIfStopped(expectedSessionId)
                            }
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
        // Same recipe as DisplayCard: no card padding, rows span the full
        // card width at a uniform 44dp and inset their own content by 16dp;
        // the read button carries its own spacing.
        Column {
            Column {
                CalibrationRow(stringResource(R.string.calibration_low_scale), calibration.lowScaleMultiplier)
                CalibrationRow(stringResource(R.string.calibration_low_offset), calibration.lowOffsetUa, "\u03BCA")
                CalibrationRow(stringResource(R.string.calibration_mid_scale), calibration.midScaleMultiplier)
                CalibrationRow(stringResource(R.string.calibration_mid_offset), calibration.midOffsetUa, "\u03BCA")
                CalibrationRow(stringResource(R.string.calibration_high_scale), calibration.highScaleMultiplier)
                CalibrationRow(stringResource(R.string.calibration_high_offset), calibration.highOffsetUa, "\u03BCA")
            }

            Button(
                onClick = { viewModel.loadCalibration() },
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth()
                    // Same perceived-margin trick as the device card: the
                    // last calibration row leaves ~10dp of centered-text
                    // whitespace below it, so 2dp here yields a visible 12dp
                    // gap — identical to the left/right/bottom insets.
                    .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 12.dp)
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = settingsButtonColor,
                    contentColor = AppColors.buttonTextColor(),
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = AppColors.buttonTextColor().copy(alpha = 0.45f)
                )
            ) {
                // Neutral gray when disabled — see the export button.
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = if (isConnected) LocalContentColor.current
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.read))
            }
        }
    }
}

/**
 * Content inset for miuix preference rows. The horizontal 16dp matches the
 * card padding the hand-built rows live under, but is applied INSIDE the row:
 * the row itself spans the full card width, so the miuix press/hold highlight
 * covers the whole line (clipped to the card's rounded corners by the card
 * surface) instead of an inset rectangle. Vertical 8dp + title line height
 * keeps the ~38dp rhythm the hand-built rows use.
 */
private val MiuixRowInsideMargin = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 16.dp, vertical = 8.dp
)

/**
 * Display card rows: exactly one quarter of the card each (card height is
 * 4 x 44dp = 176dp), so the press highlight covers exactly a quarter of the
 * card. Also the uniform row height for the Device and Calibration cards.
 */
private val MiuixDisplayRowHeight = Modifier.height(44.dp)

@Composable
private fun LanguageSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val langCtx = AppSettings.localizedContext(LocalContext.current)
    val currentLanguage = AppSettings.language

    SettingsDropdownPreference(
        title = stringResource(R.string.language),
        items = listOf(
            langCtx.getString(R.string.system_default),
            langCtx.getString(R.string.chinese),
            langCtx.getString(R.string.english),
            langCtx.getString(R.string.japanese)
        ),
        selectedIndex = currentLanguage.ordinal,
        onSelectedIndexChange = { index ->
            AppSettings.updateLanguage(AppSettings.Language.entries[index])
        },
        modifier = MiuixDisplayRowHeight,
        insideMargin = MiuixRowInsideMargin
    )
}

@Composable
private fun ColorModeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val currentMode = AppSettings.colorMode
    val localizedCtx = AppSettings.localizedContext(LocalContext.current)

    SettingsDropdownPreference(
        title = stringResource(R.string.color_mode),
        items = listOf(
            localizedCtx.getString(R.string.system_default),
            localizedCtx.getString(R.string.always_light),
            localizedCtx.getString(R.string.always_dark)
        ),
        selectedIndex = currentMode.ordinal,
        onSelectedIndexChange = { index ->
            AppSettings.updateColorMode(AppSettings.ColorMode.entries[index])
        },
        modifier = MiuixDisplayRowHeight,
        insideMargin = MiuixRowInsideMargin
    )
}

@Composable
private fun ChartScaleModeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val currentMode = AppSettings.chartScaleMode
    val localizedCtx = AppSettings.localizedContext(LocalContext.current)

    SettingsDropdownPreference(
        title = stringResource(R.string.chart_scale_mode),
        items = listOf(
            localizedCtx.getString(R.string.chart_scale_auto),
            localizedCtx.getString(R.string.chart_scale_full_range)
        ),
        selectedIndex = currentMode.ordinal,
        onSelectedIndexChange = { index ->
            AppSettings.updateChartScaleMode(AppSettings.ChartScaleMode.entries[index])
        },
        modifier = MiuixDisplayRowHeight,
        insideMargin = MiuixRowInsideMargin
    )
}

@Composable
private fun NotificationSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    val enabled = AppSettings.notificationEnabled

    // Shared toggle target: the row click and the switch itself both funnel
    // through here, so pressing anywhere on the line behaves the same
    // (permission ask on turn-on, service teardown on turn-off).
    val toggleNotification: (Boolean) -> Unit = { turnOn ->
        if (turnOn) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED
            ) {
                AppSettings.updateNotificationEnabled(true)
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            AppSettings.updateNotificationEnabled(false)
            IslandHelper.cancelNotification(context)
            // Without a notification the service has nothing to
            // anchor on, and Android would swap in its own
            // "app is running in background" notice instead.
            RecordingForegroundService.stop(context)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { toggleNotification(!enabled) }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.notification_push), fontSize = 15.sp, color = labelColor)
        Box(
            modifier = Modifier.width(130.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            MiuixSwitch(
                checked = enabled,
                onCheckedChange = toggleNotification,
                colors = SwitchDefaults.switchColors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    disabledCheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    disabledUncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = valueColor,
                    uncheckedTrackColor = labelColor.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
private fun AutoConnectSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val enabled = AppSettings.autoConnect

    // miuix demo switch rows: the WHOLE line is the toggle target. Tapping
    // the row flips the switch (the MiuixIndication highlight plays under
    // the finger); tapping the switch itself still works — the switch
    // consumes its own tap, so the row handler never double-fires.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { AppSettings.updateAutoConnect(!enabled) }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.auto_connect), fontSize = 15.sp, color = labelColor)
        Box(
            modifier = Modifier.width(130.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            MiuixSwitch(
                checked = enabled,
                onCheckedChange = { turnOn -> AppSettings.updateAutoConnect(turnOn) },
                colors = SwitchDefaults.switchColors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    disabledCheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    disabledUncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = valueColor,
                    uncheckedTrackColor = labelColor.copy(alpha = 0.3f)
                )
            )
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

    SettingsDropdownPreference(
        title = stringResource(R.string.recording_duration_limit),
        items = AppSettings.RECORDING_DURATION_OPTIONS.map { minutes ->
            String.format(limitCtx.getString(R.string.recording_duration_value), minutes)
        },
        selectedIndex = AppSettings.RECORDING_DURATION_OPTIONS.indexOf(currentLimit),
        onSelectedIndexChange = { index ->
            AppSettings.updateRecordingDurationLimit(AppSettings.RECORDING_DURATION_OPTIONS[index])
        },
        modifier = MiuixDisplayRowHeight,
        insideMargin = MiuixRowInsideMargin
    )
}

@Composable
private fun RangeSetting(
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    onRangeChange: (RangeMode) -> Unit
) {
    val currentRange = AppSettings.range
    val rangeCtx = AppSettings.localizedContext(LocalContext.current)

    SettingsDropdownPreference(
        title = stringResource(R.string.range),
        items = RangeMode.entries.map { mode ->
            when (mode) {
                RangeMode.AUTO -> rangeCtx.getString(R.string.range_auto)
                RangeMode.LOW -> rangeCtx.getString(R.string.range_low)
                RangeMode.MID -> rangeCtx.getString(R.string.range_mid)
                RangeMode.HIGH -> rangeCtx.getString(R.string.range_high)
            }
        },
        selectedIndex = currentRange.ordinal,
        onSelectedIndexChange = { index ->
            val mode = RangeMode.entries[index]
            AppSettings.updateRange(mode)
            onRangeChange(mode)
        },
        modifier = MiuixDisplayRowHeight,
        insideMargin = MiuixRowInsideMargin
    )
}

@Composable
fun CalibrationRow(label: String, value: Float, unit: String = "") {
    val labelColor = AppColors.buttonTextColor()
    val valueColor = AppColors.linkColor()
    // Read-only readout, but styled like the miuix demo rows: pressable with
    // the full-line MiuixIndication highlight; the empty onClick guarantees
    // releasing does nothing.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = {})
            .padding(horizontal = 16.dp),
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
