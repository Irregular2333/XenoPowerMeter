package com.irregular.xenopowermeter.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.ui.theme.AppColors
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor

/**
 * Backdrop sampled by the glass dropdown popups. Provided by the settings
 * screen: a full-size layer that draws the app background color plus the
 * page content, deliberately EXCLUDING the popup layer so the glass panel
 * never samples itself.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * One glass dropdown registered by a [SettingsDropdownPreference] row. The
 * content is self-contained (it hosts its own full-screen box, like the miuix
 * demo hosts [GlassDropdownPopup] in a root box); the settings page root just
 * calls it in a layer above its backdrop-sampled content.
 */
class GlassDropdownEntry(
    val content: @Composable () -> Unit,
)

/**
 * Page-level host for glass dropdown popups. Rendering the popups here —
 * instead of through [top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout] —
 * avoids the popup host's full-screen touch interceptor, which used to keep
 * eating the first tap after a dropdown was dismissed ("must tap twice").
 */
val LocalGlassDropdownHost = staticCompositionLocalOf<SnapshotStateList<GlassDropdownEntry>?> { null }

/**
 * A dropdown preference row whose typography exactly matches the app's
 * hand-built setting rows, opening a GLASS popup (miuix main, miuix-glass).
 *
 * The stock miuix preferences render their title with a hardcoded
 * [FontWeight.Medium] and their value with a normal weight — the inverse of
 * the app's own rows (normal-weight label + medium-weight teal value). This
 * wrapper keeps the app's styles and swaps the popup for the glass one:
 * - title: 15sp normal weight, [AppColors.buttonTextColor] (the deep teal the
 *   hand-built rows use)
 * - value: 15sp medium weight, [AppColors.linkColor]
 * - menu: glass panel blurred over [LocalGlassBackdrop], items tinted with
 *   the app palette (deep teal label, teal selected + tick)
 *
 * The popup composable itself stays composed across open/close (like the
 * miuix demo): show=false just stops drawing, and its exit spring runs to
 * completion while composed. GlassPopupSurface's own scrim handles outside
 * taps while open and disappears the moment the exit settles.
 */
@Composable
fun SettingsDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    SettingsDropdownRow(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        insideMargin = insideMargin,
        enabled = enabled,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
private fun SettingsDropdownRow(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier,
    insideMargin: PaddingValues,
    enabled: Boolean,
    onSelectedIndexChange: ((Int) -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val expanded = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val currentOnSelectedIndexChange by rememberUpdatedState(onSelectedIndexChange)

    val itemsNotEmpty = items.isNotEmpty()
    val actualEnabled = enabled && itemsNotEmpty

    val labelColor = AppColors.buttonTextColor()
    val linkColor = AppColors.linkColor()
    val titleColor = if (actualEnabled) labelColor else labelColor.copy(alpha = 0.45f)
    val actionColor = if (actualEnabled) linkColor else labelColor.copy(alpha = 0.45f)

    val anchor = rememberGlassPopupAnchor()
    var rowBounds by remember { mutableStateOf(Rect.Zero) }

    val setExpanded: (Boolean) -> Unit = remember {
        { next ->
            if (expanded.value != next) {
                expanded.value = next
            }
        }
    }

    val handleClick = remember(actualEnabled) {
        {
            if (actualEnabled) {
                setExpanded(!expanded.value)
                if (expanded.value) {
                    currentHapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            }
        }
    }

    BasicComponent(
        modifier = modifier.onGloballyPositioned { rowBounds = it.boundsInWindow() },
        interactionSource = interactionSource,
        insideMargin = insideMargin,
        endActions = {
            Row(
                modifier = Modifier.glassPopupAnchorValue(anchor),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (itemsNotEmpty) {
                    val text = items.getOrNull(selectedIndex)
                    if (!text.isNullOrEmpty()) {
                        Text(
                            text = text,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .weight(1f, fill = false),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = actionColor,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
        onClick = handleClick,
        role = Role.DropdownList,
        holdDownState = expanded.value,
        enabled = actualEnabled,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = titleColor,
        )
    }

    // Register the glass popup with the page-level host. The content lambda
    // goes through rememberUpdatedState so the panel always renders with the
    // row's latest items/selection even after they change.
    val host = LocalGlassDropdownHost.current
    if (host != null) {
        val latestContent by rememberUpdatedState<@Composable () -> Unit> {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                GlassDropdownPopup(
                    show = expanded.value && actualEnabled,
                    onDismissRequest = { setExpanded(false) },
                    anchorBounds = rowBounds,
                    backdrop = LocalGlassBackdrop.current,
                    anchor = anchor,
                ) {
                    items.forEachIndexed { index, item ->
                        GlassPopupItem(
                            text = item,
                            selected = index == selectedIndex,
                            contentColor = labelColor,
                            selectedContentColor = linkColor,
                            onClick = {
                                currentHapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                setExpanded(false)
                                currentOnSelectedIndexChange?.invoke(index)
                            },
                        )
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            val entry = GlassDropdownEntry { latestContent() }
            host.add(entry)
            onDispose { host.remove(entry) }
        }
    }
}
