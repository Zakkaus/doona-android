package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Vertical gap between blocks; pass a role of `DoonaTheme.spacing`. */
@Composable
fun DoonaGap(size: Dp) = Spacer(Modifier.height(size))

/** Horizontal gap between inline items; pass a role of `DoonaTheme.spacing`. */
@Composable
fun DoonaHGap(size: Dp) = Spacer(Modifier.width(size))
