package com.irregular.xenopowermeter.ui.about

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutScreen() {
    val commonGap = 8.dp 
    val uriHandler = LocalUriHandler.current

    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Image
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.about_header),
                    contentDescription = "About Header",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
                Image(
                    painter = painterResource(id = R.drawable.app_icon),
                    contentDescription = "App Icon",
                    modifier = Modifier.size(150.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(commonGap))

            // App Info Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "XenoPowerMeter",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(commonGap))

                // Info Panel
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F5F6)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Version
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("VERSION", fontSize = 10.sp, color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                            Text("1.2.0", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                        
                        Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(Color(0xFFE0E0E0)))
                        
                        // Build Date
                        Column(modifier = Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("BUILD DATE", fontSize = 10.sp, color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                            Text("2026.09.06", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                        }
                        
                        Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(Color(0xFFE0E0E0)))
                        
                        // Author
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { uriHandler.openUri("https://github.com/Irregular2333") },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("AUTHOR", fontSize = 10.sp, color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                            Text("Irregular", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFFB8C00))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(commonGap))
            }
        }
    }
}
