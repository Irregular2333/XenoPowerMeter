package com.irregular.xenopowermeter.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: WaveformViewModel) {
    val cardTextColor = AppColors.buttonTextColor()
    val cardColor = AppColors.cardColor()
    val calibration by viewModel.calibration.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val recorder = viewModel.recorder

    val saveBinLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    recorder.exportToBin(os)
                }
                scope.launch(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("Exported ${recorder.getEntryCount()} records to .bin")
                }
            }
        }
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    recorder.exportToCsv(os)
                }
                scope.launch(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("Exported ${recorder.getEntryCount()} records to .csv")
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Export Data",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recording: ${recorder.getEntryCount()} samples",
                        fontSize = 14.sp,
                        color = cardTextColor
                    )
                    Text(
                        text = "Duration: ${String.format("%.2f", recorder.getDuration())}s",
                        fontSize = 14.sp,
                        color = cardTextColor
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { saveBinLauncher.launch("xenopower_recording.bin") },
                            modifier = Modifier.weight(1f),
                            enabled = recorder.getEntryCount() > 0
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export .bin", color = cardTextColor)
                        }

                        OutlinedButton(
                            onClick = { saveCsvLauncher.launch("xenopower_recording.csv") },
                            modifier = Modifier.weight(1f),
                            enabled = recorder.getEntryCount() > 0
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export .csv", color = cardTextColor)
                        }
                    }
                }
            }

            Divider()

            Text(
                text = "Calibration",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CalibrationRow("Low Scale", calibration.lowScaleMultiplier)
                    CalibrationRow("Low Offset", calibration.lowOffsetUa, "\u03BCA")
                    CalibrationRow("Mid Scale", calibration.midScaleMultiplier)
                    CalibrationRow("Mid Offset", calibration.midOffsetUa, "\u03BCA")
                    CalibrationRow("High Scale", calibration.highScaleMultiplier)
                    CalibrationRow("High Offset", calibration.highOffsetUa, "\u03BCA")

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.loadCalibration() },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Read", color = cardTextColor)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    viewModel.usbManager.resetCalibration()
                                    viewModel.loadCalibration()
                                }
                            },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", color = cardTextColor)
                        }
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )
    }
}

@Composable
fun CalibrationRow(label: String, value: Float, unit: String = "") {
    val cardTextColor = AppColors.buttonTextColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = cardTextColor)
        Text(
            "${String.format("%.6f", value)} $unit",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = cardTextColor
        )
    }
}
