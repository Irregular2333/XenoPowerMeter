package com.irregular.xenopowermeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.irregular.xenopowermeter.ui.main.MainScreen
import com.irregular.xenopowermeter.ui.about.AboutScreen
import com.irregular.xenopowermeter.ui.navigation.Screen
import com.irregular.xenopowermeter.ui.navigation.bottomNavItems
import com.irregular.xenopowermeter.ui.settings.SettingsScreen
import com.irregular.xenopowermeter.ui.theme.XenoPowerMeterTheme
import com.irregular.xenopowermeter.viewmodel.WaveformViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            XenoPowerMeterTheme {
                XenoPowerApp()
            }
        }
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
fun XenoPowerApp() {
    val viewModel: WaveformViewModel = viewModel()
    val navController = rememberNavController()

    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val bottomBarHeight = 56.dp

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Main.route,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(bottom = bottomBarHeight + 28.dp),
            enterTransition = {
                val initialIndex = bottomNavItems.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = bottomNavItems.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) + fadeIn(tween(300))
                } else {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) + fadeIn(tween(300))
                }
            },
            exitTransition = {
                val initialIndex = bottomNavItems.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = bottomNavItems.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) + fadeOut(tween(300))
                } else {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) + fadeOut(tween(300))
                }
            }
        ) {
            composable(Screen.Main.route) {
                MainScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(Screen.About.route) {
                AboutScreen()
            }
        }

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

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
                    onDrawSurface = { drawRect(Color(0xFFD0EEF0)) }
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bottomNavItems.forEach { screen ->
                val selected = currentRoute == screen.route
                val tint = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF6B9DA0)

                val animationScope = rememberCoroutineScope()
                val progressAnimation = remember { Animatable(0f) }

                Box(
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null) {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
                        contentDescription = screen.title,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
