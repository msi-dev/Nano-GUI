package com.msi.nanogui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object MsiAnimations {
    val msi_fadein: EnterTransition = fadeIn(animationSpec = tween(300, easing = LinearOutSlowInEasing))
    val msi_fadeout: ExitTransition = fadeOut(animationSpec = tween(300, easing = FastOutLinearInEasing))

    val msi_slideup: EnterTransition = slideInVertically(
        initialOffsetY = { it },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeIn()

    val msi_slidedown: ExitTransition = slideOutVertically(
        targetOffsetY = { it },
        animationSpec = tween(300, easing = FastOutLinearInEasing)
    ) + fadeOut()

    val msi_slideleft: EnterTransition = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeIn()

    val msi_slideright: EnterTransition = slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeIn()

    // Smooth scroll utility for standard Compose ScrollState
    fun msi_scrolling(scope: CoroutineScope, scrollState: ScrollState, targetValue: Int) {
        scope.launch {
            scrollState.animateScrollTo(targetValue, animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing))
        }
    }

    // Smooth scroll utility for LazyListState
    fun msi_scrolling(scope: CoroutineScope, listState: LazyListState, index: Int, offset: Int = 0) {
        scope.launch {
            listState.animateScrollToItem(index, offset)
        }
    }
}

// Custom infinite rotating modifier for loading symbols or icons
fun Modifier.msi_rotation(durationMillis: Int = 1000): Modifier = this.composed {
    val transition = rememberInfiniteTransition(label = "rotation")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )
    this.rotate(angle)
}
