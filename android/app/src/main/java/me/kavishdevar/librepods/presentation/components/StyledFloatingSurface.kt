/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

@Composable
fun StyledFloatingSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val stateModifier = Modifier
        .then(
            if (onClick != null) Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            else Modifier
        )
        .semantics {
            this.selected = selected
            if (!enabled) disabled()
            if (contentDescription != null) this.contentDescription = contentDescription
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        )

    when (LocalDesignSystem.current) {
        DesignSystem.Apple -> {
            val fallbackTint = if (isDarkTheme) {
                Color(0xFF1C1C1E).copy(alpha = if (selected) 0.94f else 0.88f)
            } else {
                Color(0xFFF2F2F7).copy(alpha = if (selected) 0.92f else 0.84f)
            }
            val edgeColor = if (isDarkTheme) {
                Color.White.copy(alpha = if (selected) 0.30f else 0.16f)
            } else {
                Color.White.copy(alpha = if (selected) 0.90f else 0.68f)
            }

            Box(
                modifier = modifier
                    .then(stateModifier)
                    .clip(shape)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            blur(10.dp.toPx())
                            lens(
                                refractionHeight = 4.dp.toPx(),
                                refractionAmount = 8.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = false
                            )
                        },
                        onDrawSurface = {
                            drawRect(fallbackTint)
                            if (selected) drawRect(Color.White.copy(alpha = 0.08f))
                        }
                    )
                    .border(if (selected) 2.dp else 1.dp, edgeColor, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                content = content
            )
        }

        DesignSystem.Material -> {
            Surface(
                modifier = modifier.then(stateModifier),
                shape = shape,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                tonalElevation = if (selected) 4.dp else 2.dp,
                border = if (selected) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                } else {
                    null
                }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}
