package com.phonecontrol.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.overlay.bubble.BubblePosition
import com.phonecontrol.assistant.overlay.bubble.bubblePositionForHorizontalSwipe
import com.phonecontrol.assistant.overlay.bubble.bubblePositionOnNearestEdge
import com.phonecontrol.assistant.overlay.bubble.clampBubblePosition
import com.phonecontrol.assistant.overlay.effects.OverlayGlow
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.SessionCommands
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.navigation.AppRoutes
import com.phonecontrol.assistant.ui.theme.DarkAssistantColors
import com.phonecontrol.assistant.ui.theme.LightAssistantColors
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.ThemeMode
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the two WindowManager surfaces used by the DHD overlay. */
class OverlayWindowController(
    context: Context,
    private val coordinator: SessionCoordinator,
    private val visibilityGate: OverlayVisibilityGate,
    private val developerStatus: StateFlow<DeveloperModeStatus>,
    private val companionConnected: StateFlow<Boolean>,
    private val taskPreviewState: StateFlow<TaskPreviewState>,
    private val taskDisplaySession: StateFlow<TaskDisplaySession?>,
    private val onTaskPreviewSurfaceAvailable: (TaskDisplaySession, Surface) -> Unit,
    private val onTaskPreviewSurfaceDestroyed: (TaskDisplaySession, Surface, () -> Unit) -> Unit,
    private val uiPreferences: UiPreferencesRepository,
) {
    private companion object {
        const val BUBBLE_SIZE_DP = 56
    }

    private val appContext = context.applicationContext
    private val sessionCommands = SessionCommands(appContext)
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val _panelMode = MutableStateFlow(OverlayPanelMode.BUBBLE)
    private val _resultMessage = MutableStateFlow<String?>(null)
    private val _glowTrigger = MutableStateFlow(0L)

    private var panelView: ComposeView? = null
    private var glowView: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubblePositionAnimator: ValueAnimator? = null
    private var viewTreeOwner: OverlayViewTreeOwner? = null
    private var lastState: SessionState = coordinator.state.value
    private var hidden = visibilityGate.hidden.value
    private var keyboardWasVisible = false
    private var textFieldFocused = false
    private var panelFocusEnabled = false

    val panelMode: StateFlow<OverlayPanelMode> = _panelMode.asStateFlow()
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()
    val glowTrigger: StateFlow<Long> = _glowTrigger.asStateFlow()

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) {
            if (panelView != null || glowView != null || panelParams != null || viewTreeOwner != null) {
                removeViews()
            }
            return false
        }
        val viewsAttached = panelView?.isAttachedToWindow == true && glowView?.isAttachedToWindow == true
        if (!viewsAttached || panelParams == null || viewTreeOwner == null) {
            if (panelView != null || glowView != null || panelParams != null || viewTreeOwner != null) {
                removeViews()
            }
            createViews()
        }
        if (panelView == null || glowView == null || panelParams == null) return false
        onSessionState(coordinator.state.value)
        // onSessionState may be a no-op when the state and panel mode survived a
        // view recreation; always reconcile the new window with that mode.
        updatePanelLayout(_panelMode.value != OverlayPanelMode.BUBBLE)
        setHidden(hidden)
        return true
    }

    fun hide() {
        hideKeyboard()
        removeViews()
    }

    fun destroy() {
        hide()
    }

    fun setHidden(value: Boolean) {
        hidden = value
        if (value) {
            hideKeyboard()
            releasePanelFocus()
        }
        panelView?.visibility = if (value) View.GONE else View.VISIBLE
        updateGlowVisibility()
    }

    fun onSessionState(state: SessionState) {
        val previousState = lastState
        lastState = state
        val isActive = state.isActive
        val nextMode = nextOverlayPanelMode(_panelMode.value, previousState, state)

        if (isActive) {
            _resultMessage.value = null
        } else if (previousState.isActive && state is SessionState.Completed) {
            _resultMessage.value = state.message
        } else if (previousState.isActive && state is SessionState.Stopped) {
            // A user stop is a control action, not an assistant result. Keep the
            // overlay quiet and return to the composer (or the collapsed bubble).
            _resultMessage.value = null
        }
        if (state is SessionState.Completed || state is SessionState.Stopped) {
            if (previousState.isActive) {
                setPanelMode(nextMode)
            }
        } else if (isActive) {
            setPanelMode(nextMode)
        }
    }

    fun openComposer() {
        val state = coordinator.state.value
        if (state.isActive) {
            setPanelMode(overlayPanelModeForUserExpand(state))
            return
        }
        _glowTrigger.value = System.currentTimeMillis()
        _resultMessage.value = null
        val existingInputFocus = hasExistingInputFocus()
        if (existingInputFocus) {
            prepareForComposerFocus()
        } else {
            releasePanelFocus()
        }
        setPanelMode(OverlayPanelMode.COMPOSER)
        if (existingInputFocus) {
            panelView?.post {
                val panel = panelView ?: return@post
                if (_panelMode.value == OverlayPanelMode.COMPOSER && !hidden && !textFieldFocused) {
                    // Wait until the overlay window has had a chance to take
                    // window focus, then dismiss the old app's IME. Do not
                    // focus a Compose child; the user must tap the composer.
                    panel.requestFocus()
                    hideKeyboard()
                    panel.clearFocus()
                }
            }
        }
    }

    fun showBubble() {
        bubblePositionAnimator?.cancel()
        panelView?.clearFocus()
        hideKeyboard()
        setPanelMode(OverlayPanelMode.BUBBLE)
    }

    fun dismissResult() {
        _resultMessage.value = null
        setPanelMode(OverlayPanelMode.COMPOSER)
    }

    private fun dismissAfterHorizontalSwipe(direction: OverlaySwipeDirection, swipeEndX: Int) {
        val metrics = appContext.resources.displayMetrics
        val insets = bubbleInsets()
        val bubbleWidth = bubbleSizePx()
        val bubbleHeight = bubbleSizePx()
        val savedPosition = OverlayPreferences.bubblePosition(appContext)
        val position = bubblePositionForHorizontalSwipe(
            direction = direction,
            currentPosition = savedPosition,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        val startPosition = clampBubblePosition(
            x = swipeEndX - bubbleWidth / 2,
            y = position.y,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        // Save before switching the window back to bubble mode because the
        // layout reconciliation reads the persisted position.
        OverlayPreferences.setBubblePosition(appContext, position)
        showBubble()
        animateBubbleFrom(startPosition, position)
    }

    private fun animateBubbleFrom(start: BubblePosition, target: BubblePosition) {
        val panel = panelView ?: return
        panel.post {
            if (_panelMode.value != OverlayPanelMode.BUBBLE || panelView !== panel) return@post
            val params = panelParams ?: return@post
            params.x = start.x
            params.y = start.y
            runCatching { windowManager.updateViewLayout(panel, params) }

            bubblePositionAnimator?.cancel()
            bubblePositionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    if (_panelMode.value != OverlayPanelMode.BUBBLE || panelView !== panel) {
                        animator.cancel()
                    } else {
                        val fraction = animator.animatedFraction
                        params.x = (start.x + (target.x - start.x) * fraction).roundToInt()
                        params.y = (start.y + (target.y - start.y) * fraction).roundToInt()
                        runCatching { windowManager.updateViewLayout(panel, params) }
                    }
                }
                start()
            }
        }
    }

    fun moveBubble(deltaX: Float, deltaY: Float) {
        val params = panelParams ?: return
        if (_panelMode.value != OverlayPanelMode.BUBBLE || hidden) return
        val metrics = appContext.resources.displayMetrics
        val insets = bubbleInsets()
        val position = clampBubblePosition(
            x = params.x + deltaX.roundToInt(),
            y = params.y + deltaY.roundToInt(),
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = params.width.takeIf { it > 0 } ?: bubbleSizePx(),
            bubbleHeight = params.height.takeIf { it > 0 } ?: bubbleSizePx(),
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        params.x = position.x
        params.y = position.y
        runCatching { panelView?.let { windowManager.updateViewLayout(it, params) } }
    }

    fun snapBubbleToNearestEdge() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (_panelMode.value != OverlayPanelMode.BUBBLE || hidden) return

        val metrics = appContext.resources.displayMetrics
        val insets = bubbleInsets()
        val bubbleWidth = params.width.takeIf { it > 0 } ?: bubbleSizePx()
        val bubbleHeight = params.height.takeIf { it > 0 } ?: bubbleSizePx()
        val current = clampBubblePosition(
            x = params.x,
            y = params.y,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        val target = bubblePositionOnNearestEdge(
            currentPosition = current,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        OverlayPreferences.setBubblePosition(appContext, target)
        if (current != target) {
            animateBubbleFrom(current, target)
        } else {
            params.x = target.x
            params.y = target.y
            runCatching { windowManager.updateViewLayout(panel, params) }
        }
    }

    private fun setPanelMode(mode: OverlayPanelMode) {
        val changed = _panelMode.value != mode
        if (mode != OverlayPanelMode.BUBBLE) {
            bubblePositionAnimator?.cancel()
        }
        _panelMode.value = mode
        updateGlowVisibility()
        updatePanelLayout(mode != OverlayPanelMode.BUBBLE)
        if (changed) {
            // Composer focus is opt-in: the panel stays non-focusable until
            // the text field receives a touch. Non-input modes always release
            // any focus held by the panel.
            if (!canAcceptTextInput()) {
                releasePanelFocus()
            }
            // WindowManager may measure the old Compose content before the state
            // flow recomposition lands. Reconcile once more on the next UI turn.
            panelView?.post {
                if (_panelMode.value == mode) {
                    if (!canAcceptTextInput()) {
                        releasePanelFocus()
                    }
                    updatePanelLayout(mode != OverlayPanelMode.BUBBLE)
                }
            }
        }
    }

    private fun updateGlowVisibility() {
        val visible = shouldShowOverlayGlow(
            mode = _panelMode.value,
            state = coordinator.state.value,
            hidden = hidden,
        )
        glowView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun createViews() {
        if (!Settings.canDrawOverlays(appContext)) return
        val lifecycleOwner = OverlayViewTreeOwner()
        val initialVisibility = if (hidden) View.GONE else View.VISIBLE
        val panel = ComposeView(appContext).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (canAcceptTextInput()) {
                            allowPanelFocus()
                        }
                    }
                    MotionEvent.ACTION_OUTSIDE -> releasePanelFocus()
                }
                false
            }
            visibility = initialVisibility
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                OverlayTheme {
                    OverlayPanel(
                        sessionState = coordinator.state,
                        toolCalls = coordinator.toolCalls,
                        panelMode = panelMode,
                        resultMessage = resultMessage,
                        developerStatus = developerStatus,
                        companionConnected = companionConnected,
                        pointerEvent = coordinator.pointerEvent,
                        taskDisplaySession = taskDisplaySession,
                        onKeyboardVisibilityChanged = ::onKeyboardVisibilityChanged,
                        onTextFieldFocusChanged = ::onTextFieldFocusChanged,
                        onComposerTapped = ::onComposerTapped,
                        onExpand = ::openComposer,
                        onNewRequest = ::openComposer,
                        onSubmit = ::submitRequest,
                        onDrag = ::moveBubble,
                        onBubbleDragEnd = ::snapBubbleToNearestEdge,
                        onStop = ::stopSession,
                        onAcknowledgeAttention = ::acknowledgeAttention,
                        onOpenPhoneAccess = { openDhdRoute(AppRoutes.PAIRING) },
                        onOpenCompanion = { openDhdRoute(AppRoutes.COMPANION) },
                        onContinueInDhd = ::continueInDhd,
                        onCollapse = ::showBubble,
                        onDismissResult = ::dismissResult,
                        onHorizontalSwipeDismiss = ::dismissAfterHorizontalSwipe,
                        taskPreviewState = taskPreviewState,
                        overlayHidden = visibilityGate.hidden,
                        onTaskPreviewSurfaceAvailable = onTaskPreviewSurfaceAvailable,
                        onTaskPreviewSurfaceDestroyed = onTaskPreviewSurfaceDestroyed,
                        preferences = uiPreferences,
                    )
                }
            }
        }
        val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            android.graphics.Rect(0, 0, bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val glow = ComposeView(appContext).apply {
            fitsSystemWindows = false
            visibility = initialVisibility
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                OverlayTheme {
                    OverlayGlow(
                        sessionState = coordinator.state,
                        panelMode = panelMode,
                        glowTrigger = glowTrigger,
                        hidden = visibilityGate.hidden,
                    )
                }
            }
        }
        val glowLayout = WindowManager.LayoutParams(
            screenBounds.width(),
            screenBounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val savedPosition = OverlayPreferences.bubblePosition(appContext)
        val insets = bubbleInsets()
        val bubblePosition = bubblePositionOnNearestEdge(
            currentPosition = savedPosition,
            displayWidth = appContext.resources.displayMetrics.widthPixels,
            displayHeight = appContext.resources.displayMetrics.heightPixels,
            bubbleWidth = bubbleSizePx(),
            bubbleHeight = bubbleSizePx(),
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        val panelLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubblePosition.x
            y = bubblePosition.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            windowManager.addView(glow, glowLayout)
            windowManager.addView(panel, panelLayout)
            glowView = glow
            panelView = panel
            panelParams = panelLayout
            viewTreeOwner = lifecycleOwner
        } catch (error: RuntimeException) {
            runCatching { windowManager.removeViewImmediate(panel) }
            runCatching { windowManager.removeViewImmediate(glow) }
            lifecycleOwner.destroy()
            android.util.Log.w("DhdOverlay", "Could not attach overlay windows", error)
        }
    }

    private fun updatePanelLayout(expanded: Boolean) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (expanded) {
            val focusable = panelFocusEnabled && canAcceptTextInput()
            panel.isFocusable = focusable
            panel.isFocusableInTouchMode = focusable
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.x = 0
            params.y = 0
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (!focusable) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            panel.isFocusable = false
            panel.isFocusableInTouchMode = false
            val savedPosition = OverlayPreferences.bubblePosition(appContext)
            val insets = bubbleInsets()
            val bubblePosition = bubblePositionOnNearestEdge(
                currentPosition = savedPosition,
                displayWidth = appContext.resources.displayMetrics.widthPixels,
                displayHeight = appContext.resources.displayMetrics.heightPixels,
                bubbleWidth = bubbleSizePx(),
                bubbleHeight = bubbleSizePx(),
                topInset = insets.top,
                bottomInset = insets.bottom,
            )
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = bubblePosition.x
            params.y = bubblePosition.y
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun onKeyboardVisibilityChanged(visible: Boolean) {
        if (visible) {
            keyboardWasVisible = true
            if (textFieldFocused) {
                allowPanelFocus()
            }
        } else if (keyboardWasVisible && textFieldFocused) {
            keyboardWasVisible = false
            releasePanelFocus()
        } else {
            keyboardWasVisible = false
        }
    }

    private fun allowPanelFocus() {
        if (hidden || !canAcceptTextInput()) return
        setPanelFocusable(true)
    }

    private fun prepareForComposerFocus() {
        val panel = panelView ?: return
        // The bubble is normally not focusable, so the text field in the
        // underlying app keeps focus while the user taps it. Claim the overlay
        // window only for the handoff; the composer field itself remains
        // unfocused until the user taps it.
        setPanelFocusable(true)
        panel.isFocusableInTouchMode = true
        panel.isFocusable = true
        panel.requestFocus()
    }

    private fun hasExistingInputFocus(): Boolean {
        val panel = panelView ?: return false
        val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            false
        }
        // A stale served view is not enough to trigger the handoff. Only a
        // currently visible IME means the previous app needs to relinquish
        // its text-entry session before the composer opens.
        return imeVisible
    }

    private fun onTextFieldFocusChanged(focused: Boolean) {
        textFieldFocused = focused
        if (!focused || !canAcceptTextInput()) return
        allowPanelFocus()
        panelView?.post {
            val panel = panelView ?: return@post
            if (canAcceptTextInput()) {
                appContext.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(panel, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun onComposerTapped() {
        if (!canAcceptTextInput()) return
        // The tap started while the overlay window was not focusable. Enable
        // the window first, then let Compose request the actual text-field
        // focus on the next frame.
        allowPanelFocus()
        panelView?.post {
            val panel = panelView ?: return@post
            if (canAcceptTextInput()) {
                panel.requestFocus()
            }
        }
    }

    private fun releasePanelFocus() {
        textFieldFocused = false
        panelView?.clearFocus()
        setPanelFocusable(false)
    }

    private fun canAcceptTextInput(): Boolean =
        !coordinator.state.value.isActive &&
            _panelMode.value in setOf(OverlayPanelMode.COMPOSER, OverlayPanelMode.RESULT)

    private fun setPanelFocusable(focusable: Boolean) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        panelFocusEnabled = focusable
        panel.isFocusable = focusable
        panel.isFocusableInTouchMode = focusable
        val nextFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (nextFlags == params.flags) return
        params.flags = nextFlags
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun removeViews() {
        bubblePositionAnimator?.cancel()
        bubblePositionAnimator = null
        panelView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        glowView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panelView = null
        glowView = null
        panelParams = null
        viewTreeOwner?.destroy()
        viewTreeOwner = null
    }

    private fun submitRequest(request: String) {
        _resultMessage.value = null
        val preferences = uiPreferences.current()
        val reasoningEffort = ReasoningEffort.fromStorage(preferences.reasoningEffort).codexValue
        sessionCommands.start(request, DHD_CONVERSATION_ID, reasoningEffort, preferences.fastMode)
        setPanelMode(OverlayPanelMode.WORKING)
    }

    private fun stopSession() {
        sessionCommands.stop()
    }

    private fun acknowledgeAttention(): Boolean {
        val acknowledged = coordinator.acknowledgeAttention()
        if (acknowledged) {
            AssistantForegroundService.removeAttentionNotification(appContext)
        }
        return acknowledged
    }

    private fun openDhdRoute(route: String) {
        appContext.startActivity(
            android.content.Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            },
        )
    }

    private fun continueInDhd() {
        val state = coordinator.state.value
        val conversationId = when (state) {
            is SessionState.Running -> state.conversationId
            is SessionState.Paused -> state.conversationId
            is SessionState.Stopped -> state.conversationId
            is SessionState.Completed -> state.conversationId
            SessionState.Idle -> DHD_CONVERSATION_ID
        } ?: DHD_CONVERSATION_ID
        appContext.startActivity(
            android.content.Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            },
        )
    }

    private fun hideKeyboard() {
        keyboardWasVisible = false
        textFieldFocused = false
        panelView?.let { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
            }
            view.windowToken?.let { token ->
                appContext.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(token, 0)
            }
        }
    }

    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        val preferences by uiPreferences.state.collectAsState()
        val isDark = ThemeMode.fromStorage(preferences.themeMode).isDark(isSystemInDarkTheme())
        val colors = if (isDark) DarkAssistantColors else LightAssistantColors
        CompositionLocalProvider(LocalAssistantColors provides colors, content = content)
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()

    private fun bubbleSizePx(): Int = dp(BUBBLE_SIZE_DP)

    private fun bubbleInsets(): BubbleInsets {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return BubbleInsets()
        val insets = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        return BubbleInsets(top = insets.top, bottom = insets.bottom)
    }

private data class BubbleInsets(
        val top: Int = 0,
        val bottom: Int = 0,
    )
}
