package com.tvremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A remote-control button: fires on touch-down with a haptic tick (feels like a physical remote),
 * and optionally repeats while held (d-pad, volume, channel).
 */
@Composable
fun RemoteButton(
    onPress: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    repeat: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(onPress)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) lerp(color, Color.White, 0.18f) else color)
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    action()
                    val repeater = if (repeat) scope.launch {
                        delay(450)
                        while (isActive) {
                            action()
                            delay(110)
                        }
                    } else null
                    tryAwaitRelease()
                    repeater?.cancel()
                    pressed = false
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { action(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}
