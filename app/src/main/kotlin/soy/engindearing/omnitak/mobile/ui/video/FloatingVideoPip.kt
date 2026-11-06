package soy.engindearing.omnitak.mobile.ui.video

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.data.video.FeedStatus
import kotlin.math.roundToInt

/**
 * Video window floating over the map. Drag anywhere on the picture to
 * move it; drag the bottom-right handle to resize. Works for any feed —
 * no drone or MAVLink link needed.
 *
 * Fill the parent (it positions itself inside); it starts top-right, below
 * the status bar, clear of the map controls.
 */
@Composable
fun FloatingVideoPip(
    controller: VideoFeedController,
    title: String,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val health by controller.health.collectAsState()
    val muted by controller.muted.collectAsState()
    val now by rememberNow()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val minW = with(density) { 180.dp.toPx() }
        val headerPx = with(density) { 40.dp.toPx() }
        // Raw state; clamped where it is used (no state writes during
        // composition), so a rotation that shrinks the screen just pulls
        // the window back inside.
        var rawWidth by rememberSaveable { mutableFloatStateOf(with(density) { 260.dp.toPx() }) }
        // Starts top-right, under the status bar: clear of the zoom stack,
        // the self-position card and the bottom toolbar.
        var rawX by rememberSaveable { mutableFloatStateOf(Float.MAX_VALUE) }
        var rawY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
        val widthPx = rawWidth.coerceIn(minW, maxWidthPx.coerceAtLeast(minW))
        val heightPx = widthPx * 9f / 16f + headerPx
        val maxX = (maxWidthPx - widthPx).coerceAtLeast(0f)
        val maxY = (maxHeightPx - heightPx).coerceAtLeast(0f)
        val x = rawX.coerceIn(0f, maxX)
        val y = (if (rawY.isNaN()) with(density) { 130.dp.toPx() } else rawY)
            .coerceIn(0f, maxY)
        val bounds by rememberUpdatedState(Triple(x, y, Pair(maxX, maxY)))
        val moveBy: (Float, Float) -> Unit = { dx, dy ->
            val (cx, cy, m) = bounds
            rawX = (cx + dx).coerceIn(0f, m.first)
            rawY = (cy + dy).coerceIn(0f, m.second)
        }
        val currentWidth by rememberUpdatedState(widthPx)

        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { widthPx.toDp() })
                .clip(RoundedCornerShape(10.dp))
                .background(FeedColors.Scrim)
                .border(2.dp, FeedColors.forStatus(health.status), RoundedCornerShape(10.dp)),
        ) {
            // Header: title + badge + close. Also a drag handle.
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            moveBy(drag.x, drag.y)
                        }
                    }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeedStatusBadge(health, now)
                Text(
                    title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close video", tint = Color.White)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            moveBy(drag.x, drag.y)
                        }
                    },
            ) {
                VideoFeedView(controller, Modifier.fillMaxSize(), compact = true)
                // Bottom controls, glove-sized.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .background(Color(0x99000000), RoundedCornerShape(topEnd = 10.dp)),
                ) {
                    IconButton(onClick = { controller.setMuted(!muted) }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = Color.White,
                        )
                    }
                    if (health.status == FeedStatus.ERROR || health.status == FeedStatus.RECONNECTING ||
                        health.status == FeedStatus.STALE
                    ) {
                        IconButton(onClick = { controller.retryNow() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Retry now", tint = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val bmp = controller.snapshot()
                                val where = bmp?.let { SnapshotSaver.save(context, it, title) }
                                Toast.makeText(
                                    context,
                                    if (where != null) "Snapshot saved: $where" else "No picture to save",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "Snapshot", tint = Color.White)
                        }
                    }
                    IconButton(onClick = onExpand, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "Full screen", tint = Color.White)
                    }
                }
                // Resize handle.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(44.dp)
                        .background(Color(0x99000000), RoundedCornerShape(topStart = 10.dp))
                        .pointerInput(maxWidthPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                rawWidth = (currentWidth + drag.x).coerceIn(minW, maxWidthPx.coerceAtLeast(minW))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.SouthEast, contentDescription = "Resize", tint = Color.White)
                }
            }
        }
    }
}
