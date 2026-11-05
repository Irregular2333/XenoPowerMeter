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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalOverscrollFactory
import com.irregular.xenopowermeter.ui.BouncyOverscrollFactory
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.irregular.xenopowermeter.ui.BarPressState
import com.irregular.xenopowermeter.ui.BottomBarInset
import com.irregular.xenopowermeter.ui.BottomBarHeight
import com.irregular.xenopowermeter.ui.BottomBarLift
import com.irregular.xenopowermeter.ui.GlassRimHighlight
import com.irregular.xenopowermeter.ui.PageEndGutter
import com.irregular.xenopowermeter.ui.RailActionCount
import com.irregular.xenopowermeter.ui.RailActionGap
import com.irregular.xenopowermeter.ui.RailActionStack
import com.irregular.xenopowermeter.ui.RailInset
import com.irregular.xenopowermeter.ui.RailLength
import com.irregular.xenopowermeter.ui.RailLift
import com.irregular.xenopowermeter.ui.RailPageStart
import com.irregular.xenopowermeter.ui.RailThickness
import com.irregular.xenopowermeter.ui.RailTopInset
import com.irregular.xenopowermeter.ui.main.MainScreen
import com.irregular.xenopowermeter.ui.about.AboutScreen
import com.irregular.xenopowermeter.ui.about.ChangelogScreen
import com.irregular.xenopowermeter.ui.navigation.bottomNavItems
import com.irregular.xenopowermeter.ui.settings.SettingsScreen
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.ui.theme.XenoPowerMeterTheme
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    // Release notes live above the pager as a full-screen page: opening them
    // must not tear down the tab pages (or their scroll positions), and the
    // floating bar has to disappear behind them, which an in-page overlay
    // could not do.
    //
    // Held as the State object rather than read through a delegate: reading it
    // here would put the read in this function's restart scope, so flipping it
    // would re-run the whole screen — pager, bars, waveform and all — on the
    // very frame the page is composed, which measured as a ~70ms stall right
    // after the tap. [ChangelogHost] reads it instead, so only that tiny
    // composable invalidates.
    val showChangelog = rememberSaveable { mutableStateOf(false) }
    // Back inside the release notes is handled by the page itself: it owns the
    // predictive-gesture progress, so a BackHandler here would steal the
    // gesture before the page could follow it.
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
    // rememberLayerBackdrop keys its LayerBackdrop on the onDraw lambda, so an
    // inline lambda returns a brand-new backdrop on every recomposition. A new
    // backdrop starts with null layout coordinates and only receives them on the
    // next placement pass — which a pure recomposition never triggers — so the
    // bar silently loses its blur until something re-lays-out the tree. Hoisting
    // the lambda keeps the backdrop, and the layer it records into, stable.
    val backgroundColorState = rememberUpdatedState(backgroundColor)
    val backdropOnDraw: ContentDrawScope.() -> Unit = remember {
        {
            drawRect(backgroundColorState.value)
            drawContent()
        }
    }
    val backdrop = rememberLayerBackdrop(onDraw = backdropOnDraw)

    val bottomBarHeight = BottomBarHeight
    // Landscape turns the pill on its side and hangs it off the start edge;
    // portrait keeps it floating along the bottom. Everything below reads this
    // one flag, so the bar, the pages' insets and the pages' own bands agree.
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    Box(Modifier.fillMaxSize()) {
        // Pages run edge-to-edge; the bar floats above them, and the
        // scrollable pages dissolve their edges into it with blur bands.
        // One page factory for both orientations so the two pagers can't
        // drift apart.
        val pageContent: @Composable androidx.compose.foundation.pager.PagerScope.(Int) -> Unit = { page ->
            when (page) {
                0 -> // Reserve the floating bar's zone so the info card /
                    // buttons / chart stack keeps filling the screen exactly
                    // as before. In landscape that zone is the rail on the
                    // start edge instead of the strip along the bottom.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (isLandscape) {
                                    // RailPageStart already folds in the rail's
                                    // own inset, and it is the same value the
                                    // other two pages use — so all three
                                    // content columns start on one line.
                                    Modifier.padding(
                                        start = RailPageStart,
                                        end = PageEndGutter
                                    )
                                } else {
                                    Modifier
                                        .windowInsetsPadding(BottomBarInset)
                                        .padding(bottom = bottomBarHeight + BottomBarLift)
                                }
                            )
                    ) {
                        MainScreen(viewModel = viewModel)
                    }
                1 -> SettingsScreen(viewModel = viewModel)
                else -> AboutScreen(onOpenChangelog = { showChangelog.value = true })
            }
        }

        // The pager follows the bar: portrait flips pages sideways along the
        // bottom pill, landscape flips them vertically alongside the rail —
        // the swipe axis matches the direction the navigation sits in.
        // No rubber band here: the rubber-band bounce belongs to the scrolling
        // lists, a paging swipe reads better stopping dead at the neighbour
        // page. The null provision must stop at the pager: pageContent runs
        // INSIDE the pager, so the pages (and every list in them) would inherit
        // the null and lose their bounce — provide the theme's bounce factory
        // again at the page level, where the lists actually live.
        val pagerModifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
        val pagerNoBounce = LocalOverscrollFactory provides null
        val pageListBounce = LocalOverscrollFactory provides BouncyOverscrollFactory
        if (isLandscape) {
            CompositionLocalProvider(pagerNoBounce) {
                VerticalPager(
                    state = pagerState,
                    // Keep the adjacent page composed so sliding to it never pays a
                    // first-composition hitch.
                    beyondViewportPageCount = 1,
                    modifier = pagerModifier,
                    pageContent = { page ->
                        CompositionLocalProvider(pageListBounce) { pageContent(page) }
                    }
                )
            }
        } else {
            CompositionLocalProvider(pagerNoBounce) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = pagerModifier,
                    pageContent = { page ->
                        CompositionLocalProvider(pageListBounce) { pageContent(page) }
                    }
                )
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

        // Pages run all the way to the screen edge; the bar floats on top and
        // samples them through its own backdrop.

        // Glass, for real this time: the tint goes *over* a blurred snapshot
        // instead of hiding it. RenderEffect needs API 31, so older devices keep
        // a nearly opaque surface — there is no blur there for a translucent
        // fill to reveal, and half-transparent would just show sharp content.
        val blurSupported = isRenderEffectSupported()
        val refractSupported = isRuntimeShaderSupported()
        val isDarkBar = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val barSurfaceColor = AppColors.barColor()
            .copy(alpha = if (blurSupported) 0.58f else 0.93f)
        // The portrait bar already tells the actions apart by fill: connect is
        // the deeper teal, clear and record sit on the bar colour. The rail
        // borrows the same pair so the two orientations agree.
        val connectSurfaceColor = AppColors.connectButtonColor()
            .copy(alpha = if (blurSupported) 0.58f else 0.93f)
        // Disabled actions go grey in portrait — Material's filled button
        // paints onSurface at 12% over the surface, and its label at 38%.
        // The rail copies both values instead of keeping the teal and just
        // fading the icon, which read as "still active, just dim".
        val disabledSurfaceColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            .compositeOver(MaterialTheme.colorScheme.surface)
            .copy(alpha = if (blurSupported) 0.58f else 0.93f)
        val disabledContentTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        val unselectedIconTint = AppColors.buttonTextColor()

        // Full pill: the radius is half the height, so the caps stay round at
        // any bar height instead of flattening into a rounded rectangle.
        // The very same capsule serves both orientations: lying flat it spans
        // the bottom of the screen, standing up it becomes the rail beside the
        // content. The three action circles are separate pieces of glass above
        // it — which is what lets a tab press scale the capsule, highlight and
        // labels included, without dragging the buttons along (and vice versa).
        val capsuleShape: Shape = remember(bottomBarHeight) {
            RoundedCornerShape(bottomBarHeight / 2)
        }
        // Height of the circle stack riding above the capsule (0 in portrait).
        val actionStack = if (isLandscape) RailActionStack else 0.dp
        val actionStackPx = with(LocalDensity.current) { actionStack.toPx() }
        // A tab press pools light at the finger, and the finger is measured in
        // the rail node's coordinates while the capsule draws in its own — the
        // circle stack's height is the difference.
        val haloShift = Offset(0f, actionStackPx)
        // Light blur on purpose. Thickness comes from the lens below:
        // bending what is behind the rim is what reads as a slab of glass,
        // while a heavy blur only reads as frosted plastic and swallows the
        // page underneath. Mirror keeps the rim from sampling a clamped row.
        val glassEffects: BackdropEffectScope.() -> Unit =
            remember(blurSupported, refractSupported, isLandscape) {
                {
                    if (blurSupported) {
                        vibrancy()
                        blur(8f.dp.toPx(), TileMode.Mirror)
                        // Mirror inflates padding to the radius, but that ring
                        // records nothing except off-screen pixels — the lens
                        // folds its samples back into the node on its own, so
                        // the ring buys nothing.
                        padding = 0f
                        // The library's own liquid-glass lens, the same one the
                        // AndroidLiquidGlass catalog uses for its bottom tabs
                        // (24dp refraction height, 24dp amount). It only knows
                        // a rounded-rect outline; the fused rail outline is not
                        // one, so the landscape rail keeps plain blur +
                        // vibrancy.
                        if (refractSupported && !isLandscape) {
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        }
                    }
                }
            }

        val barScope = rememberCoroutineScope()
        val pressState = remember(barScope) { BarPressState(barScope) }
        // Deep surfaces take a stronger halo; on a pale one the same alpha
        // clips to outright white instead of reading as light.
        val haloPeak = if (isDarkBar) 0.12f else 0.04f

        // Remembered as a whole so a recomposition that changes nothing visual
        // doesn't rebuild the element (and re-apply the effects) every time.
        // The press values are read inside draw, so a press only invalidates
        // the draw phase — not this modifier, and not the composition.
        val glassSurface =
            remember(backdrop, barSurfaceColor, glassEffects, haloPeak, capsuleShape, haloShift) {
                Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = glassEffects,
                        // Both are non-null defaults in the library, so opting
                        // out has to be explicit — but only the shadow stays
                        // off; the rim is back in a softened form.
                        highlight = GlassRimHighlight,
                        shadow = null,
                        innerShadow = null,
                        onDrawSurface = { drawRect(barSurfaceColor) },
                        onDrawFront = {
                            val progress = pressState.progress.value
                            if (progress > 0.001f) {
                                // The capsule brightens a touch...
                                drawRect(
                                    Color.White.copy(alpha = 0.03f * progress),
                                    blendMode = BlendMode.Plus
                                )
                                // ...and a soft disc sits under the finger. Solid
                                // out to half its radius, then a long falloff, so
                                // it reads as light pooling in the glass rather
                                // than a dot painted on top.
                                val center = pressState.finger.value - haloShift
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

        // The action circles are their own discs of the same material. Same
        // effects, same rim, same glass — only the tint differs, and it
        // differs exactly the way the portrait buttons do: the connect action
        // wears the deeper teal (connectButtonColor), clear and record sit on
        // the bar tint. Reading the fills from the same palette is what keeps
        // the two orientations looking like one app rather than two.
        fun circleGlass(tint: Color): Modifier = Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = glassEffects,
            highlight = GlassRimHighlight,
            shadow = null,
            innerShadow = null,
            onDrawSurface = { drawRect(tint) }
        )

        val circleSurface = remember(backdrop, barSurfaceColor, glassEffects) {
            circleGlass(barSurfaceColor)
        }
        val connectCircleSurface = remember(backdrop, connectSurfaceColor, glassEffects) {
            circleGlass(connectSurfaceColor)
        }
        val disabledCircleSurface = remember(backdrop, disabledSurfaceColor, glassEffects) {
            circleGlass(disabledSurfaceColor)
        }

        // Geometry: one capsule holding labelled tabs. Its thickness comes from
        // BottomBarHeight, so the bar, its rim and the pages' reserved space
        // can't drift apart; the highlight is then inset from every edge —
        // 3dp along the tab axis, 6dp across — which keeps it a capsule
        // floating inside the glass rather than a band filling it. The padding
        // along that axis is shared between the tabs.
        //
        // In landscape the very same capsule stands on its end: length and
        // thickness swap, tabs stack down it, and the highlight slides along y.
        val barLength = RailLength
        val barThickness = bottomBarHeight
        // The rail node also has to hold the circle stack, but the capsule
        // keeps its own length — tab geometry never mixes with the circles.
        val nodeLength = barLength + actionStack
        val barPadding = 4.dp
        val selectorInset = 3.dp
        val selectorCross = bottomBarHeight - 12.dp
        val tabCount = bottomNavItems.size
        val tabSpan = (barLength - barPadding * 2) / tabCount
        val tabSpanPx = with(LocalDensity.current) { tabSpan.toPx() }
        val selectorSpan = tabSpan - selectorInset * 2
        // Slightly darker than the glass in light mode, slightly brighter in
        // dark mode: the same relationship the reference uses (black 6% over a
        // pale bar, white 10% over a deep one).
        val selectorColor =
            if (isDarkBar) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.06f)

        // Position of the highlight in tab units: 0 is the first tab fully
        // selected, 1.5 is exactly between the second and the third. A float,
        // not an index, so a pager swipe tracks continuously and a drag follows
        // the finger instead of snapping to whole tabs.
        val indicator = remember { Animatable(pagerState.currentPage.toFloat()) }
        val activeIndex by remember {
            derivedStateOf { indicator.value.roundToInt().coerceIn(0, tabCount - 1) }
        }
        // How many gestures currently own the indicator. While it is above
        // zero the pager must not push its own progress in: a drag sets the
        // position, and the release animation is deliberately not the pager's.
        var barHolds by remember { mutableIntStateOf(0) }
        val haptic = LocalHapticFeedback.current

        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                .collect { if (barHolds == 0) indicator.snapTo(it) }
        }

        // Capsule and page travel together, and the bar only lets go of the
        // indicator once both have landed — otherwise the pager's progress
        // would snap the highlight backwards mid-flight.
        val goToTab: (Int) -> Unit = { target ->
            barHolds += 1
            barScope.launch {
                try {
                    coroutineScope {
                        launch {
                            indicator.animateTo(
                                target.toFloat(),
                                spring(0.8f, 350f, 0.001f)
                            )
                        }
                        launch {
                            // Crisper than the default soft spring: a full
                            // screen-width travel settles in ~250ms without
                            // overshoot.
                            if (pagerState.currentPage != target ||
                                pagerState.currentPageOffsetFraction != 0f
                            ) {
                                pagerState.animateScrollToPage(
                                    target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    }
                } finally {
                    barHolds -= 1
                }
            }
        }

        Box(
            modifier = Modifier
                .then(
                    if (isLandscape) {
                        Modifier
                            .padding(start = RailLift)
                            .windowInsetsPadding(RailInset)
                            // Reserve the status bar before centring: the
                            // column then has equal air above and below inside
                            // the canvas the eye actually sees, instead of
                            // being centred on a window whose top 33dp are
                            // chrome (which read as 16dp too high).
                            .windowInsetsPadding(RailTopInset)
                    } else {
                        Modifier
                            .padding(bottom = BottomBarLift)
                            .windowInsetsPadding(BottomBarInset)
                    }
                )
                .then(
                    if (isLandscape) Modifier.width(barThickness).height(nodeLength)
                    else Modifier.width(barLength).height(barThickness)
                )
                .align(if (isLandscape) Alignment.CenterStart else Alignment.BottomCenter)
                // Portrait: the node IS the capsule, so the whole thing —
                // glass, highlight, labels — scales into the press and the
                // 0.5-damping spring overshoots back out. Landscape keeps
                // only the gesture here: its glass lives in the capsule and
                // circle nodes below, each scaling on its own.
                .then(
                    if (isLandscape) {
                        Modifier
                    } else {
                        Modifier
                            .graphicsLayer {
                                val s = 1f + 0.05f * pressState.progress.value
                                scaleX = s
                                scaleY = s
                            }
                            .clip(capsuleShape)
                            .then(glassSurface)
                    }
                )
                // One gesture for the whole bar: a tap picks the tab under the
                // finger, a drag along it carries the highlight 1:1 and lets go
                // on the nearest tab. Press state lives here too, so the halo
                // follows a drag the same way it follows a press. The axis
                // follows the bar: x when it lies flat, y when it stands up.
                .pointerInput(tabCount, tabSpanPx, isLandscape) {
                    val slop = viewConfiguration.touchSlop
                    val padPx = barPadding.toPx()
                    fun alongBar(position: Offset) =
                        if (isLandscape) position.y - actionStackPx else position.x
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Above the capsule live the action circles; they own
                        // their gestures, the tab gesture must not eat them.
                        if (isLandscape && down.position.y < actionStackPx) {
                            return@awaitEachGesture
                        }
                        pressState.press(down.position)
                        barHolds += 1

                        val downTab = floor((alongBar(down.position) - padPx) / tabSpanPx)
                            .toInt().coerceIn(0, tabCount - 1)
                        // Pressing a tab that is not the active one flies the
                        // highlight over straight away, like the reference: the
                        // bar answers the finger before the page moves.
                        if (downTab != indicator.value.roundToInt()) {
                            barScope.launch {
                                indicator.animateTo(
                                    downTab.toFloat(),
                                    spring(0.8f, 320f, 0.001f)
                                )
                            }
                        }

                        var lastAlong = alongBar(down.position)
                        var travel = 0f
                        var dragging = false
                        var lastTick = downTab

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = alongBar(change.position) - lastAlong
                            lastAlong = alongBar(change.position)
                            travel += delta
                            if (!dragging && abs(travel) > slop) dragging = true
                            if (dragging) {
                                pressState.moveTo(change.position)
                                val position = (downTab + travel / tabSpanPx)
                                    .coerceIn(0f, (tabCount - 1).toFloat())
                                barScope.launch { indicator.snapTo(position) }
                                val tick = position.roundToInt()
                                if (tick != lastTick) {
                                    lastTick = tick
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                }
                                change.consume()
                            }
                        }

                        pressState.release()
                        val target = if (dragging) {
                            indicator.value.roundToInt().coerceIn(0, tabCount - 1)
                        } else {
                            downTab
                        }
                        // Dragging already ticked once per tab crossed; a tap
                        // crosses none, so it would have landed silently. It
                        // gets the same single click the action circles give —
                        // a light keyboard-class click rather than the tick,
                        // so "I picked this" never feels like "I scrolled past
                        // this".
                        if (!dragging) {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        }
                        goToTab(target)
                        barHolds -= 1
                    }
                },
            contentAlignment = if (isLandscape) Alignment.TopCenter else Alignment.CenterStart
        ) {
            // The active tab carries the accent; the rest are held back so
            // the highlight isn't the only thing telling them apart.
            val selectedTint = MaterialTheme.colorScheme.primary
            val unselectedTint = unselectedIconTint.copy(alpha = 0.78f)

            // Sliding highlight, between the glass and the icons so the blur
            // still reads behind it. The offset is read in placement, not in
            // composition, so a drag doesn't recompose the tabs every frame.
            // In landscape it lives inside the capsule group, whose own offset
            // already covers the circle stack, so it only travels the tab axis.
            //
            // The Q-bounce lives here too: the slider jelly-stretches along
            // the tab axis and bulges across it while held, and the 0.5-damping
            // press spring makes it overshoot on release — the reference
            // bounces its slider the same way, never the whole bar.
            val highlight = Modifier
                .offset {
                    val along = (barPadding + selectorInset).toPx().roundToInt() +
                        (indicator.value * tabSpanPx).roundToInt()
                    if (isLandscape) IntOffset(0, along) else IntOffset(along, 0)
                }
                .then(
                    if (isLandscape) Modifier.width(selectorCross).height(selectorSpan)
                    else Modifier.width(selectorSpan).height(selectorCross)
                )
                .clip(CircleShape)
                .graphicsLayer {
                    val p = pressState.progress.value
                    if (isLandscape) {
                        scaleX = 1f + 0.05f * p
                        scaleY = 1f + 0.14f * p
                    } else {
                        scaleX = 1f + 0.14f * p
                        scaleY = 1f + 0.05f * p
                    }
                }
                .background(selectorColor)

            if (isLandscape) {
                // The capsule is a node of its own: pressing it scales glass,
                // highlight and labels together — the same answer the portrait
                // bar gives — while the circles overhead stay exactly put.
                Box(
                    modifier = Modifier
                        .offset(y = actionStack)
                        .width(barThickness)
                        .height(barLength)
                        .graphicsLayer {
                            val s = 1f + 0.05f * pressState.progress.value
                            scaleX = s
                            scaleY = s
                        }
                        .clip(capsuleShape)
                        .then(glassSurface),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(highlight)
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = barPadding),
                        verticalArrangement = Arrangement.Center
                    ) {
                        bottomNavItems.forEachIndexed { index, screen ->
                            BarTab(
                                icon = screen.icon,
                                label = stringResource(screen.labelRes),
                                selected = index == activeIndex,
                                tint = if (index == activeIndex) selectedTint else unselectedTint,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onClick = { goToTab(index) }
                            )
                        }
                    }
                }
            } else {
                Box(highlight)
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = barPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    bottomNavItems.forEachIndexed { index, screen ->
                        BarTab(
                            icon = screen.icon,
                            label = stringResource(screen.labelRes),
                            selected = index == activeIndex,
                            tint = if (index == activeIndex) selectedTint else unselectedTint,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { goToTab(index) }
                        )
                    }
                }
            }

            // Landscape only: the three home actions ride above the rail as
            // their own discs of glass (same material, same rim, same tint).
            // Each one scales on its own press, so a tab press never moves
            // them and a circle press never touches the capsule.
            if (isLandscape) {
                val circleTint = AppColors.buttonTextColor()
                val accentTint = MaterialTheme.colorScheme.primary

                // Resolve the dialog strings here — where the hot language
                // switch provably re-resolves — and pass them in as plain
                // values; a Dialog captures its locals when it opens.
                val clearTitle = stringResource(R.string.clear_confirm_title)
                val clearMessage = stringResource(R.string.clear_confirm_message)
                val confirmLabel = stringResource(R.string.confirm_clear)
                val cancelLabel = stringResource(R.string.cancel)
                var showClearDialog by remember { mutableStateOf(false) }

                if (showClearDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        title = { Text(clearTitle) },
                        text = { Text(clearMessage) },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearDialog = false
                                viewModel.clearWaveform()
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

                RailActionButton(
                    surface = connectCircleSurface,
                    disabledSurface = disabledCircleSurface,
                    disabledTint = disabledContentTint,
                    icon = if (isConnected) Icons.Default.LinkOff else Icons.Default.Usb,
                    contentDescription = stringResource(
                        if (isConnected) R.string.disconnect else R.string.connect
                    ),
                    enabled = true,
                    tint = if (isConnected) accentTint else circleTint,
                    modifier = Modifier.offset(y = 0.dp),
                    onClick = { if (isConnected) viewModel.disconnect() else viewModel.connect() }
                )
                RailActionButton(
                    surface = circleSurface,
                    disabledSurface = disabledCircleSurface,
                    disabledTint = disabledContentTint,
                    icon = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.clear),
                    enabled = !isRecording,
                    tint = circleTint,
                    modifier = Modifier.offset(y = RailThickness + RailActionGap),
                    onClick = { showClearDialog = true }
                )
                RailActionButton(
                    surface = circleSurface,
                    disabledSurface = disabledCircleSurface,
                    disabledTint = disabledContentTint,
                    icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = stringResource(
                        if (isRecording) R.string.stop else R.string.record
                    ),
                    enabled = isConnected,
                    tint = if (isRecording) accentTint else circleTint,
                    modifier = Modifier.offset(
                        y = (RailThickness + RailActionGap) * 2
                    ),
                    onClick = { viewModel.toggleRecording() }
                )
            }
        }

        // Release notes page. Last child on purpose: it covers the pager and
        // the floating bar, so the detail page owns the whole window.
        // Composed only while it is open, with no transition of its own: the
        // page pushes itself in from the right edge and reports back once it
        // has finished sliding out, so opening and the predictive back gesture
        // are one continuous movement rather than two different animations.
        ChangelogHost(
            visible = showChangelog,
            warmWhenAboutShown = pagerState.currentPage == bottomNavItems.lastIndex,
            onClose = { showChangelog.value = false }
        )
    }
}

