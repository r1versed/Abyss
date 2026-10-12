/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.peek

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.lib.state.ext.flowScoped
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.theater.ClarusTheaterActivity
import org.mozilla.fenix.theme.ClarusHaptics
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.Theme
import org.mozilla.fenix.theme.getThemeProvider

/**
 * Link peek: long-press on a link opens a large glass card with live preview
 * and pill actions (New tab / Copy / Share). Swipe left = private tab.
 * Gestures are handled at the raw touch level so release and drag work cleanly.
 */
class TabPeekController(
    private val browserStore: BrowserStore,
    private val fenixBrowserUseCases: FenixBrowserUseCases,
    private val contextMenuUseCases: ContextMenuUseCases,
    private val engine: Engine,
    private val lifecycleOwner: LifecycleOwner,
    private val container: ViewGroup,
    private val engineView: View,
    private val isPrivateSession: () -> Boolean = { false },
    private val onActionRequested: (actionId: String, currentUrl: String, hit: HitResult) -> Unit = { _, _, _ -> },
    private val dismissLegacyMenu: () -> Unit = { },
    private val onPeekStarted: () -> Unit = { },
    private val onPeekDismissed: () -> Unit = { },
) {
    private var overlay: ComposeView? = null
    private var hitResultScope: CoroutineScope? = null
    private var activePeekId: Long = 0L
    private var activeHost: FrameLayout? = null
    private var activeEngineView: EngineView? = null
    private var activeSession: EngineSession? = null
    private var isPreviewActReq: Boolean = false
    private var hasRenderedAndLoaded: Boolean = false
    private var activeAttachListener: View.OnAttachStateChangeListener? = null
    private var activeLayoutListener: View.OnLayoutChangeListener? = null

    private val lifecycleRingBuffer = ArrayDeque<String>(8)

    private fun recordLifecycleEvent(peekId: Long, event: String) {
        val now = SystemClock.uptimeMillis() % 100000
        val entry = "[$now ms #$peekId:$event]"
        synchronized(lifecycleRingBuffer) {
            if (lifecycleRingBuffer.size >= 8) {
                lifecycleRingBuffer.removeFirst()
            }
            lifecycleRingBuffer.addLast(entry)
        }
        updateDebugInfo()
    }

    private fun getLifecycleHistory(): String {
        synchronized(lifecycleRingBuffer) {
            return lifecycleRingBuffer.joinToString(" -> ")
        }
    }

    private var pendingHit: HitResult? = null
    private var previewTimeoutJob: Job? = null
    private var teardownJob: Job? = null
    private var lifecycleObserver: androidx.lifecycle.DefaultLifecycleObserver? = null
    private var nextPeekId = 0L
    private var lastPeekReleased = true
    private var previousSessionClosed = true
    private var previousSessionClosedBeforeThisPeek = true
    private var lastSelectedTabId: String? = null
    private var lastCrashEvent: String = "none"
    private var previewUrl: String = ""
    private var currentProgress: Int = 0

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasInitialTouch = false
    private var passedCommitThreshold = false
    private var passedPrivateThreshold = false
    private var passedPinThreshold = false
    private var passedActionsThreshold = false
    private val actionBounds = mutableMapOf<String, Rect>()

    private var uiState by mutableStateOf<TabPeekUiState>(TabPeekUiState.Hidden)

    val isPeekVisible: Boolean
        get() = uiState is TabPeekUiState.Peeking

    fun shouldSuppressContextMenu(): Boolean =
        isPeekVisible || pendingHit != null || uiState is TabPeekUiState.Canceling

    private var currentLoadToken: Long = 0L
    private var hasReceivedLocationForCurrentPeek: Boolean = false

    private val previewObserver = object : EngineSession.Observer {
        private fun isEventValid(): Boolean {
            val state = uiState as? TabPeekUiState.Peeking ?: return false
            if (previewUrl.isBlank() || previewUrl == "about:blank") return false
            if (state.peekId != activePeekId || activePeekId == 0L) return false
            return true
        }

        override fun onLocationChange(url: String, hasUserGesture: Boolean) {
            if (!isEventValid()) return
            if (url.isNotBlank() && url != "about:blank") {
                hasReceivedLocationForCurrentPeek = true
                val current = uiState
                if (current is TabPeekUiState.Peeking) {
                    uiState = current.copy(
                        content = current.content.copy(url = url),
                    )
                    updateDebugInfo()
                }
            }
        }

        override fun onFirstContentfulPaint() {
            if (!isEventValid() || !hasReceivedLocationForCurrentPeek) return
            markPreviewReady()
            updateDebugInfo()
        }

        override fun onProgress(progress: Int) {
            if (!isEventValid() || !hasReceivedLocationForCurrentPeek) return
            currentProgress = progress
            if (progress >= 70) {
                markPreviewReady()
            }
            updateDebugInfo()
        }

        override fun onLoadingStateChange(loading: Boolean) {
            if (!isEventValid() || !hasReceivedLocationForCurrentPeek) return
            if (!loading) {
                markPreviewReady()
            }
            updateDebugInfo()
        }

        override fun onTitleChange(title: String) {
            if (!isEventValid() || !hasReceivedLocationForCurrentPeek) return
            val current = uiState
            if (current is TabPeekUiState.Peeking && title.isNotBlank() && title != "about:blank") {
                uiState = current.copy(
                    content = current.content.copy(title = title),
                )
                updateDebugInfo()
            }
        }

        override fun onCrash() {
            if (!isEventValid()) return
            lastCrashEvent = "CRASH"
            updateDebugInfo()
            recreatePreviewSession()
        }

        override fun onProcessKilled() {
            if (!isEventValid()) return
            lastCrashEvent = "KILLED"
            updateDebugInfo()
            recreatePreviewSession()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        handleTouchEvent(event)
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!isPeekVisible) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasInitialTouch = true
                    passedCommitThreshold = false
                    passedPrivateThreshold = false
                    passedPinThreshold = false
                    passedActionsThreshold = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hasInitialTouch = false
                    passedCommitThreshold = false
                    passedPrivateThreshold = false
                    passedPinThreshold = false
                    passedActionsThreshold = false
                }
            }
            return false
        }

        val peekingState = uiState as? TabPeekUiState.Peeking
        if (peekingState?.isPinned == true) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                hasInitialTouch = true
                passedCommitThreshold = false
                passedPrivateThreshold = false
                passedPinThreshold = false
                passedActionsThreshold = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasInitialTouch) {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasInitialTouch = true
                }
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val dragUp = (-dy).coerceAtLeast(0f)
                val dragDown = dy.coerceAtLeast(0f)
                val dragLeft = (-dx).coerceAtLeast(0f)
                val dragRight = dx.coerceAtLeast(0f)

                val density = container.resources.displayMetrics.density
                val commitThresholdPx = COMMIT_DRAG_THRESHOLD_DP * density
                val privateThresholdPx = PRIVATE_DRAG_THRESHOLD_DP * density
                val pinThresholdPx = PIN_DRAG_THRESHOLD_DP * density
                val actionsThresholdPx = ACTIONS_DRAG_THRESHOLD_DP * density

                val minActionMovementPx = 24f * density
                val totalDistSq = dx * dx + dy * dy
                val hasMovedEnoughForAction = totalDistSq >= (minActionMovementPx * minActionMovementPx)

                val hoveredAction = if (hasMovedEnoughForAction) findActionAt(event.rawX, event.rawY) else null
                val current = uiState

                if (hoveredAction != null) {
                    val prevHovered = (current as? TabPeekUiState.Peeking)?.hoveredActionId
                    if (hoveredAction != prevHovered) {
                        ClarusHaptics.performTick(engineView)
                    }
                    if (current is TabPeekUiState.Peeking) {
                        uiState = current.copy(
                            dragUpPx = 0f,
                            dragLeftPx = 0f,
                            dragRightPx = 0f,
                            dragDownPx = dragDown,
                            activeGesture = PeekGestureTarget.NONE,
                            hoveredActionId = hoveredAction,
                        )
                    }
                    return true
                }

                val dominantGesture = when {
                    dragUp >= dragLeft && dragUp >= dragRight && dragUp >= dragDown && dragUp > 30f -> PeekGestureTarget.NEW_TAB
                    dragLeft >= dragUp && dragLeft >= dragRight && dragLeft >= dragDown && dragLeft > 30f -> PeekGestureTarget.PRIVATE_TAB
                    dragRight >= dragUp && dragRight >= dragLeft && dragRight >= dragDown && dragRight > 30f -> PeekGestureTarget.PIN
                    dragDown >= dragUp && dragDown >= dragLeft && dragDown >= dragRight && dragDown > 30f -> PeekGestureTarget.ACTIONS
                    else -> PeekGestureTarget.NONE
                }

                if (current is TabPeekUiState.Peeking) {
                    uiState = current.copy(
                        dragUpPx = dragUp,
                        dragLeftPx = dragLeft,
                        dragRightPx = dragRight,
                        dragDownPx = dragDown,
                        commitThresholdPx = commitThresholdPx,
                        privateThresholdPx = privateThresholdPx,
                        pinThresholdPx = pinThresholdPx,
                        actionsThresholdPx = actionsThresholdPx,
                        activeGesture = dominantGesture,
                        hoveredActionId = null,
                    )
                }

                when (dominantGesture) {
                    PeekGestureTarget.NEW_TAB -> {
                        if (dragUp >= commitThresholdPx && !passedCommitThreshold) {
                            ClarusHaptics.performTick(engineView)
                            passedCommitThreshold = true
                        } else if (dragUp < commitThresholdPx) {
                            passedCommitThreshold = false
                        }
                    }
                    PeekGestureTarget.PRIVATE_TAB -> {
                        if (dragLeft >= privateThresholdPx && !passedPrivateThreshold) {
                            ClarusHaptics.performTick(engineView)
                            passedPrivateThreshold = true
                        } else if (dragLeft < privateThresholdPx) {
                            passedPrivateThreshold = false
                        }
                    }
                    PeekGestureTarget.PIN -> {
                        if (dragRight >= pinThresholdPx && !passedPinThreshold) {
                            ClarusHaptics.performTick(engineView)
                            passedPinThreshold = true
                        } else if (dragRight < pinThresholdPx) {
                            passedPinThreshold = false
                        }
                    }
                    PeekGestureTarget.ACTIONS -> {
                        if (dragDown >= actionsThresholdPx && !passedActionsThreshold) {
                            ClarusHaptics.performTick(engineView)
                            passedActionsThreshold = true
                        } else if (dragDown < actionsThresholdPx) {
                            passedActionsThreshold = false
                        }
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val finalX = event.rawX
                val finalY = event.rawY
                val peeking = uiState as? TabPeekUiState.Peeking
                val dx = finalX - initialTouchX
                val dy = finalY - initialTouchY
                val density = container.resources.displayMetrics.density
                val minActionMovementPx = 24f * density
                val totalDistSq = dx * dx + dy * dy
                val hasMovedEnoughForAction = totalDistSq >= (minActionMovementPx * minActionMovementPx)

                val hitAction = if (hasMovedEnoughForAction) {
                    findActionAt(finalX, finalY) ?: peeking?.hoveredActionId
                } else {
                    null
                }

                when {
                    hitAction != null -> {
                        val hit = pendingHit
                        val currentUrl = peeking?.content?.url ?: hit?.getUrl() ?: ""
                        ClarusHaptics.performClick(engineView)
                        if (hit != null) {
                            onActionRequested(hitAction, currentUrl, hit)
                            consumeHit()
                            teardown()
                        } else if (currentUrl.isNotBlank()) {
                            executeDirectAction(hitAction, currentUrl)
                        } else {
                            cancelPeek()
                        }
                    }

                    peeking != null && peeking.activeGesture == PeekGestureTarget.NEW_TAB && peeking.commitProgress >= 1f -> {
                        commit(private = false)
                    }

                    peeking != null && peeking.activeGesture == PeekGestureTarget.PRIVATE_TAB && peeking.privateProgress >= 1f -> {
                        commit(private = true)
                    }

                    peeking != null && peeking.activeGesture == PeekGestureTarget.PIN && peeking.pinProgress >= 1f -> {
                        pin()
                    }

                    else -> {
                        cancelPeek()
                    }
                }
                hasInitialTouch = false
                passedCommitThreshold = false
                passedPrivateThreshold = false
                passedPinThreshold = false
                passedActionsThreshold = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPeek()
                hasInitialTouch = false
                passedCommitThreshold = false
                passedPrivateThreshold = false
                passedPinThreshold = false
                passedActionsThreshold = false
                return true
            }
        }
        return true
    }

    private fun findActionAt(rawX: Float, rawY: Float): String? {
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val screenLoc = IntArray(2)
        container.getLocationOnScreen(screenLoc)
        val windowX = rawX - (screenLoc[0] - loc[0])
        val windowY = rawY - (screenLoc[1] - loc[1])

        // 1. Prefer the action whose bounds contain the finger
        for ((id, bounds) in actionBounds) {
            if (windowX >= bounds.left && windowX <= bounds.right &&
                windowY >= bounds.top && windowY <= bounds.bottom
            ) {
                return id
            }
        }

        // 2. Otherwise find the nearest action center within slop
        val density = container.resources.displayMetrics.density
        val slopXPx = 12f * density
        val slopYPx = 32f * density

        var bestId: String? = null
        var minDistanceSq = Float.MAX_VALUE

        for ((id, bounds) in actionBounds) {
            if (windowX >= (bounds.left - slopXPx) &&
                windowX <= (bounds.right + slopXPx) &&
                windowY >= (bounds.top - slopYPx) &&
                windowY <= (bounds.bottom + slopYPx)
            ) {
                val centerX = (bounds.left + bounds.right) / 2f
                val centerY = (bounds.top + bounds.bottom) / 2f
                val dx = windowX - centerX
                val dy = windowY - centerY
                val distSq = dx * dx + dy * dy
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq
                    bestId = id
                }
            }
        }
        return bestId
    }

    private fun executeDirectAction(actionId: String, currentUrl: String) {
        when (actionId) {
            ACTION_OPEN_TAB -> {
                fenixBrowserUseCases.loadUrlOrSearch(currentUrl, newTab = true, private = isPrivateSession())
                teardown()
            }
            ACTION_COPY -> {
                val clipboard = container.context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("URL", currentUrl))
                teardown()
            }
            ACTION_SHARE -> {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
                }
                container.context.startActivity(android.content.Intent.createChooser(send, null))
                teardown()
            }
            else -> teardown()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListeners(view: View) {
        view.setOnTouchListener(touchListener)
    }

    private fun detachTouchListeners(view: View) {
        view.setOnTouchListener(null)
    }

    fun start() {
        attachTouchListeners(engineView)
        lifecycleObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                if (isPeekVisible || uiState is TabPeekUiState.Canceling) {
                    teardown()
                }
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                if (isPeekVisible || uiState is TabPeekUiState.Canceling) {
                    teardown()
                }
            }
        }
        lifecycleObserver = observer
        lifecycleOwner.lifecycle.addObserver(observer)
        hitResultScope?.cancel()
        hitResultScope = browserStore.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.map { state ->
                val tab = state.findTabOrCustomTabOrSelectedTab(null)
                tab?.id to tab?.content?.hitResult
            }
                .distinctUntilChanged()
                .collect { (tabId, hit) ->
                    if (lastSelectedTabId != null && lastSelectedTabId != tabId && isPeekVisible) {
                        teardown()
                    }
                    lastSelectedTabId = tabId
                    if (hit != null && hit.isPeekableLink() && pendingHit != hit) {
                        openPeek(hit)
                    }
                }
        }
    }

    fun stop() {
        lifecycleObserver?.let {
            lifecycleOwner.lifecycle.removeObserver(it)
            lifecycleObserver = null
        }
        hitResultScope?.cancel()
        hitResultScope = null
        detachTouchListeners(engineView)
        teardown()
    }

    private fun createPreviewRequestInterceptor(): mozilla.components.concept.engine.request.RequestInterceptor {
        val appRequestInterceptor = org.mozilla.fenix.AppRequestInterceptor(
            context = container.context,
            isPrivateForSession = { isPrivateSession() },
        )
        return object : mozilla.components.concept.engine.request.RequestInterceptor {
            override fun interceptsAppInitiatedRequests() = true

            override fun onLoadRequest(
                engineSession: EngineSession,
                uri: String,
                lastUri: String?,
                hasUserGesture: Boolean,
                isSameDomain: Boolean,
                isRedirect: Boolean,
                isDirectNavigation: Boolean,
                isSubframeRequest: Boolean,
            ): mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse? {
                return if (uri.startsWith("${org.mozilla.fenix.AppRequestInterceptor.ERROR_PAGE_ACTION_SCHEME}://")) {
                    appRequestInterceptor.onLoadRequest(
                        engineSession,
                        uri,
                        lastUri,
                        hasUserGesture,
                        isSameDomain,
                        isRedirect,
                        isDirectNavigation,
                        isSubframeRequest,
                    )
                } else {
                    null
                }
            }

            override fun onErrorRequest(
                session: EngineSession,
                errorType: mozilla.components.browser.errorpages.ErrorType,
                uri: String?,
            ): mozilla.components.concept.engine.request.RequestInterceptor.ErrorResponse {
                return appRequestInterceptor.onErrorRequest(session, errorType, uri)
            }
        }
    }

    private fun openPeek(hit: HitResult) {
        if (teardownJob != null) {
            teardownJob?.cancel()
            teardownJob = null
            teardown()
        }
        previewTimeoutJob?.cancel()
        previewTimeoutJob = null
        removeOverlay()
        dismissLegacyMenu()
        ClarusHaptics.performTick(engineView)

        nextPeekId++
        activePeekId = nextPeekId
        currentLoadToken = nextPeekId
        hasReceivedLocationForCurrentPeek = false
        hasRenderedAndLoaded = false
        isPreviewActReq = false
        pendingHit = hit
        val currentTabUrl = browserStore.state.selectedTab?.content?.url
        val rawHitUrl = hit.getUrl()
        val url = resolvePeekUrl(rawHitUrl, currentTabUrl)
        previewUrl = url
        lastCrashEvent = "none"
        currentProgress = 0

        val isDownloadable = isDownloadableUrl(url)
        val primaryAction = if (isDownloadable) {
            TabPeekAction(id = ACTION_DOWNLOAD, label = "Download", isPrimary = true)
        } else {
            TabPeekAction(id = ACTION_OPEN_TAB, label = "New tab", isPrimary = true)
        }
        val actions = listOf(
            TabPeekAction(id = ACTION_COPY, label = "Copy"),
            TabPeekAction(id = ACTION_SHARE, label = "Share"),
            primaryAction,
        )
        val density = container.resources.displayMetrics.density
        uiState = TabPeekUiState.Peeking(
            content = TabPeekContent(url = url, title = hit.linkLabel()),
            actions = actions,
            commitThresholdPx = COMMIT_DRAG_THRESHOLD_DP * density,
            privateThresholdPx = PRIVATE_DRAG_THRESHOLD_DP * density,
            pinThresholdPx = PIN_DRAG_THRESHOLD_DP * density,
            actionsThresholdPx = ACTIONS_DRAG_THRESHOLD_DP * density,
            peekId = nextPeekId,
        )
        onPeekStarted()
        ensureOverlay()
        updateDebugInfo()

        previewTimeoutJob = lifecycleOwner.lifecycleScope.launch {
            delay(4000)
            onPreviewTimeout()
        }
    }

    private fun createPreviewView(host: FrameLayout) {
        val peekId = activePeekId
        if (peekId == 0L || uiState !is TabPeekUiState.Peeking) return

        if (activeEngineView != null || activeSession != null) {
            teardownActivePreview()
        }

        val context = container.context
        val activity = context.findActivity() ?: (context as? Activity)
        val ev = engine.createView(context)
        ev.setActivityContext(activity)
        val view = ev.asView()
        recordLifecycleEvent(peekId, "view-created")

        val session = engine.createSession(private = isPrivateSession())
        session.settings.requestInterceptor = createPreviewRequestInterceptor()
        val isDesktop = browserStore.state.selectedTab?.content?.desktopMode ?: false
        session.toggleDesktopMode(enable = isDesktop, reload = false)
        session.register(previewObserver)

        activeHost = host
        activeEngineView = ev
        activeSession = session

        fun bindToHost() {
            if (activePeekId != peekId) return
            val parent = view.parent
            if (parent != null && parent !== host) {
                (parent as? ViewGroup)?.removeView(view)
                host.post { bindToHost() }
                return
            }
            if (view.parent !== host) {
                host.removeAllViews()
                host.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

            fun checkAndRenderLoad() {
                if (activePeekId != peekId) return
                if (hasRenderedAndLoaded) return
                if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
                    hasRenderedAndLoaded = true
                    recordLifecycleEvent(peekId, "render")
                    ev.render(session)
                    setGeckoSessionActive(session, true)
                    recordLifecycleEvent(peekId, "load")
                    session.loadUrl(previewUrl)
                    updateDebugInfo()
                }
            }

            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    recordLifecycleEvent(peekId, "attached")
                    checkAndRenderLoad()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    recordLifecycleEvent(peekId, "detached")
                    updateDebugInfo()
                }
            }
            activeAttachListener = attachListener
            view.addOnAttachStateChangeListener(attachListener)

            val layoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View?,
                    left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    checkAndRenderLoad()
                }
            }
            activeLayoutListener = layoutListener
            view.addOnLayoutChangeListener(layoutListener)

            if (view.isAttachedToWindow) {
                recordLifecycleEvent(peekId, "attached")
            }
            checkAndRenderLoad()
            updateDebugInfo()
        }

        bindToHost()
    }

    private fun releasePreviewView(peekId: Long) {
        if (peekId == activePeekId || peekId == 0L) {
            teardownActivePreview()
        }
    }

    private fun teardownActivePreview() {
        val peekId = activePeekId
        val host = activeHost
        val ev = activeEngineView
        val session = activeSession
        val attachListener = activeAttachListener
        val layoutListener = activeLayoutListener

        activeAttachListener = null
        activeLayoutListener = null
        activeHost = null
        activeEngineView = null
        activeSession = null
        hasRenderedAndLoaded = false
        isPreviewActReq = false

        val view = ev?.asView()
        if (view != null) {
            if (attachListener != null) {
                view.removeOnAttachStateChangeListener(attachListener)
            }
            if (layoutListener != null) {
                view.removeOnLayoutChangeListener(layoutListener)
            }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        host?.removeAllViews()

        if (ev != null) {
            recordLifecycleEvent(peekId, "release")
            try {
                ev.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error releasing EngineView", e)
            }
        }

        if (session != null) {
            setGeckoSessionActive(session, false)
            session.unregister(previewObserver)
            recordLifecycleEvent(peekId, "session-close")
            try {
                session.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Error closing EngineSession", e)
            }
        }

        updateDebugInfo()
    }

    private fun recreatePreviewSession() {
        val host = activeHost ?: return
        val url = previewUrl.ifBlank { return }
        teardownActivePreview()
        hasReceivedLocationForCurrentPeek = false
        previewUrl = url
        createPreviewView(host)
    }

    private fun markPreviewReady() {
        val current = uiState
        if (current is TabPeekUiState.Peeking && !current.previewReady) {
            uiState = current.copy(previewReady = true)
        }
    }

    private fun onPreviewTimeout() {
        val current = uiState
        if (current is TabPeekUiState.Peeking && !current.previewReady) {
            uiState = current.copy(previewTimedOut = true)
        }
    }

    private fun commit(private: Boolean) {
        val state = uiState as? TabPeekUiState.Peeking ?: return
        uiState = TabPeekUiState.Committing
        ClarusHaptics.performCommit(engineView)
        consumeHit()
        fenixBrowserUseCases.loadUrlOrSearch(
            searchTermOrURL = state.content.url,
            newTab = true,
            private = private,
        )
        teardown()
    }

    private fun pin() {
        val current = uiState as? TabPeekUiState.Peeking ?: return
        ClarusHaptics.performTick(engineView)
        uiState = current.copy(
            isPinned = true,
            activeGesture = PeekGestureTarget.NONE,
            dragUpPx = 0f,
            dragLeftPx = 0f,
            dragRightPx = 0f,
            dragDownPx = 0f,
        )
    }

    private fun cancelPeek() {
        val wasVisible = uiState is TabPeekUiState.Peeking
        if (wasVisible) ClarusHaptics.performTick(engineView)
        consumeHit()
        overlay?.animate()?.alpha(0f)?.setDuration(SNAP_SHUT_MS.toLong())?.start()
        teardownJob?.cancel()
        teardownJob = lifecycleOwner.lifecycleScope.launch {
            delay(SNAP_SHUT_MS.toLong())
            teardown()
        }
    }

    private fun consumeHit() {
        val tabId = browserStore.state.findTabOrCustomTabOrSelectedTab(null)?.id
            ?: browserStore.state.selectedTab?.id
        tabId?.let { contextMenuUseCases.consumeHitResult(it) }
        pendingHit = null
    }

    private fun teardown() {
        teardownJob?.cancel()
        teardownJob = null
        consumeHit()
        teardownActivePreview()
        removeOverlay()
        uiState = TabPeekUiState.Hidden
        pendingHit = null
        hasInitialTouch = false
        passedCommitThreshold = false
        passedPrivateThreshold = false
        passedPinThreshold = false
        passedActionsThreshold = false
        actionBounds.clear()
        onPeekDismissed()
    }

    private fun extractGeckoSession(session: EngineSession?): Pair<org.mozilla.geckoview.GeckoSession?, Boolean> {
        if (session == null) return null to true
        return try {
            val field = try {
                session.javaClass.getDeclaredField("geckoSession")
            } catch (_: NoSuchFieldException) {
                session.javaClass.declaredFields.firstOrNull {
                    it.type.name.contains("GeckoSession") || it.name.startsWith("geckoSession")
                }
            }
            if (field != null) {
                field.isAccessible = true
                val gs = field.get(session) as? org.mozilla.geckoview.GeckoSession
                gs to (gs != null)
            } else {
                null to false
            }
        } catch (_: Throwable) {
            null to false
        }
    }

    private fun setGeckoSessionActive(session: EngineSession?, active: Boolean) {
        val (gs, _) = extractGeckoSession(session)
        if (session === activeSession) {
            isPreviewActReq = active
        }
        if (gs != null) {
            try {
                gs.setActive(active)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set active on GeckoSession", e)
            }
            try {
                gs.setFocused(active)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set focus on GeckoSession", e)
            }
        }
    }

    private fun updateDebugInfo() {
        val current = uiState as? TabPeekUiState.Peeking ?: return
        val host = activeHost
        val displayedView = host?.let { if (it.childCount > 0) it.getChildAt(0) else null }
        val cachedEngineView = activeEngineView
        val cachedView = cachedEngineView?.asView()
        val viewToInspect = displayedView ?: cachedView

        val sess = activeSession
        val (gs, reflOk) = extractGeckoSession(sess)
        val isOpen = gs?.isOpen ?: false

        val mainTabSession = browserStore.state.selectedTab?.engineState?.engineSession
        val (mainGs, _) = extractGeckoSession(mainTabSession)
        val mainOpen = mainGs?.isOpen == true

        val vHash = cachedView?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val dispHash = displayedView?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val hostHash = host?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"

        val parentClass = viewToInspect?.parent?.javaClass?.simpleName ?: "none"
        val hasWindowToken = viewToInspect?.windowToken != null
        val visibility = when (viewToInspect?.visibility) {
            View.VISIBLE -> "VIS"
            View.INVISIBLE -> "INV"
            View.GONE -> "GONE"
            else -> "none"
        }

        val info = PeekDebugInfo(
            peekId = current.peekId,
            sessionId = sess?.let { Integer.toHexString(it.hashCode()) } ?: "none",
            isOpen = isOpen,
            actReq = isPreviewActReq,
            reflOk = reflOk,
            isAttached = viewToInspect?.isAttachedToWindow == true,
            viewWidth = viewToInspect?.width ?: 0,
            viewHeight = viewToInspect?.height ?: 0,
            loadProgress = currentProgress,
            pageTitle = current.content.title ?: "",
            crashEvent = lastCrashEvent,
            prevClosed = previousSessionClosedBeforeThisPeek,
            mainAttached = engineView.isAttachedToWindow,
            mainOpen = mainOpen,
            viewHash = vHash,
            hostHash = hostHash,
            displayedViewHash = dispHash,
            parentClass = parentClass,
            hasWindowToken = hasWindowToken,
            visibility = visibility,
            lifecycleHistory = getLifecycleHistory(),
        )
        uiState = current.copy(debugInfo = info)
    }

    private fun ensureOverlay() {
        removeOverlay()
        val cv = ComposeView(container.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setOnTouchListener(touchListener)
            setContent {
                val theme: Theme = getThemeProvider().provideTheme()
                FirefoxTheme(theme = theme) {
                    TabPeekOverlay(
                        state = uiState,
                        theme = theme,
                        createPreviewView = { host -> createPreviewView(host) },
                        releasePreviewView = { id -> releasePreviewView(id) },
                        onAction = { id ->
                            val currentUrl = (uiState as? TabPeekUiState.Peeking)?.content?.url ?: pendingHit?.getUrl() ?: ""
                            val hit = pendingHit
                            ClarusHaptics.performClick(engineView)
                            if (hit != null) {
                                onActionRequested(id, currentUrl, hit)
                                consumeHit()
                                teardown()
                            } else if (currentUrl.isNotBlank()) {
                                when (id) {
                                    ACTION_OPEN_TAB -> {
                                        fenixBrowserUseCases.loadUrlOrSearch(currentUrl, newTab = true, private = isPrivateSession())
                                        teardown()
                                    }
                                    ACTION_COPY -> {
                                        val clipboard = container.context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("URL", currentUrl))
                                        teardown()
                                    }
                                    ACTION_SHARE -> {
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
                                        }
                                        container.context.startActivity(android.content.Intent.createChooser(send, null))
                                        teardown()
                                    }
                                    else -> {
                                        teardown()
                                    }
                                }
                            } else {
                                teardown()
                            }
                        },
                        onDismiss = { cancelPeek() },
                        onCommit = { commit(private = false) },
                        onOpenPrivate = { commit(private = true) },
                        onPin = { pin() },
                        onDrag = { up, left ->
                            val current = uiState
                            if (current is TabPeekUiState.Peeking) {
                                uiState = current.copy(dragUpPx = up, dragLeftPx = left)
                            }
                        },
                        onActionBounds = { id, bounds ->
                            actionBounds[id] = bounds
                        },
                    )
                }
            }
        }
        overlay = cv
        val lp = if (container is CoordinatorLayout) {
            CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                behavior = object : CoordinatorLayout.Behavior<View>() {
                    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: View, ev: MotionEvent): Boolean {
                        if (isPeekVisible) {
                            val peeking = uiState as? TabPeekUiState.Peeking
                            if (peeking?.isPinned == true) {
                                return false
                            }
                            handleTouchEvent(ev)
                            return true
                        }
                        return false
                    }

                    override fun onTouchEvent(parent: CoordinatorLayout, child: View, ev: MotionEvent): Boolean {
                        val peeking = uiState as? TabPeekUiState.Peeking
                        if (peeking?.isPinned == true) {
                            return false
                        }
                        return handleTouchEvent(ev)
                    }
                }
            }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        container.addView(cv, lp)
    }

    private fun removeOverlay() {
        overlay?.setOnTouchListener(null)
        overlay?.let { container.removeView(it) }
        overlay = null
    }

    companion object {
        private const val TAG = "TabPeekController"
        const val ACTION_OPEN_TAB = "open_tab"
        const val ACTION_COPY = "copy_link"
        const val ACTION_SHARE = "share_link"
        const val ACTION_DOWNLOAD = "download_file"
    }
}

