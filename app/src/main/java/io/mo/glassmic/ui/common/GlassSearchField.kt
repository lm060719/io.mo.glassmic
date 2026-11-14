package io.mo.glassmic.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 胶囊搜索框：放大镜 + 单行输入 + 非空时的清除键。
 * [onCard] 为 true 时用卡片磨砂底（页面上），false 时用浅填充底（底部面板里）。
 */
@Composable
fun GlassSearchField(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onCard: Boolean = true
) {
    val t = glass
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .then(if (onCard) Modifier.frosted().background(t.card) else Modifier.background(t.fill))
            .border(BorderStroke(1.dp, t.border), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = t.ink.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = t.ink),
            cursorBrush = SolidColor(t.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(hint, fontSize = 15.sp, color = t.ink3, maxLines = 1)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, null, tint = t.ink3,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { onQuery("") }
            )
        }
    }
}
