/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.theater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.glass.ClarusGlassSurface
import org.mozilla.fenix.theme.glass.ClarusGlassTokens

/**
 * Option 1: Anchored Floating Tactile Card at the bottom of the screen.
 * Non-blocking glass prompt offering Clarus Theater vs Standard Fullscreen.
 * Auto-dismisses after 5 seconds to prevent blocking playback.
 */
@Composable
fun FullscreenChoicePrompt(
    videoTitle: String?,
    isDarkTheme: Boolean,
    isPrivate: Boolean,
    onTheater: (rememberChoice: Boolean) -> Unit,
    onFullscreen: (rememberChoice: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rememberChoice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(5000L)
        onDismiss()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ClarusGlassSurface(
            modifier = Modifier
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            elevation = ClarusGlassTokens.Elevation.Level3,
            isDarkTheme = isDarkTheme,
            isPrivate = isPrivate,
            blurRadius = ClarusGlassTokens.Blur.Heavy,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                // Header row: Icon + Titles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFFF59E0B).copy(alpha = 0.25f),
                                        Color(0xFFD97706).copy(alpha = 0.15f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_theater_fullscreen),
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = videoTitle?.takeIf { it.isNotBlank() } ?: "Fullscreen Media",
                            style = FirefoxTheme.typography.subtitle1.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Choose playback mode",
                            style = FirefoxTheme.typography.caption.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Action buttons: Clarus Theater vs Standard Fullscreen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Clarus Theater button (Primary Gold Accent)
                    ClarusGlassSurface(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTheater(rememberChoice) },
                        shape = RoundedCornerShape(12.dp),
                        elevation = ClarusGlassTokens.Elevation.Level2,
                        isDarkTheme = isDarkTheme,
                        isPrivate = isPrivate,
                        isActive = true,
                        blurRadius = ClarusGlassTokens.Blur.Subtle,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFFF59E0B).copy(alpha = 0.95f),
                                            Color(0xFFD97706).copy(alpha = 0.90f),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Clarus Theater",
                                style = FirefoxTheme.typography.button.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1C1410),
                                maxLines = 1,
                            )
                        }
                    }

                    // Standard Fullscreen button (Secondary Glass)
                    ClarusGlassSurface(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onFullscreen(rememberChoice) },
                        shape = RoundedCornerShape(12.dp),
                        elevation = ClarusGlassTokens.Elevation.Level1,
                        isDarkTheme = isDarkTheme,
                        isPrivate = isPrivate,
                        isActive = false,
                        blurRadius = ClarusGlassTokens.Blur.Subtle,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isDarkTheme) Color.White.copy(alpha = 0.08f)
                                    else Color.Black.copy(alpha = 0.05f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Fullscreen",
                                style = FirefoxTheme.typography.button,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // "Remember my choice" checkbox row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFF59E0B),
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkmarkColor = Color(0xFF1C1410),
                        ),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Remember my choice",
                        style = FirefoxTheme.typography.caption.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
