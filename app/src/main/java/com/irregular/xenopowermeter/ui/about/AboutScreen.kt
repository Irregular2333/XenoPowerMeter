package com.irregular.xenopowermeter.ui.about

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.irregular.xenopowermeter.AppSettings
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.ui.BottomBarTopEdge
import com.irregular.xenopowermeter.ui.PageEndGutter
import com.irregular.xenopowermeter.ui.ProgressiveBottomBlur
import com.irregular.xenopowermeter.ui.ProgressiveTopBlur
import com.irregular.xenopowermeter.ui.RailPageStart
import com.irregular.xenopowermeter.ui.theme.AppColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private val dimColorMatrix = ColorMatrix(
    floatArrayOf(
        0.55f, 0f, 0f, 0f, 0f,
        0f, 0.55f, 0f, 0f, 0f,
        0f, 0f, 0.55f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun AboutScreen() {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isLandscape = screenWidthDp > screenHeightDp
    val view = LocalView.current
    val context = LocalContext.current

    val darkTheme = when (AppSettings.colorMode) {
        AppSettings.ColorMode.SYSTEM -> isSystemInDarkTheme()
        AppSettings.ColorMode.LIGHT -> false
        AppSettings.ColorMode.DARK -> true
    }

    LaunchedEffect(Unit) {
        val window = (view.context as Activity).window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val scrollState = rememberScrollState()
    val imageHeightDp = (screenHeightDp * 0.3f).dp

    // The band dissolves away once the list rests at the very top (the first
    // section then starts well below the image with nothing to dissolve), and
    // returns as soon as content slides underneath the pinned image.
    val topObscured by remember {
        derivedStateOf { scrollState.value > 0 }
    }
    val topFadeAlpha by animateFloatAsState(
        targetValue = if (topObscured) 1f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    if (isLandscape) {
        LandscapeAboutLayout(
            scrollState = scrollState,
            topFadeAlpha = topFadeAlpha,
            imageHeightDp = imageHeightDp,
            context = context
        )
    } else {
        PortraitAboutLayout(
            scrollState = scrollState,
            topFadeAlpha = topFadeAlpha,
            imageHeightDp = imageHeightDp,
            context = context
        )
    }
}

/**
 * Shared chrome for both orientations: the scrolling column, the two blur
 * bands and the pinned header image.
 *
 * The page's range is not the screen's: the top [imageHeightDp] belongs to the
 * pinned photo, which is drawn *over* the column. So the top band is anchored
 * to the image's bottom edge rather than to y = 0 — anchored at the screen top
 * it would only blur the photo and leave the content underneath it untouched.
 */
@Composable
private fun AboutPage(
    scrollState: androidx.compose.foundation.ScrollState,
    topFadeAlpha: Float,
    imageHeightDp: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    // Snapshot of the scrolling column alone (the image is not part of it), so
    // the bands sample page content and never the photo they sit next to.
    val pageBackdrop = rememberLayerBackdrop()

    // Landscape puts the bar on the start edge, so the cards clear it
    // sideways and the bottom is free again. Same shared start edge as Main
    // and Settings.
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val contentStart = if (isLandscape) RailPageStart else 16.dp

    // Same rest space as Settings: the last card stops right at the band's top
    // edge instead of sinking into it and staying blurred at full scroll.
    val bottomRestSpace = if (isLandscape) 16.dp else BottomBarTopEdge + 8.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
                .verticalScroll(scrollState)
        ) {
            // Content starts below the pinned header image and slides under
            // it when scrolling up.
            Spacer(modifier = Modifier.height(imageHeightDp + 16.dp))

            Column(
                modifier = Modifier.padding(start = contentStart, end = PageEndGutter),
                content = content
            )

            Spacer(modifier = Modifier.height(bottomRestSpace))
        }

        // Same band as the Settings page, for the floating bar that hangs over
        // this page too. With the bar on the side there is nothing to dissolve
        // into down here.
        if (!isLandscape) {
            ProgressiveBottomBlur(
                backdrop = pageBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
                bandHeight = BottomBarTopEdge + 10.dp,
                maxBlur = 20.dp
            )
        }

        // Hung off the image's bottom edge, at the same strength: content
        // dissolves into the photo instead of colliding with its hard edge.
        //
        // 76dp, not the Settings band's ~92dp: that one has the status bar's
        // dead zone folded into it, this one starts at a real edge, and a
        // ramp that long would smear the two rows that still matter on a page
        // whose whole travel is about 44dp.
        ProgressiveTopBlur(
            backdrop = pageBackdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = imageHeightDp)
                .graphicsLayer { alpha = topFadeAlpha },
            bandHeight = 76.dp,
            maxBlur = 20.dp
        )

        // In landscape the photo joins the content grid: it starts on the same
        // x as the cards and keeps the same trailing gutter, with the cards'
        // rounded corner, so the header reads as the first card of the column
        // instead of a full-bleed strip pinned to the screen's left edge.
        // Portrait stays edge-to-edge.
        AboutHeaderImage(
            imageHeightDp,
            if (isLandscape) {
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = contentStart, end = PageEndGutter)
            } else {
                Modifier.align(Alignment.TopCenter)
            }
        )
    }
}

