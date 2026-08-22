package me.kavishdevar.librepods.presentation.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.body
import me.kavishdevar.librepods.presentation.theme.bodyEmphasized
import me.kavishdevar.librepods.presentation.theme.display
import me.kavishdevar.librepods.presentation.theme.displayEmphasized
import me.kavishdevar.librepods.presentation.theme.label
import me.kavishdevar.librepods.presentation.theme.labelEmphasized

/** Keeps Material Expressive compact inside Find My without changing the rest of the app. */
@Composable
internal fun FindMyCompactTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = if (LocalDesignSystem.current == DesignSystem.Material) {
        base.copy(
            displaySmall = base.displaySmall.copy(
                fontFamily = display,
                fontSize = 28.sp,
                lineHeight = 32.sp,
            ),
            displayMedium = base.displayMedium.copy(
                fontFamily = display,
                fontSize = 32.sp,
                lineHeight = 36.sp,
            ),
            displayLarge = base.displayLarge.copy(
                fontFamily = display,
                fontSize = 36.sp,
                lineHeight = 40.sp,
            ),
            displaySmallEmphasized = base.displaySmallEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 28.sp,
                lineHeight = 32.sp,
            ),
            displayMediumEmphasized = base.displayMediumEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 32.sp,
                lineHeight = 36.sp,
            ),
            displayLargeEmphasized = base.displayLargeEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 36.sp,
                lineHeight = 40.sp,
            ),
            headlineSmall = base.headlineSmall.copy(
                fontFamily = display,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
            headlineMedium = base.headlineMedium.copy(
                fontFamily = display,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            headlineLarge = base.headlineLarge.copy(
                fontFamily = display,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
            headlineSmallEmphasized = base.headlineSmallEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
            headlineMediumEmphasized = base.headlineMediumEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            headlineLargeEmphasized = base.headlineLargeEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
            titleSmall = base.titleSmall.copy(
                fontFamily = display,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
            titleMedium = base.titleMedium.copy(
                fontFamily = display,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
            titleLarge = base.titleLarge.copy(
                fontFamily = display,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            titleSmallEmphasized = base.titleSmallEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
            titleMediumEmphasized = base.titleMediumEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
            titleLargeEmphasized = base.titleLargeEmphasized.copy(
                fontFamily = displayEmphasized,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            bodySmall = base.bodySmall.copy(
                fontFamily = body,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            bodyMedium = base.bodyMedium.copy(
                fontFamily = body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            bodyLarge = base.bodyLarge.copy(
                fontFamily = body,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            bodySmallEmphasized = base.bodySmallEmphasized.copy(
                fontFamily = bodyEmphasized,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            bodyMediumEmphasized = base.bodyMediumEmphasized.copy(
                fontFamily = bodyEmphasized,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            bodyLargeEmphasized = base.bodyLargeEmphasized.copy(
                fontFamily = bodyEmphasized,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            labelSmall = base.labelSmall.copy(
                fontFamily = label,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelMedium = base.labelMedium.copy(
                fontFamily = label,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            ),
            labelLarge = base.labelLarge.copy(
                fontFamily = label,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
            labelSmallEmphasized = base.labelSmallEmphasized.copy(
                fontFamily = labelEmphasized,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelMediumEmphasized = base.labelMediumEmphasized.copy(
                fontFamily = labelEmphasized,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            ),
            labelLargeEmphasized = base.labelLargeEmphasized.copy(
                fontFamily = labelEmphasized,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
        )
    } else {
        base
    }

    MaterialTheme(typography = typography, content = content)
}
