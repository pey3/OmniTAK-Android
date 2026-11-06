package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import soy.engindearing.omnitak.mobile.data.uas.VideoSource
import soy.engindearing.omnitak.mobile.data.uas.toVideoFeed
import soy.engindearing.omnitak.mobile.ui.video.FeedStatusBadge
import soy.engindearing.omnitak.mobile.ui.video.VideoFeedController
import soy.engindearing.omnitak.mobile.ui.video.VideoFeedView
import soy.engindearing.omnitak.mobile.ui.video.rememberNow

/**
 * Picture-in-picture live video for the drone camera, shown while a UAS is
 * connected. Playback goes through the shared [VideoFeedController] used by
 * Video Feeds, so all three source modes (see [VideoSource]) get the same
 * CONNECTING / LIVE / RECONNECTING / STALE / ERROR badge, auto-reconnect
 * and frozen-frame dimming:
 *
 *  - **RTSP** (default): Media3 RtspMediaSource, RTP-over-TCP forced — UDP
 *    RTP works on a LAN but breaks through SSH tunnels and many home
 *    routers; TCP costs ~10 ms, fine for ops video.
 *  - **Raw H264 UDP**: Annex B NAL units over UDP decoded by MediaCodec
 *    (RubyFPV-style ground stations without RTSP).
 *  - **MPEG-TS UDP**: what ATAK UAS Tool / gstreamer `mpegtsmux ! udpsink` emit.
 *
 * Layout: 160 × 90 dp compact, 320 × 180 dp expanded (camera / gimbal
 * controls appear when expanded). Close tears the player down.
 */
@Composable
fun UasVideoPip(
    source: VideoSource,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit = {},
    onToggleRecord: (Boolean) -> Unit = {},
    onGimbalForward: () -> Unit = {},
    onGimbalNadir: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val feed = remember(source) { source.toVideoFeed() } ?: return
    val context = LocalContext.current
    val controller = remember(feed) {
        VideoFeedController(context, feed, httpClient = { OkHttpClient() }).also { it.start() }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val health by controller.health.collectAsState()
    val now by rememberNow()

    var expanded by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val widthDp = if (expanded) 320.dp else 160.dp

    Box(
        modifier = modifier
            .width(widthDp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        VideoFeedView(controller, Modifier.fillMaxSize(), compact = true)

        FeedStatusBadge(health, now, Modifier.align(Alignment.TopStart).padding(6.dp))

        if (recording) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 30.dp, start = 8.dp)
                    .size(8.dp)
                    .background(Color(0xFFFF3B30), shape = CircleShape),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Toggle size",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(onClick = onPhoto, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Capture photo", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        recording = !recording
                        onToggleRecord(recording)
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (recording) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                        contentDescription = if (recording) "Stop recording" else "Start recording",
                        tint = if (recording) Color(0xFFFF3B30) else Color.White,
                    )
                }
                IconButton(onClick = onGimbalForward, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.CameraFront, contentDescription = "Gimbal forward (0°)", tint = Color.White)
                }
                IconButton(onClick = onGimbalNadir, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = "Gimbal nadir (-90°)", tint = Color.White)
                }
            }
        }
    }
}
