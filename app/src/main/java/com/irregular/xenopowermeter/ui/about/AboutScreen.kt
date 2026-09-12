package com.irregular.xenopowermeter.ui.about

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
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
import com.irregular.xenopowermeter.ui.theme.AppColors

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
    val backgroundColor = MaterialTheme.colorScheme.background

    // The bottom fade dissolves away (200ms) once the list rests at the very
    // bottom — nothing left to wash there — and returns on scrolling up.
    val bottomObscured by remember {
        derivedStateOf { scrollState.value < scrollState.maxValue }
    }
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (bottomObscured) 1f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    if (isLandscape) {
        LandscapeAboutLayout(
            scrollState = scrollState,
            bottomFadeAlpha = bottomFadeAlpha,
            backgroundColor = backgroundColor,
            darkTheme = darkTheme,
            context = context,
            screenHeightDp = screenHeightDp
        )
    } else {
        PortraitAboutLayout(
            scrollState = scrollState,
            bottomFadeAlpha = bottomFadeAlpha,
            backgroundColor = backgroundColor,
            darkTheme = darkTheme,
            context = context,
            screenHeightDp = screenHeightDp
        )
    }
}

@Composable
private fun PortraitAboutLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    bottomFadeAlpha: Float,
    backgroundColor: Color,
    darkTheme: Boolean,
    context: android.content.Context,
    screenHeightDp: Int
) {
    val imageHeightDp = (screenHeightDp * 0.3f).dp
    // Lets the last section rest 8dp above the floating bar at full scroll,
    // matching the Settings page (About's viewport has no bottom padding).
    val bottomRestSpace = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding() + 68.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            AboutHeaderImage(imageHeightDp)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                VersionInfoSection()

                Spacer(modifier = Modifier.height(16.dp))

                AuthorSection(context)

                Spacer(modifier = Modifier.height(16.dp))

                SpecialThanksSection(context)
            }

            Spacer(modifier = Modifier.height(bottomRestSpace))
        }

        FadeGradient(bottomFadeAlpha, backgroundColor)
    }
}

@Composable
private fun LandscapeAboutLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    bottomFadeAlpha: Float,
    backgroundColor: Color,
    darkTheme: Boolean,
    context: android.content.Context,
    screenHeightDp: Int
) {
    val imageHeightDp = (screenHeightDp * 0.3f).dp
    // Lets the last section rest 8dp above the floating bar at full scroll,
    // matching the Settings page (About's viewport has no bottom padding).
    val bottomRestSpace = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding() + 68.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            AboutHeaderImage(imageHeightDp)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(bottomRestSpace))
        }

        FadeGradient(bottomFadeAlpha, backgroundColor)
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
private fun AboutHeaderImage(imageHeightDp: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
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

/**
 * The bottom fade covers the whole floating bar and spills 0.5 × a above it,
 * where a = distance from the bar's vertical center to the screen bottom
 * (nav inset + 4dp gap + half of the 56dp bar) — so the wash visibly climbs
 * over the content above the bar.
 */
private val BottomFadeSpillFactor = 0.5f
private val BottomFadeFixedDistance = 32.dp

@Composable
private fun BoxScope.FadeGradient(bottomFadeAlpha: Float, backgroundColor: Color) {
    if (bottomFadeAlpha > 0f) {
        // Fade = full bar zone + spill above it: nav inset + (4dp gap + 56dp
        // bar) + 0.5 × a, a = bar vertical center → screen bottom. Identical
        // to the Settings page's bottom fade; dissolves away once the list
        // rests at the very bottom.
        val navBottomInset = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val fadeHeight = navBottomInset + 60.dp +
            (navBottomInset + BottomFadeFixedDistance) * BottomFadeSpillFactor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
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
}

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
