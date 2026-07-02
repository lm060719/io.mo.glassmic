package io.mo.glassmic.service

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBg = Color(0xEE1C1C20)
private val WaveColor = Color(0xFF34C759)
private val OnDark = Color.White

/**
 * 实时波形悬浮窗内容。中心镜像柱状图，随 [samples] 滚动刷新。
 * 面板可整体拖动；显示与隐藏由设置里的开关控制。透明度由 [opacity] 控制。
 */
@Composable
fun WaveformOverlay(
    samples: FloatArray,
    opacity: Float,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .alpha(opacity.coerceIn(0.15f, 1f))
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PanelBg)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
                    change.consume()
                    onDragBy(drag.x, drag.y)
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "实时波形",
            color = OnDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val n = samples.size
            if (n == 0) return@Canvas
            val midY = size.height / 2f
            val barW = size.width / n
            val stroke = (barW * 0.6f).coerceAtLeast(1.5f)
            for (i in 0 until n) {
                val amp = samples[i].coerceIn(0f, 1f)
                val h = amp * size.height * 0.92f
                val x = i * barW + barW / 2f
                drawLine(
                    color = WaveColor,
                    start = Offset(x, midY - h / 2f),
                    end = Offset(x, midY + h / 2f),
                    strokeWidth = stroke
                )
            }
        }
    }
}
