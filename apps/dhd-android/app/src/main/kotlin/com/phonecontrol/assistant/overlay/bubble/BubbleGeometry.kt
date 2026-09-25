package com.phonecontrol.assistant.overlay.bubble

import com.phonecontrol.assistant.overlay.OverlaySwipeDirection

internal fun bubblePositionForHorizontalSwipe(
    direction: OverlaySwipeDirection,
    currentPosition: BubblePosition,
    displayWidth: Int,
    displayHeight: Int,
    bubbleWidth: Int,
    bubbleHeight: Int,
    topInset: Int = 0,
    bottomInset: Int = 0,
): BubblePosition {
    val edgeX = when (direction) {
        OverlaySwipeDirection.LEFT -> 12
        OverlaySwipeDirection.RIGHT -> displayWidth - bubbleWidth - 12
    }
    return clampBubblePosition(
        x = edgeX,
        y = currentPosition.y,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        bubbleWidth = bubbleWidth,
        bubbleHeight = bubbleHeight,
        topInset = topInset,
        bottomInset = bottomInset,
    )
}

internal fun bubblePositionOnNearestEdge(
    currentPosition: BubblePosition,
    displayWidth: Int,
    displayHeight: Int,
    bubbleWidth: Int,
    bubbleHeight: Int,
    topInset: Int = 0,
    bottomInset: Int = 0,
    margin: Int = 12,
): BubblePosition {
    val clamped = clampBubblePosition(
        x = currentPosition.x,
        y = currentPosition.y,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        bubbleWidth = bubbleWidth,
        bubbleHeight = bubbleHeight,
        topInset = topInset,
        bottomInset = bottomInset,
        margin = margin,
    )
    val rightX = (displayWidth - bubbleWidth - margin).coerceAtLeast(margin)
    val edgeX = if (clamped.x + bubbleWidth / 2 <= displayWidth / 2) margin else rightX
    return BubblePosition(edgeX, clamped.y)
}

data class BubblePosition(val x: Int, val y: Int)

fun clampBubblePosition(
    x: Int,
    y: Int,
    displayWidth: Int,
    displayHeight: Int,
    bubbleWidth: Int,
    bubbleHeight: Int,
    topInset: Int = 0,
    bottomInset: Int = 0,
    margin: Int = 12,
): BubblePosition {
    val maxX = (displayWidth - bubbleWidth - margin).coerceAtLeast(margin)
    val minY = (topInset + margin).coerceAtMost(displayHeight - bubbleHeight - margin)
    val maxY = (displayHeight - bubbleHeight - bottomInset - margin).coerceAtLeast(minY)
    return BubblePosition(
        x = x.coerceIn(margin, maxX),
        y = y.coerceIn(minY, maxY),
    )
}
