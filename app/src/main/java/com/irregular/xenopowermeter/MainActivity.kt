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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
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
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.irregular.xenopowermeter.ui.BarPressState
import com.irregular.xenopowermeter.ui.BarRefractionShader
import com.irregular.xenopowermeter.ui.BottomBarInset
import com.irregular.xenopowermeter.ui.BottomBarHeight
import com.irregular.xenopowermeter.ui.BottomBarLift
import com.irregular.xenopowermeter.ui.PageEndGutter
import com.irregular.xenopowermeter.ui.RailInset
import com.irregular.xenopowermeter.ui.RailLength
import com.irregular.xenopowermeter.ui.RailLift
import com.irregular.xenopowermeter.ui.RailPageStart
import com.irregular.xenopowermeter.ui.RailThickness
import com.irregular.xenopowermeter.ui.main.MainScreen
import com.irregular.xenopowermeter.ui.about.AboutScreen
import com.irregular.xenopowermeter.ui.navigation.bottomNavItems
import com.irregular.xenopowermeter.ui.settings.SettingsScreen
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.irregular.xenopowermeter.ui.theme.XenoPowerMeterTheme
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A whisper of a rim. The library default is a hard 0.5 dp hairline at full
 * layer alpha, which reads as a drawn white stroke. Widened and softened, then
 * dialled back, so it only catches along the 45° top-left arc — just enough to
 * lift the pill off the page without becoming an outline.
 * Top-level so the lambda keeps a stable identity across recompositions.
 */
private val BottomBarHighlight: () -> Highlight? = {
    Highlight(
        width = 0.9.dp,
        blurRadius = 2.2.dp,
        alpha = 0.32f,
        style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.32f))
    )
}

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
                else -> AboutScreen()
            }
        }

        // The pager follows the bar: portrait flips pages sideways along the
        // bottom pill, landscape flips them vertically alongside the rail —
        // the swipe axis matches the direction the navigation sits in.
        val pagerModifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
        if (isLandscape) {
            VerticalPager(
                state = pagerState,
                // Keep the adjacent page composed so sliding to it never pays a
                // first-composition hitch.
                beyondViewportPageCount = 1,
                modifier = pagerModifier,
                pageContent = pageContent
            )
        } else {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = pagerModifier,
                pageContent = pageContent
            )
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
        val unselectedIconTint = AppColors.buttonTextColor()

        // Full pill: the radius is half the height, so the caps stay round at
        // any bar height instead of flattening into a rounded rectangle.
        val barShape = RoundedCornerShape(bottomBarHeight / 2)
        // Light blur on purpose. Thickness comes from the refraction below:
        // bending what is behind the rim is what reads as a slab of glass,
        // while a heavy blur only reads as frosted plastic and swallows the
        // page underneath. Mirror keeps the rim from sampling a clamped row.
        val glassEffects: BackdropEffectScope.() -> Unit =
            remember(blurSupported, refractSupported) {
                {
                    if (blurSupported) {
                        vibrancy()
                        blur(6f.dp.toPx(), TileMode.Mirror)
                        // Mirror inflates padding to the radius, but that ring
                        // records nothing except off-screen pixels — the
                        // refraction shader folds its samples back into the
                        // node on its own, so the ring buys nothing.
                        padding = 0f
                        if (refractSupported) {
                            runtimeShaderEffect(
                                key = "BottomBarRefraction",
                                shaderString = BarRefractionShader,
                                uniformShaderName = "content"
                            ) {
                                setFloatUniform("rectSize", size.width, size.height)
                                setFloatUniform("cornerRadius", (bottomBarHeight / 2).toPx())
                                setFloatUniform("refractionHeight", 6f.dp.toPx())
                                setFloatUniform("refractionAmount", -32f.dp.toPx())
                            }
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
        val barSurface = remember(backdrop, barSurfaceColor, glassEffects, haloPeak) {
            Modifier
                .clip(barShape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = glassEffects,
                    // Both are non-null defaults in the library, so opting out
                    // has to be explicit — but only the shadow stays off; the
                    // rim is back in a softened form.
                    highlight = BottomBarHighlight,
                    shadow = null,
                    innerShadow = null,
                    onDrawSurface = { drawRect(barSurfaceColor) },
                    onDrawFront = {
                        val progress = pressState.progress.value
                        if (progress > 0.001f) {
                            // The whole slab brightens a touch...
                            drawRect(
                                Color.White.copy(alpha = 0.03f * progress),
                                blendMode = BlendMode.Plus
                            )
                            // ...and a soft disc sits under the finger. Solid
                            // out to half its radius, then a long falloff, so
                            // it reads as light pooling in the glass rather
                            // than a dot painted on top.
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
                    } else {
                        Modifier
                            .padding(bottom = BottomBarLift)
                            .windowInsetsPadding(BottomBarInset)
                    }
                )
                .then(
                    if (isLandscape) Modifier.width(barThickness).height(barLength)
                    else Modifier.width(barLength).height(barThickness)
                )
                .align(if (isLandscape) Alignment.CenterStart else Alignment.BottomCenter)
                .graphicsLayer {
                    // Grows into the press instead of shrinking: this is a
                    // piece of glass being pushed, not a button being squashed.
                    // 16dp over the bar's longer side keeps the growth
                    // proportional whether it lies flat or stands up.
                    val scale = 1f + 16.dp.toPx() / size.maxDimension * pressState.progress.value
                    scaleX = scale
                    scaleY = scale
                }
                // One gesture for the whole bar: a tap picks the tab under the
                // finger, a drag along it carries the highlight 1:1 and lets go
                // on the nearest tab. Press state lives here too, so the halo
                // follows a drag the same way it follows a press. The axis
                // follows the bar: x when it lies flat, y when it stands up.
                .pointerInput(tabCount, tabSpanPx, isLandscape) {
                    val slop = viewConfiguration.touchSlop
                    val padPx = barPadding.toPx()
                    fun alongBar(position: Offset) =
                        if (isLandscape) position.y else position.x
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
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
                        goToTab(target)
                        barHolds -= 1
                    }
                }
                .then(barSurface),
            // Lying flat, the highlight's base position is the vertical centre
            // and the offset moves it sideways. Standing up it is the other way
            // round: base at the top, offset down the rail.
            contentAlignment = if (isLandscape) Alignment.TopCenter else Alignment.CenterStart
        ) {
            // Sliding highlight, between the glass and the icons so the blur
            // still reads behind it. The offset is read in placement, not in
            // composition, so a drag doesn't recompose the tabs every frame.
            Box(
                modifier = Modifier
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
                    .background(selectorColor)
            )

            // The active tab carries the accent; the rest are held back so
            // the highlight isn't the only thing telling them apart.
            val selectedTint = MaterialTheme.colorScheme.primary
            val unselectedTint = unselectedIconTint.copy(alpha = 0.78f)

            if (isLandscape) {
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
            } else {
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
        }
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
