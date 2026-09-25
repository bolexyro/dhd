package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TaskPointerEvent
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val CALIBRATION_ANCHORS = arrayOf(
    0.18f to 0.16f,
    0.82f to 0.16f,
    0.18f to 0.84f,
    0.82f to 0.84f,
)

internal class PointerFeedback {
    private val _pointerEvent = MutableStateFlow<TaskPointerEvent?>(null)

    val pointerEvent: StateFlow<TaskPointerEvent?> = _pointerEvent.asStateFlow()

    fun clear() {
        _pointerEvent.value = null
    }

    fun publishGesture(
        sessionId: String,
        action: PhoneAction,
        observation: ObservationSnapshot,
        clickPhase: ClickPhase,
    ) {
        val sequence = nextSequence()
        val nextEvent = when (action) {
            is TapAction -> TaskPointerEvent.Click(
                sequence = sequence,
                sessionId = sessionId,
                x = action.x,
                y = action.y,
                displayWidth = observation.width,
                displayHeight = observation.height,
                phase = clickPhase,
            )

            is SwipeAction -> TaskPointerEvent.Swipe(
                sequence = sequence,
                sessionId = sessionId,
                startX = action.startX,
                startY = action.startY,
                endX = action.endX,
                endY = action.endY,
                durationMs = action.durationMs,
                displayWidth = observation.width,
                displayHeight = observation.height,
            )

            else -> return
        }
        _pointerEvent.value = nextEvent
    }

    fun publishCalibration(
        sessionId: String,
        observation: ObservationSnapshot,
    ): TaskPointerEvent.Calibration {
        val (xRatio, yRatio) = CALIBRATION_ANCHORS[Random.nextInt(CALIBRATION_ANCHORS.size)]
        val nextEvent = TaskPointerEvent.Calibration(
            sequence = nextSequence(),
            sessionId = sessionId,
            x = (observation.width * xRatio).roundToInt().coerceIn(0, observation.width - 1),
            y = (observation.height * yRatio).roundToInt().coerceIn(0, observation.height - 1),
            displayWidth = observation.width,
            displayHeight = observation.height,
        )
        _pointerEvent.value = nextEvent
        return nextEvent
    }

    private fun nextSequence(): Long = (_pointerEvent.value?.sequence ?: 0L) + 1L
}