private val DOWNLOADABLE_EXTENSIONS = setOf(
    // Audio
    "mp3", "m4a", "aac", "flac", "wav", "wave", "ogg", "oga", "opus", "weba",
    // Video
    "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m3u8",
    // Archives, Documents & Packages
    "pdf", "zip", "rar", "7z", "tar", "gz", "apk", "bin", "exe", "dmg", "iso",
)

fun isDownloadableUrl(url: String): Boolean {
    if (url.isBlank()) return false
    if (ClarusTheaterActivity.isAudioUrl(url) ||
        ClarusTheaterActivity.isVideoUrl(url) ||
        ClarusTheaterActivity.isPlayableMedia(url)
    ) {
        return true
    }
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    val ext = clean.substringAfterLast('.', "")
    return ext in DOWNLOADABLE_EXTENSIONS
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun cleanPeekUrl(rawUrl: String): String {
    if (rawUrl.contains("google.com/url?") || rawUrl.contains("google.co/url?")) {
        runCatching {
            val uri = Uri.parse(rawUrl)
            val paramUrl = uri.getQueryParameter("url")
            val paramQ = uri.getQueryParameter("q")
            val target = paramUrl ?: paramQ
            if (!target.isNullOrBlank() && (target.startsWith("http://") || target.startsWith("https://"))) {
                return target
            }
        }
    }
    return rawUrl
}

fun resolvePeekUrl(rawUrl: String, baseUrl: String?): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return trimmed
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
        trimmed.startsWith("about:") || trimmed.startsWith("javascript:") ||
        trimmed.startsWith("data:")
    ) {
        return cleanPeekUrl(trimmed)
    }
    if (baseUrl.isNullOrBlank()) return cleanPeekUrl(trimmed)
    return try {
        val baseUri = java.net.URI.create(baseUrl)
        val resolved = baseUri.resolve(trimmed).toString()
        cleanPeekUrl(resolved)
    } catch (e: Throwable) {
        cleanPeekUrl(trimmed)
    }
}

fun HitResult.isPeekableLink(): Boolean {
    val url = getUrl()
    return url.isNotBlank() &&
        !url.startsWith("about:") &&
        !url.startsWith("javascript:") &&
        (this is HitResult.UNKNOWN || this is HitResult.IMAGE_SRC || this is HitResult.IMAGE)
}

private fun HitResult.linkLabel(): String? = when (this) {
    is HitResult.UNKNOWN -> linkText
    is HitResult.IMAGE -> title
    else -> null
}
