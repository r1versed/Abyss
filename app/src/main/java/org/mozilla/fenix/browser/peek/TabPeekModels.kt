/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.peek

data class TabPeekContent(
    val url: String,
    val title: String?,
)

data class PeekLifecycleEvent(
    val timestampMs: Long,
    val peekId: Long,
    val event: String,
)

data class PeekDebugInfo(
    val peekId: Long = 0L,
    val sessionId: String = "",
    val isOpen: Boolean = false,
    val actReq: Boolean = false,
    val reflOk: Boolean = true,
    val viewHash: String = "none",
    val hostHash: String = "none",
    val displayedViewHash: String = "none",
    val isAttached: Boolean = false,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val parentClass: String = "none",
    val hasWindowToken: Boolean = false,
    val visibility: String = "none",
    val loadProgress: Int = 0,
    val pageTitle: String = "",
    val crashEvent: String = "none",
    val prevClosed: Boolean = true,
    val mainAttached: Boolean = false,
    val mainOpen: Boolean = false,
    val lifecycleHistory: String = "",
)

data class TabPeekAction(
    val id: String,
    val label: String,
    val isPrimary: Boolean = false,
)

enum class PeekGestureTarget {
    NONE,
    NEW_TAB,      // Up
    PRIVATE_TAB,  // Left
    PIN,          // Right
    ACTIONS,      // Down
}

sealed interface TabPeekUiState {
    data object Hidden : TabPeekUiState

    data class Peeking(
        val content: TabPeekContent,
        val actions: List<TabPeekAction> = emptyList(),
        val dragUpPx: Float = 0f,
        val dragLeftPx: Float = 0f,
        val dragRightPx: Float = 0f,
        val dragDownPx: Float = 0f,
        val commitThresholdPx: Float = COMMIT_DRAG_THRESHOLD_DP * 2.75f,
        val privateThresholdPx: Float = PRIVATE_DRAG_THRESHOLD_DP * 2.75f,
        val pinThresholdPx: Float = PIN_DRAG_THRESHOLD_DP * 2.75f,
        val actionsThresholdPx: Float = ACTIONS_DRAG_THRESHOLD_DP * 2.75f,
        val previewReady: Boolean = false,
        val previewTimedOut: Boolean = false,
        val peekId: Long = 0L,
        val activeGesture: PeekGestureTarget = PeekGestureTarget.NONE,
        val isPinned: Boolean = false,
        val hoveredActionId: String? = null,
        val debugInfo: PeekDebugInfo = PeekDebugInfo(),
    ) : TabPeekUiState {
        val commitProgress: Float
            get() = (dragUpPx / commitThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val privateProgress: Float
            get() = (dragLeftPx / privateThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val pinProgress: Float
            get() = (dragRightPx / pinThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val actionsProgress: Float
            get() = (dragDownPx / actionsThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    data object Committing : TabPeekUiState
    data object Canceling : TabPeekUiState
}

const val COMMIT_DRAG_THRESHOLD_DP: Float = 140f
const val PRIVATE_DRAG_THRESHOLD_DP: Float = 130f
const val PIN_DRAG_THRESHOLD_DP: Float = 130f
const val ACTIONS_DRAG_THRESHOLD_DP: Float = 130f
const val COMMIT_DRAG_THRESHOLD_PX: Float = 400f
const val PRIVATE_DRAG_THRESHOLD_PX: Float = 360f
const val SNAP_SHUT_MS: Int = 160
