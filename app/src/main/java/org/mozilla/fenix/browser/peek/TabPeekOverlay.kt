/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.peek

import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.fenix.R
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.Theme
import org.mozilla.fenix.theme.glass.ClarusGlassSurface
import org.mozilla.fenix.theme.glass.ClarusGlassTokens

/**
 * Option B: Full-sheet Link Peek:
 * - 94% screen width, 87% screen height, 24dp rounded corners.
 * - Dimmed backdrop scrim over the current webpage.
 * - Header with security lock, bold domain, URL caption, Link Peek / Pinned badge.
 * - Large live preview viewport with Compose key-keyed AndroidView.
 * - Radial gesture HUD guide (Up = New Tab, Left = Private, Right = Pin, Down = Actions).
 * - Bottom docked action bar.
 */
@Composable
fun TabPeekOverlay(
    state: TabPeekUiState,
    theme: Theme,
    previewFactory: ((FrameLayout) -> Unit)? = null,
    onAction: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    onCommit: () -> Unit = {},
    onOpenPrivate: () -> Unit = {},
    onPin: () -> Unit = {},
    onDrag: (upPx: Float, leftPx: Float) -> Unit = { _, _ -> },
    onActionBounds: (id: String, bounds: Rect) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val peeking = state as? TabPeekUiState.Peeking ?: return
    val isDark = theme == Theme.Dark || theme == Theme.Private
    val isPrivate = theme == Theme.Private

    val domain = remember(peeking.content.url) { extractDomain(peeking.content.url) }

    val scale by animateFloatAsState(
        targetValue = 1f + peeking.commitProgress * 0.03f + peeking.privateProgress * 0.03f,
        tween(100),
        label = "PeekScale",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.60f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ClarusGlassSurface(
            modifier = Modifier
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    enabled = !peeking.isPinned,
                    onClick = {},
                )
                .graphicsLayer {
                    scaleX = 1f
                    scaleY = 1f
                }
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.96f),
            shape = RoundedCornerShape(20.dp),
            elevation = ClarusGlassTokens.Elevation.Level3,
            isDarkTheme = isDark,
            isPrivate = isPrivate,
            isActive = peeking.activeGesture != PeekGestureTarget.NONE,
            blurRadius = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                // Header row (fixed height so nothing spills out across font scales)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Lock icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPrivate) Color(0xFFF59E0B).copy(alpha = 0.25f)
                                else Color.White.copy(alpha = 0.12f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPrivate) R.drawable.ic_clarus_private_mode
                                else iconsR.drawable.mozac_ic_lock_24,
                            ),
                            contentDescription = null,
                            tint = if (isPrivate) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    // Column (domain and URL, each maxLines 1, ellipsis) with weight 1
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = domain,
                            style = FirefoxTheme.typography.subtitle1.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = peeking.content.url,
                            style = FirefoxTheme.typography.caption.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Badge (single-line, never shrinks)
                    Box(
                        modifier = Modifier
                            .wrapContentWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (peeking.isPinned) Color(0xFFF59E0B).copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.08f),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (peeking.isPinned) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(iconsR.drawable.mozac_ic_pin_24),
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "Pinned",
                                    style = FirefoxTheme.typography.caption.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = Color(0xFFF59E0B),
                                    softWrap = false,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Text(
                                text = "Link Peek",
                                style = FirefoxTheme.typography.caption.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = Color(0xFFF59E0B),
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Live Preview Viewport
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.32f)),
                ) {
                    if (previewFactory != null) {
                        AndroidView(
                            factory = { ctx -> FrameLayout(ctx).also { previewFactory(it) } },
                            update = { /* Side-effect free: do not re-add or re-attach engine view */ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // TEMPORARY ON-SCREEN DEBUG LINE (ITEM 1b)
                    val d = peeking.debugInfo
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "DEBUG: peek=#${d.peekId} sess=${d.sessionId} open=${d.isOpen} act=${d.isActive} refl=${if (d.reflOk) "ok" else "FAIL"} gv_att=${d.isAttached} size=${d.viewWidth}x${d.viewHeight} prog=${d.loadProgress}% title='${d.pageTitle.take(15)}' crash=${d.crashEvent} prev_closed=${if (d.prevClosed) "yes" else "no"} main_att=${d.mainAttached} main_active=${d.mainActive}",
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                color = Color(0xFFFBBF24),
                                lineHeight = 11.sp,
                            ),
                        )
                    }

                    // Loading spinner
                    if (!peeking.previewReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFFF59E0B),
                                    strokeWidth = 2.5.dp,
                                )
                                Text(
                                    text = if (peeking.previewTimedOut) "Still loading..." else "Loading preview...",
                                    style = FirefoxTheme.typography.caption,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    // Radial Gesture HUD Guide (Active when dragging and not pinned)
                    if (!peeking.isPinned) {
                        RadialGestureHud(
                            peeking = peeking,
                            isDark = isDark,
                            isPrivate = isPrivate,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Docked Action Bar at bottom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    peeking.actions.forEach { action ->
                        PeekActionButton(
                            id = action.id,
                            label = action.label,
                            isPrimary = action.isPrimary,
                            isHovered = peeking.hoveredActionId == action.id,
                            isDark = isDark,
                            isPrivate = isPrivate,
                            onClick = { onAction(action.id) },
                            onBoundsChanged = onActionBounds,
                            modifier = Modifier.weight(if (action.isPrimary) 1.35f else 1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Radial Gesture Guide Overlay:
 * Shows 4 subtle directional HUD capsules (Up: New Tab, Left: Private, Right: Pin, Down: Actions)
 * and highlights whichever direction the finger is currently dragging towards.
 */
@Composable
private fun RadialGestureHud(
    peeking: TabPeekUiState.Peeking,
    isDark: Boolean,
    isPrivate: Boolean,
    modifier: Modifier = Modifier,
) {
    val isAnyDrag = peeking.dragUpPx > 15f || peeking.dragLeftPx > 15f ||
        peeking.dragRightPx > 15f || peeking.dragDownPx > 15f

    AnimatedVisibility(
        visible = isAnyDrag,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // UP: New Tab
            GestureIndicator(
                label = "New Tab",
                iconRes = iconsR.drawable.mozac_ic_tab_24,
                isActive = peeking.activeGesture == PeekGestureTarget.NEW_TAB,
                progress = peeking.commitProgress,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // LEFT: Private Tab
            GestureIndicator(
                label = "Private",
                iconRes = R.drawable.ic_clarus_private_mode,
                isActive = peeking.activeGesture == PeekGestureTarget.PRIVATE_TAB,
                progress = peeking.privateProgress,
                modifier = Modifier.align(Alignment.CenterStart),
            )

            // RIGHT: Pin
            GestureIndicator(
                label = "Pin",
                iconRes = iconsR.drawable.mozac_ic_pin_24,
                isActive = peeking.activeGesture == PeekGestureTarget.PIN,
                progress = peeking.pinProgress,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // DOWN: Actions
            GestureIndicator(
                label = "Actions",
                iconRes = iconsR.drawable.mozac_ic_ellipsis_vertical_24,
                isActive = peeking.activeGesture == PeekGestureTarget.ACTIONS,
                progress = peeking.actionsProgress,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun GestureIndicator(
    label: String,
    iconRes: Int,
    isActive: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val scale = if (isActive) (1f + progress * 0.18f).coerceIn(1f, 1.25f) else 0.95f
    val alpha = if (isActive) (0.80f + progress * 0.20f).coerceIn(0.80f, 1f) else 0.45f

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isActive) {
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFF59E0B).copy(alpha = (0.35f + progress * 0.55f).coerceIn(0.35f, 0.95f)),
                            Color(0xFFD97706).copy(alpha = (0.25f + progress * 0.55f).coerceIn(0.25f, 0.90f)),
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.40f),
                        ),
                    )
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (isActive) Color(0xFF1C1410).takeIf { progress >= 0.9f } ?: Color(0xFFF59E0B) else Color.White.copy(alpha = alpha),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = FirefoxTheme.typography.caption.copy(
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (isActive && progress >= 0.9f) Color(0xFF1C1410) else Color.White.copy(alpha = alpha),
            )
        }
    }
}

@Composable
private fun PeekActionButton(
    id: String,
    label: String,
    isPrimary: Boolean,
    isHovered: Boolean,
    isDark: Boolean,
    isPrivate: Boolean,
    onClick: () -> Unit,
    onBoundsChanged: ((String, Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isActive = isPrimary || isHovered
    Box(
        modifier = modifier
            .height(48.dp)
            .onGloballyPositioned { coords ->
                onBoundsChanged?.invoke(id, coords.boundsInWindow())
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ClarusGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp)),
            shape = ClarusGlassTokens.Shapes.Pill,
            elevation = if (isActive) ClarusGlassTokens.Elevation.Level3 else ClarusGlassTokens.Elevation.Level1,
            isDarkTheme = isDark,
            isPrivate = isPrivate,
            isActive = isActive,
            blurRadius = ClarusGlassTokens.Blur.Subtle,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (isActive) {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFFF59E0B).copy(alpha = 0.95f),
                                    Color(0xFFD97706).copy(alpha = 0.90f),
                                ),
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.10f),
                                    Color.White.copy(alpha = 0.06f),
                                ),
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = FirefoxTheme.typography.button.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = if (isActive) Color(0xFF1C1410) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun extractDomain(url: String): String {
    return runCatching {
        val uri = Uri.parse(url)
        val host = uri.host
        if (!host.isNullOrBlank()) {
            host.removePrefix("www.")
        } else {
            url
        }
    }.getOrDefault(url)
}
