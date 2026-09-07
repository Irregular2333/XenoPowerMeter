package com.irregular.xenopowermeter.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.ui.theme.AppColors

private val dimColorMatrix = ColorMatrix(
    floatArrayOf(
        0.8f, 0f, 0f, 0f, 0f,
        0f, 0.8f, 0f, 0f, 0f,
        0f, 0f, 0.8f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current
    val cardColor = AppColors.cardColor()
    val labelColor = AppColors.labelColor()
    val dividerColor = AppColors.dividerColor()

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp

    if (isLandscape) {
        LandscapeAboutScreen(uriHandler, cardColor, labelColor, dividerColor)
    } else {
        PortraitAboutScreen(uriHandler, cardColor, labelColor, dividerColor)
    }
}

@Composable
private fun PortraitAboutScreen(
    uriHandler: androidx.compose.ui.platform.UriHandler,
    cardColor: Color,
    labelColor: Color,
    dividerColor: Color
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val shortSide = minOf(screenWidthDp, screenHeightDp)
    val logoSizeDp = (shortSide * 0.3f).dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.about_header_portrait),
                contentDescription = "About Header",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(dimColorMatrix)
            )
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "App Icon",
                modifier = Modifier.size(logoSizeDp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "XenoPowerMeter",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            InfoCard(cardColor, labelColor, dividerColor, uriHandler)
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun LandscapeAboutScreen(
    uriHandler: androidx.compose.ui.platform.UriHandler,
    cardColor: Color,
    labelColor: Color,
    dividerColor: Color
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val longSide = maxOf(screenWidthDp, screenHeightDp)
    val logoSizeDp = (longSide * 0.2f).dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.about_header),
                contentDescription = "About Header",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(dimColorMatrix)
            )
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "App Icon",
                modifier = Modifier.size(logoSizeDp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "XenoPowerMeter",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            InfoCard(cardColor, labelColor, dividerColor, uriHandler)
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun InfoCard(
    cardColor: Color,
    labelColor: Color,
    dividerColor: Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VERSION", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text("1.4.0", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }

            Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(dividerColor))

            Column(modifier = Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BUILD DATE", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text("2026.09.07", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
            }

            Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(dividerColor))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { uriHandler.openUri("https://github.com/Irregular2333") },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("AUTHOR", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Medium)
                Text("Irregular", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFFB8C00))
            }
        }
    }
}
