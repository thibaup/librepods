package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: LayerBackdrop,
    skipPartiallyExpanded: Boolean = false,
    gesturesEnabled: Boolean = true,
    sheetState: SheetState? = null,
    content: @Composable (innerBackdrop: LayerBackdrop, progress: Float) -> Unit
) {
    if (!visible) return

    val isDarkTheme = isSystemInDarkTheme()
    val internalSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded
    )
    val resolvedSheetState = sheetState ?: internalSheetState

    val isExpanded = resolvedSheetState.targetValue == SheetValue.Expanded

    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        label = "sheetProgress"
    )

    val animatedCorner = lerp(48.dp, 42.dp, progress)
    val shape = RoundedCornerShape(animatedCorner)
    val innerBackdrop = rememberLayerBackdrop()

    when (LocalDesignSystem.current) {
        DesignSystem.Apple -> {
            val edgeColor = if (isDarkTheme) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color.White.copy(alpha = 0.72f)
            }
            val fallbackTint = if (isDarkTheme) {
                Color(0xFF1C1C1E).copy(alpha = 0.92f)
            } else {
                Color(0xFFF2F2F7).copy(alpha = 0.88f)
            }

            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = resolvedSheetState,
                sheetGesturesEnabled = gesturesEnabled,
                containerColor = Color.Transparent,
                dragHandle = { },
                shape = shape,
                scrimColor = Color.Black.copy(alpha = if (isDarkTheme) 0.22f else 0.16f),
                modifier = Modifier.padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .drawBackdrop(
                            backdrop = backdrop,
                            exportedBackdrop = innerBackdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(14.dp.toPx())
                                lens(
                                    refractionHeight = 5.dp.toPx(),
                                    refractionAmount = 10.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = false
                                )
                            },
                            onDrawSurface = { drawRect(fallbackTint) }
                        )
                        .border(1.dp, edgeColor, shape)
                        .padding(top = 24.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    content(innerBackdrop, progress)
                }
            }
        }

        DesignSystem.Material -> {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = resolvedSheetState,
                sheetGesturesEnabled = gesturesEnabled,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
                dragHandle = { },
                shape = shape,
                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                modifier = Modifier.padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    content(innerBackdrop, progress)
                }
            }
        }
    }
}
