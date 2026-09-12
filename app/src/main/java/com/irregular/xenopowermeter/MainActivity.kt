package com.irregular.xenopowermeter

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.irregular.xenopowermeter.ui.main.MainScreen
import com.irregular.xenopowermeter.ui.about.AboutScreen
import com.irregular.xenopowermeter.ui.navigation.bottomNavItems
import com.irregular.xenopowermeter.ui.settings.SettingsScreen
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.ui.theme.XenoPowerMeterTheme
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Context carrying the in-app locale for Compose string lookups. Still
 * exposes the activity's result registry — rememberLauncherForActivityResult
 * resolves it from LocalContext, and a plain configuration context would
 * break the SAF export and permission launchers.
 */
private class LocalizedActivityContext(
    context: Context,
    private val activity: AppCompatActivity
) : ContextWrapper(context), ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry
        get() = activity.activityResultRegistry

    // The wrapped configuration context carries no activity token, so
    // startActivity on it crashes with "Calling startActivity() from outside
    // of an Activity context" (link clicks on the About page). Delegate to
    // the real activity instead.
    override fun startActivity(intent: Intent) {
        activity.startActivity(intent)
    }
}

class MainActivity : AppCompatActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    /** Set when the activity was launched (or re-delivered) by a USB_DEVICE_ATTACHED intent. */
    private val usbAttachEvents = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        applyLocale()
        enableEdgeToEdge()
        requestNotificationPermission()
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbAttachEvents.value = true
        }
        setContent {
            XenoPowerMeterTheme {
                // Hot language switching: provide a context carrying the
                // in-app locale so every stringResource re-resolves as soon as
                // AppSettings.language changes — no activity recreation.
                val language = AppSettings.language
                val localizedContext = remember(language) { AppSettings.localizedContext(this) }
                CompositionLocalProvider(
                    LocalContext provides LocalizedActivityContext(localizedContext, this)
                ) {
                    XenoPowerApp(usbAttachEvents)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbAttachEvents.value = true
        }
    }

    private fun applyLocale() {
        val localeList = when (AppSettings.language) {
            AppSettings.Language.SYSTEM -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            AppSettings.Language.CHINESE -> androidx.core.os.LocaleListCompat.create(java.util.Locale("zh"))
            AppSettings.Language.ENGLISH -> androidx.core.os.LocaleListCompat.create(java.util.Locale("en"))
            AppSettings.Language.JAPANESE -> androidx.core.os.LocaleListCompat.create(java.util.Locale("ja"))
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun XenoPowerApp(usbAttachEvents: MutableStateFlow<Boolean>) {
    val viewModel: WaveformViewModel = viewModel()
    // ViewPager-style pager (the Compose counterpart of ViewPager2/PageView):
    // the pages sit side by side on a horizontal track, so swiping flips
    // pages with finger-following physics and tab taps scroll the track.
    val pagerState = rememberPagerState(initialPage = 0) { bottomNavItems.size }
    val view = LocalView.current
    val isRecording by viewModel.recorder.isRecording.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val usbAttached by usbAttachEvents.collectAsState()

    // Auto-connect when the system launched the app by plugging the meter in.
    // Always consume the event, even while already connected — otherwise a
    // redelivered attach intent lingers and fires a bogus "connect" the moment
    // the device disconnects.
    LaunchedEffect(usbAttached) {
        if (usbAttached) {
            usbAttachEvents.value = false
            if (!isConnected) {
                viewModel.connectAuto()
            }
        }
    }

    SideEffect {
        val window = (view.context as? AppCompatActivity)?.window
        if (isRecording) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val bottomBarHeight = 56.dp

    Box(Modifier.fillMaxSize()) {
        // Pages run edge-to-edge; the bottom bar floats above them, and the
        // scrollable pages fade out behind it with plain gradients.
        HorizontalPager(
            state = pagerState,
            // Keep the adjacent page composed so sliding to it never pays a
            // first-composition hitch.
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) { page ->
            when (page) {
                0 -> // Reserve the floating bar's zone so the info card /
                    // buttons / chart stack keeps filling the screen exactly
                    // as before.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                            .padding(bottom = bottomBarHeight + 4.dp)
                    ) {
                        MainScreen(viewModel = viewModel)
                    }
                1 -> SettingsScreen(viewModel = viewModel)
                else -> AboutScreen()
            }
        }

        val currentPage = pagerState.currentPage

        // Recomposition-driven, not disposal-driven: the onDispose of a
        // recreating activity's old composition can run AFTER the new
        // composition, which used to strand the flag at false and starve the
        // waveform while the Main page was clearly on screen.
        SideEffect {
            viewModel.setMainScreenVisible(currentPage == 0)
        }
        val bottomBarColor = AppColors.barColor()
        val unselectedIconTint = AppColors.buttonTextColor()

        Row(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                .width(330.dp)
                .height(bottomBarHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(28.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        blur(8f.dp.toPx())
                    },
                    highlight = null,
                    onDrawSurface = { drawRect(bottomBarColor) }
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bottomNavItems.forEachIndexed { index, screen ->
                val selected = currentPage == index
                val tint = if (selected) MaterialTheme.colorScheme.primary else unselectedIconTint

                val animationScope = rememberCoroutineScope()
                val progressAnimation = remember { Animatable(0f) }

                Box(
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null) {
                            if (currentPage != index) {
                                animationScope.launch {
                                    // Crisper than the default soft spring:
                                    // a full screen-width travel settles in
                                    // ~250ms without overshoot.
                                    pagerState.animateScrollToPage(
                                        index,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        }
                        .pointerInput(animationScope) {
                            val animationSpec = spring<Float>(0.5f, 300f, 0.001f)
                            awaitEachGesture {
                                awaitFirstDown()
                                animationScope.launch {
                                    progressAnimation.animateTo(1f, animationSpec)
                                }
                                waitForUpOrCancellation()
                                animationScope.launch {
                                    progressAnimation.animateTo(0f, animationSpec)
                                }
                            }
                        }
                        .graphicsLayer {
                            val progress = progressAnimation.value
                            val maxScale = 1.15f
                            val scale = lerp(1f, maxScale, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