/**
 * Mounts the release notes page. Its own composable so that flipping the
 * visibility flag invalidates this and nothing else — see the note where the
 * state is created.
 *
 * The page is also composed while the About tab is on screen (a beat after the
 * tab change settles) even though it is closed. Laying its cards out costs
 * ~60ms on this device, and paying that on the tap means the first frame of the
 * opening animation is a stall — which reads as "slow to open" no matter how
 * short the animation is. Composed ahead of time, the tap only has to start the
 * motion. While closed the page sits translated off the right edge and its back
 * handler is disabled, so it neither draws over nor intercepts anything.
 */
@Composable
private fun ChangelogHost(
    visible: State<Boolean>,
    warmWhenAboutShown: Boolean,
    onClose: () -> Unit
) {
    var warmed by remember { mutableStateOf(false) }
    LaunchedEffect(warmWhenAboutShown) {
        if (warmWhenAboutShown) {
            // Let the tab's own transition finish first, otherwise the cost
            // lands as a dropped frame in the page swipe.
            delay(400)
            warmed = true
        } else {
            warmed = false
        }
    }

    if (warmed || visible.value) {
        ChangelogScreen(visible = visible.value, onBack = onClose)
    }
}

/**
 * One action circle hanging above the landscape rail. The disc is its own
 * piece of glass ([surface]) rather than part of the capsule's outline, so a
 * press scales the whole piece — glass, halo and icon — without the capsule
 * noticing; the same separation keeps a tab press from moving the circles.
 */
