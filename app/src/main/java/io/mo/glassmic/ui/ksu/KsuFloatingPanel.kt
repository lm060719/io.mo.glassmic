package io.mo.glassmic.ui.ksu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.mo.glassmic.R
import io.mo.glassmic.root.KsuController
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.glass

@Composable
fun KsuFloatingPanel(controller: KsuController, running: Boolean, expanded: Boolean,
                     maxWidth: Dp, maxHeight: Dp, size: Dp, opacity: Float,
                     onExpand: () -> Unit, onCollapse: () -> Unit, onClose: () -> Unit,
                     onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, amount ->
            change.consume()
            onDrag(amount.x, amount.y)
        }
    }
    if (!expanded) {
        GlassCard(Modifier.size(size).then(drag).graphicsLayer { alpha = opacity }, radius = size / 2,
            contentPadding = PaddingValues(0.dp), onClick = onExpand) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("KSU", color = glass.primaryInk) }
        }
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    GlassCard(Modifier.width(340.dp.coerceAtMost(maxWidth)).heightIn(max = 580.dp.coerceAtMost(maxHeight))
        .graphicsLayer { alpha = opacity }) {
        Row(Modifier.fillMaxWidth().then(drag), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.backend_ksu), Modifier.weight(1f), color = glass.primaryInk)
            SoftButton("−", onCollapse)
            Spacer(Modifier.width(6.dp))
            SoftButton("×", onClose)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                KsuStatus(state)
                SoftButton(stringResource(R.string.ksu_refresh), controller::refreshNow,
                    enabled = running && !state.busy && !state.stopping)
                KsuPlaybackControls(state, running, controller)
            }
            items(state.snapshot?.files.orEmpty(), key = { it }) { name ->
                Text(name, Modifier.fillMaxWidth().clickable(enabled = running && state.connected && !state.busy && !state.stopping) {
                    controller.command("load", listOf(name))
                }.padding(vertical = 10.dp), color = if (name == state.snapshot?.selected) glass.primaryInk else glass.ink)
            }
        }
    }
}
