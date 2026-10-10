/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.peek

data class TabPeekContent(
    val url: String,
    val title: String?,
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
        val peekId: Long = 0L,
        val activeGesture: PeekGestureTarget = PeekGestureTarget.NONE,
        val isPinned: Boolean = false,
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