@Composable
private fun PortraitAboutLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    topFadeAlpha: Float,
    imageHeightDp: androidx.compose.ui.unit.Dp,
    context: android.content.Context
) {
    AboutPage(scrollState, topFadeAlpha, imageHeightDp) {
        VersionInfoSection()

        Spacer(modifier = Modifier.height(16.dp))

        AuthorSection(context)

        Spacer(modifier = Modifier.height(16.dp))

        SpecialThanksSection(context)
    }
}

@Composable
private fun LandscapeAboutLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    topFadeAlpha: Float,
    imageHeightDp: androidx.compose.ui.unit.Dp,
    context: android.content.Context
) {
    AboutPage(scrollState, topFadeAlpha, imageHeightDp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DisplaySectionTitle(stringResource(R.string.version_info))
                VersionInfoCard()

                DisplaySectionTitle(stringResource(R.string.author_label))
                AuthorCard(context)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DisplaySectionTitle(stringResource(R.string.special_thanks))
                SpecialThanksCard(context)
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
private fun AboutHeaderImage(imageHeightDp: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    // Pinned at the top of the page (not part of the scroll column): the
    // status bar sits over the image, and content slides underneath it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(imageHeightDp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.about_background),
            contentDescription = "About Background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(dimColorMatrix)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Image(
                painter = painterResource(id = R.drawable.about_app_icon),
                contentDescription = "App Icon",
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "XenoPowerMeter",
                    color = AppColors.linkColor(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.about_description),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Start,
                    lineHeight = 16.sp,
                    modifier = Modifier.widthIn(max = 240.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun VersionInfoSection() {
    Text(
        text = stringResource(R.string.version_info),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(8.dp))
    VersionInfoCard()
}

@Composable
private fun VersionInfoCard() {
    val labelColor = AppColors.buttonTextColor()
    val valueColor = AppColors.linkColor()
    val cardColor = AppColors.cardColor()
    val context = LocalContext.current
    var showChangelog by remember { mutableStateOf(false) }

    // Version comes from the installed manifest; the build date is baked
    // into the APK as a BuildConfig field at compile time (the toolchain
    // normalizes zip entry timestamps, so those can't be trusted).
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (e: Exception) {
            "--"
        }
    }
    val buildDate = remember { com.irregular.xenopowermeter.BuildConfig.BUILD_DATE }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.version_number), fontSize = 15.sp, color = labelColor)
                Text(versionName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.build_date), fontSize = 15.sp, color = labelColor)
                Text(buildDate, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable { showChangelog = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.changelog), fontSize = 15.sp, color = labelColor)
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = valueColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showChangelog) {
        // Resolve the strings here in the page composition — where the hot
        // language switch provably re-resolves — and pass them in as plain
        // values. A Dialog captures its locals when it opens, so
        // stringResource inside the dialog can lag a language switch.
        val title = stringResource(R.string.changelog_title)
        val body = stringResource(R.string.changelog_content)
        val closeLabel = stringResource(R.string.close)
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            title = {
                Text(text = title, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = body)
            },
            confirmButton = {
                TextButton(onClick = { showChangelog = false }) {
                    Text(closeLabel)
                }
            }
        )
    }
}

@Composable
private fun AuthorSection(context: android.content.Context) {
    Text(
        text = stringResource(R.string.author_label),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(8.dp))
    AuthorCard(context)
}

@Composable
private fun AuthorCard(context: android.content.Context) {
    val labelColor = AppColors.buttonTextColor()
    val valueColor = AppColors.linkColor()
    val cardColor = AppColors.cardColor()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.author_label), fontSize = 15.sp, color = labelColor)
                Text("Irregular", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Irregular2333/XenoPowerMeter"))
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.github), fontSize = 15.sp, color = labelColor)
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = valueColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SpecialThanksSection(context: android.content.Context) {
    Text(
        text = stringResource(R.string.special_thanks),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(8.dp))
    SpecialThanksCard(context)
}

@Composable
private fun SpecialThanksCard(context: android.content.Context) {
    val labelColor = AppColors.buttonTextColor()
    val valueColor = AppColors.linkColor()
    val cardColor = AppColors.cardColor()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nintendo.com/zh-hans-hk/games/switch2/bas6a/index.html"))
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.game), fontSize = 15.sp, color = labelColor)
                Text(stringResource(R.string.game_xenoblade), fontSize = 15.sp, color = valueColor)
            }
            ThanksRow(label = stringResource(R.string.github), name = stringResource(R.string.github_power_pico), url = "https://github.com/No-Chicken/Power-Pico", labelColor = labelColor, valueColor = valueColor)
            ThanksRow(label = stringResource(R.string.github), name = stringResource(R.string.github_nexio_schedule), url = "https://github.com/HaoZai000/NexioSchedule", labelColor = labelColor, valueColor = valueColor)
            ThanksRow(label = stringResource(R.string.github), name = stringResource(R.string.github_liquid_glass), url = "https://github.com/Kyant0/AndroidLiquidGlass", labelColor = labelColor, valueColor = valueColor)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.and_also), fontSize = 15.sp, color = labelColor)
                Text(stringResource(R.string.jrpg_thanks), fontSize = 15.sp, color = valueColor)
            }
        }
    }
}

// The old background-coloured ramp that used to sit below the image is gone:
// the top band now blurs the content itself, so a solid wash on top of it
// would only hide what the blur is there to show.
@Composable
private fun ThanksRow(
    label: String,
    name: String,
    url: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = labelColor)
        Text(name, fontSize = 15.sp, color = valueColor)
    }
}