@Composable
private fun RailActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color,
    surface: Modifier,
    disabledSurface: Modifier,
    disabledTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The disc already answers with glass and a halo; without this it is the
    // only pressable thing in the app that answers in silence, while the rail
    // beside it ticks on every tab it scrolls past.
    val haptic = LocalHapticFeedback.current
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "railActionPress"
    )
    // The halo must actually read on a 60dp disc: the slab's 0.04 peak is
    // spread over a 90dp pool, but here the same energy lands in a much
    // smaller circle, so it needs a stronger peak to feel like the capsule's
    // press glow rather than a rendering accident.
    val haloPeak = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.26f else 0.18f

    Box(
        modifier = modifier
            .size(RailThickness)
            // The scale wraps the glass too: the disc grows as one piece,
            // exactly like the capsule does on a tab press. A 56dp disc needs
            // a larger fraction than the 260dp capsule to travel a comparable
            // distance at its rim (3.4dp a side here, 6.5dp there).
            .graphicsLayer {
                val scale = 1f + 0.12f * progress
                scaleX = scale
                scaleY = scale
            }
            // A disabled action goes grey, exactly like the portrait bar's
            // filled buttons: the teal one is "live", the grey one is inert.
            .then(if (enabled) surface else disabledSurface)
            // The halo pools inside this circle only: unclipped it reaches
            // 1.5x the diameter and washes over the capsule across the gap,
            // which read as the capsule reacting to a circle press.
            .clip(CircleShape)
            .drawWithContent {
                if (progress > 0.001f) {
                    // Same two passes as the capsule: the whole disc lifts a
                    // touch (Plus on pale glass barely moves, so it stays
                    // tinted), then a core of light pools in the middle with a
                    // longer falloff towards the rim — a full-strength wash to
                    // the edge just bleached the disc white.
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
                enabled = enabled,
                role = Role.Button,
                // Disabled discs keep their silence too: `clickable` never
                // calls through when enabled is false, so a greyed-out record
                // button cannot buzz for an action it refuses to take.
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else disabledTint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * One tab of the floating bar. Shared by both bar orientations so the icon /
 * label pairing (and its semantics) can't drift between the pill and the rail;
 * the caller decides how the cell takes up its share of the length.
 */
@Composable
private fun BarTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier.semantics {
            role = Role.Tab
            this.selected = selected
            contentDescription = label
            this.onClick(label) {
                onClick()
                true
            }
        },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}
