package dev.doona.android.testing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets

/** A phone's status bar (time, Wi-Fi, battery) and gesture handle, drawn over the app in the theme's text colour. */
@Composable
fun PhoneFrame(content: @Composable () -> Unit) {
    val ink = DoonaTheme.colors.text
    CompositionLocalProvider(LocalFrameInsets provides FrameInsets) {
        Box(Modifier.fillMaxSize()) {
            content()
            Row(Modifier.fillMaxWidth().height(StatusBar).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("10:24", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(16.dp)) {
                    val wifi = Path().apply {
                        moveTo(size.width / 2, size.height * 0.9f)
                        lineTo(0f, size.height * 0.3f)
                        quadraticTo(size.width / 2, -size.height * 0.1f, size.width, size.height * 0.3f)
                        close()
                    }
                    drawPath(wifi, ink)
                }
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
                    val r = CornerRadius(3.dp.toPx())
                    drawRoundRect(ink, size = Size(size.width - 2.dp.toPx(), size.height), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
                    drawRoundRect(ink, topLeft = Offset(2.5.dp.toPx(), 2.5.dp.toPx()), size = Size((size.width - 7.dp.toPx()) * 0.8f, size.height - 5.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()))
                    drawRect(ink, topLeft = Offset(size.width - 1.5.dp.toPx(), size.height * 0.3f), size = Size(1.5.dp.toPx(), size.height * 0.4f))
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(width = 108.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ink),
            )
        }
    }
}
