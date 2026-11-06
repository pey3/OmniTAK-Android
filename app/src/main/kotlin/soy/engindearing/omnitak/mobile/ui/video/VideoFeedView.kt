package soy.engindearing.omnitak.mobile.ui.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import soy.engindearing.omnitak.mobile.data.video.FeedHealth
import soy.engindearing.omnitak.mobile.data.video.FeedStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status colours: green only when frames are arriving. */
object FeedColors {
    val Live = Color(0xFF22C55E)
    val Connecting = Color(0xFF38BDF8)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFF44336)
    val Idle = Color(0xFF6B7280)
    val Scrim = Color(0xCC0F1115)

    fun forStatus(s: FeedStatus): Color = when (s) {
        FeedStatus.LIVE -> Live
        FeedStatus.CONNECTING -> Connecting
        FeedStatus.STALE, FeedStatus.RECONNECTING -> Warning
        FeedStatus.ERROR -> Error
        FeedStatus.IDLE -> Idle
    }
}

/**
 * Monotonic clock (same base as [VideoFeedController]'s health times) that
 * recomposes every [periodMs]; drives the "x s ago" text.
 */
@Composable
fun rememberNow(periodMs: Long = 500): State<Long> {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = SystemClock.elapsedRealtime()
            delay(periodMs)
        }
    }
    return now
}

/** "3 s", "2 min", "1 h". */
fun formatAge(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
}

/** Short badge text for [h] at [nowMs]. */
fun badgeText(h: FeedHealth, nowMs: Long): String {
    val age = h.frameAgeMs(nowMs)
    return when (h.status) {
        FeedStatus.LIVE -> "LIVE"
        FeedStatus.STALE -> "STALE ${age?.let(::formatAge) ?: ""}".trim()
        FeedStatus.CONNECTING -> "CONNECTING"
        FeedStatus.RECONNECTING -> {
            val inMs = (h.nextRetryAtMs ?: nowMs) - nowMs
            if (inMs > 500) "RECONNECTING ${formatAge(inMs + 999)}" else "RECONNECTING"
        }
        FeedStatus.ERROR -> "ERROR"
        FeedStatus.IDLE -> "PAUSED"
    }
}

/**
 * Video picture for [controller], letter-boxed to the stream's aspect
 * ratio. While the feed is not LIVE the last frame stays visible but is
 * dimmed and labelled, so a frozen picture can never pass for live.
 * Playback pauses while the app is in the background (not in Android
 * picture-in-picture, where the activity is only paused).
 */
@Composable
fun VideoFeedView(
    controller: VideoFeedController,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val health by controller.health.collectAsState()
    val stats by controller.stats.collectAsState()
    val now by rememberNow()
    val view = remember(controller) { TextureView(context) }

    DisposableEffect(controller, view) {
        controller.attach(view)
        onDispose { controller.detach(view) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.stop()
                Lifecycle.Event.ON_START -> controller.start()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ratio = if (stats.width > 0 && stats.height > 0) stats.width.toFloat() / stats.height else 16f / 9f
    val notLive = health.status != FeedStatus.LIVE
    val hasPicture = health.lastFrameAtMs != null

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.aspectRatio(ratio))
        if (notLive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (hasPicture) Color(0x99000000) else Color.Black)
                    .border(3.dp, FeedColors.forStatus(health.status)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (health.status == FeedStatus.CONNECTING || health.status == FeedStatus.RECONNECTING) {
                        CircularProgressIndicator(
                            color = FeedColors.forStatus(health.status),
                            modifier = Modifier.size(if (compact) 22.dp else 40.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                    val age = health.frameAgeMs(now)
                    val headline = when (health.status) {
                        FeedStatus.STALE -> "FROZEN — last frame ${age?.let(::formatAge) ?: "?"} ago"
                        FeedStatus.RECONNECTING -> if (age != null) "NOT LIVE — last frame ${formatAge(age)} ago" else "Reconnecting…"
                        FeedStatus.CONNECTING -> if (age != null) "NOT LIVE — last frame ${formatAge(age)} ago" else "Connecting…"
                        FeedStatus.ERROR -> "No video"
                        FeedStatus.IDLE -> "Paused"
                        FeedStatus.LIVE -> ""
                    }
                    Text(
                        headline,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 11.sp else 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    val detail = health.error
                    if (!detail.isNullOrBlank() && health.status != FeedStatus.LIVE) {
                        Text(
                            detail,
                            color = Color(0xFFE5E7EB),
                            fontSize = if (compact) 10.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = if (compact) 2 else 4,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status pill: CONNECTING / LIVE / RECONNECTING / STALE / ERROR, with the
 * age of the last decoded frame whenever the feed is not LIVE.
 */
@Composable
fun FeedStatusBadge(health: FeedHealth, nowMs: Long, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = FeedColors.forStatus(health.status)
    Row(
        modifier
            .background(Color(0xE6000000), RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = if (large) 10.dp else 6.dp, vertical = if (large) 5.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(if (large) 10.dp else 7.dp).background(color, CircleShape))
        Text(
            badgeText(health, nowMs),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 14.sp else 11.sp,
        )
    }
}

/** "1280×720 · 30 fps · 2.1 Mb/s · 2.4 s behind" — unknown parts omitted. */
fun statsText(s: FeedStats): String = buildList {
    if (s.width > 0) add("${s.width}×${s.height}")
    s.fps?.let { add("%.0f fps".format(Locale.US, it)) }
    s.bitrateKbps?.let { add(if (it >= 1000) "%.1f Mb/s".format(Locale.US, it / 1000f) else "$it kb/s") }
    s.liveOffsetMs?.let { add("%.1f s behind".format(Locale.US, it / 1000f)) }
}.joinToString(" · ")

/** Minimum touch target for gloved use. */
val GloveTarget: Dp = 56.dp

/**
 * Saves a snapshot JPEG. Android 10+: MediaStore `Pictures/OmniTAK` (no
 * permission needed). Older: the app's own external Pictures folder.
 * Returns a human-readable location, or null on failure.
 */
object SnapshotSaver {
    fun save(context: Context, bitmap: Bitmap, alias: String): String? = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeAlias = alias.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val name = "OmniTAK-$safeAlias-$stamp.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniTAK")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: return null
            "Pictures/OmniTAK/$name"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OmniTAK").apply { mkdirs() }
            val f = File(dir, name)
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            f.absolutePath
        }
    }.getOrNull()
}
