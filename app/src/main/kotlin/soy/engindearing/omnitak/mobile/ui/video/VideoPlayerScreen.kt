package soy.engindearing.omnitak.mobile.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.video.FeedStatus
import soy.engindearing.omnitak.mobile.data.video.VideoFeedUrls

/**
 * Bridge between the full-screen player and [soy.engindearing.omnitak.mobile.MainActivity]
 * for Android picture-in-picture: while a feed is open full screen, leaving
 * the app (Home / app switch) shrinks it to a system PiP window instead of
 * stopping the video.
 */
object PlayerWindow {
    @Volatile var fullscreenActive: Boolean = false
    @Volatile var aspect: Rational = Rational(16, 9)

    private val _inPictureInPicture = MutableStateFlow(false)
    val inPictureInPicture: StateFlow<Boolean> = _inPictureInPicture.asStateFlow()

    fun onPictureInPictureChanged(inPip: Boolean) {
        _inPictureInPicture.value = inPip
    }
}

/**
 * Full-screen player for the feed the [soy.engindearing.omnitak.mobile.domain.VideoFeedManager]
 * is currently playing. Landscape-friendly (follows the sensor), immersive,
 * glove-sized controls along the bottom so it works one-handed.
 */
@Composable
fun VideoPlayerScreen(onBack: () -> Unit, onShowOnMap: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val manager = app.videoFeeds
    val controller = manager.playing.collectAsState().value
    val inPip by PlayerWindow.inPictureInPicture.collectAsState()

    // Set when this screen itself navigates away, so the controller going
    // null as playback closes does not trigger a second back navigation.
    var exiting by remember { mutableStateOf(false) }
    if (controller == null) {
        // Nothing playing (e.g. the process was recreated): leave once.
        if (!exiting) LaunchedEffect(Unit) { exiting = true; onBack() }
        return
    }

    val health by controller.health.collectAsState()
    val stats by controller.stats.collectAsState()
    val muted by controller.muted.collectAsState()
    val now by rememberNow()

    // Immersive while on screen; system bars come back on exit.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        PlayerWindow.fullscreenActive = true
        onDispose {
            PlayerWindow.fullscreenActive = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(stats.width, stats.height) {
        if (stats.width > 0 && stats.height > 0) {
            // Android clamps PiP ratios to 1:2.39 … 2.39:1.
            val r = (stats.width.toFloat() / stats.height).coerceIn(0.42f, 2.39f)
            PlayerWindow.aspect = Rational((r * 1000).toInt(), 1000)
        }
    }

    val leave = {
        exiting = true
        onBack()
        // Back = stop, unless the map PiP is showing this feed.
        if (manager.pipFeedId.value != controller.feed.id) manager.closePlayback()
    }
    BackHandler(onBack = leave)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoFeedView(controller, Modifier.fillMaxSize(), compact = inPip)
        if (inPip) {
            FeedStatusBadge(health, now, Modifier.align(Alignment.TopStart).padding(4.dp))
            return@Box
        }

        // Top bar.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BigIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = leave)
            Column(Modifier.weight(1f)) {
                Text(
                    controller.feed.alias,
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${controller.feed.protocol.label} · ${VideoFeedUrls.redact(controller.feed.url)}",
                    color = Color(0xFFB0B0B0), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FeedStatusBadge(health, now, large = true)
        }

        // Bottom controls.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .safeDrawingPadding()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val line = statsText(stats)
            val age = health.frameAgeMs(now)
            val ageText = when {
                age == null -> "no frame yet"
                health.status == FeedStatus.LIVE -> "last frame ${age} ms ago"
                else -> "last frame ${formatAge(age)} ago"
            }
            Text(
                listOf(ageText, line).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color(0xFFE5E7EB), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BigIconButton(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    if (muted) "Unmute" else "Mute",
                ) { controller.setMuted(!muted) }
                BigIconButton(Icons.Filled.PhotoCamera, "Snapshot") {
                    val bmp = controller.snapshot()
                    val where = bmp?.let { SnapshotSaver.save(context, it, controller.feed.alias) }
                    Toast.makeText(
                        context,
                        if (where != null) "Snapshot saved: $where" else "No picture to save",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                BigIconButton(Icons.Filled.Refresh, "Reconnect now") { controller.retryNow() }
                Button(
                    onClick = {
                        exiting = true
                        manager.showPip(controller.feed)
                        onShowOnMap()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80), contentColor = Color(0xFF0A1628)),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.size(width = 150.dp, height = GloveTarget),
                ) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("On map", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BigIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(GloveTarget)
            .background(Color(0x33FFFFFF), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
