package soy.engindearing.omnitak.mobile.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.video.VideoFeed
import soy.engindearing.omnitak.mobile.data.video.VideoFeedOrigin
import soy.engindearing.omnitak.mobile.data.video.VideoFeedUrls
import soy.engindearing.omnitak.mobile.data.video.VideoProtocol
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalOnSurfaceMuted
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import soy.engindearing.omnitak.mobile.ui.video.GloveTarget
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Video Feeds (GAP-082): every feed the operator can watch, without a
 * drone — manual feeds saved on this device, feeds the connected TAK
 * server lists (Marti video API), and feeds carried by markers on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoFeedsScreen(
    onBack: () -> Unit,
    onWatch: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val manager = app.videoFeeds
    val manual by manager.manualFeeds.collectAsState()
    val server by manager.serverFeeds.collectAsState()
    val markers by manager.markerFeeds.collectAsState()
    val servers by app.serverManager.servers.collectAsState()
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<VideoFeed?>(null) }
    var sharing by remember { mutableStateOf<VideoFeed?>(null) }
    var deleting by remember { mutableStateOf<VideoFeed?>(null) }

    LaunchedEffect(servers) { manager.refreshServerFeeds() }

    val watch: (VideoFeed) -> Unit = { feed ->
        manager.controllerFor(feed)
        onWatch()
    }
    val pip: (VideoFeed) -> Unit = { feed ->
        manager.showPip(feed)
        onShowOnMap()
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Video Feeds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { manager.refreshServerFeeds() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh server feeds")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = newFeedTemplate() },
                containerColor = TacticalAccent,
                contentColor = TacticalBackground,
            ) { Icon(Icons.Filled.Add, contentDescription = "Add feed") }
        },
    ) { inner: PaddingValues ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader("On this device") }
            if (manual.isEmpty()) {
                item {
                    Hint(
                        "No feeds yet. Tap + to add one: an RTSP camera, a MediaMTX path " +
                            "(rtsp://192.168.50.1:8554/drone), an HLS link, or a UDP port.",
                    )
                }
            }
            items(manual, key = { it.id }) { feed ->
                FeedCard(
                    feed = feed,
                    onWatch = { watch(feed) },
                    onPip = { pip(feed) },
                    onEdit = { editing = feed },
                    onShare = { sharing = feed },
                    onDelete = { deleting = feed },
                )
            }

            val queryable = manager.queryableServers()
            item {
                SectionHeader("From TAK server")
            }
            if (queryable.isEmpty()) {
                item {
                    Hint(
                        "Connect a TAK server with a client certificate (TLS) to list the " +
                            "streams it knows about. OpenTAKServer lists streams published " +
                            "with a server login.",
                    )
                }
            }
            queryable.forEach { srv ->
                val state = server[srv.id]
                item(key = "srvhdr-${srv.id}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            srv.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            state?.loading == true ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            state?.fetchedAtMs != null -> Text(
                                "updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.fetchedAtMs)),
                                color = TacticalOnSurfaceMuted, fontSize = 12.sp,
                            )
                        }
                    }
                }
                state?.error?.let { err ->
                    item(key = "srverr-${srv.id}") { Hint("Couldn't list feeds: $err", color = HostileRed) }
                }
                if (state != null && !state.loading && state.error == null && state.feeds.isEmpty()) {
                    item(key = "srvnone-${srv.id}") { Hint("The server lists no active feeds.") }
                }
                items(state?.feeds.orEmpty(), key = { it.id }) { feed ->
                    FeedCard(
                        feed = feed,
                        onWatch = { watch(feed) },
                        onPip = { pip(feed) },
                        onShare = { sharing = feed },
                    )
                }
            }

            if (markers.isNotEmpty()) {
                item { SectionHeader("On map markers") }
                items(markers, key = { it.id }) { feed ->
                    FeedCard(
                        feed = feed,
                        onWatch = { watch(feed) },
                        onPip = { pip(feed) },
                        onSaveCopy = { editing = feed.copy(id = UUID.randomUUID().toString(), origin = VideoFeedOrigin.MANUAL, sourceUid = null) },
                    )
                }
            }
        }
    }

    editing?.let { feed ->
        FeedEditorDialog(
            initial = feed,
            isNew = manual.none { it.id == feed.id },
            passwordsPersisted = manager.passwordsPersisted,
            onDismiss = { editing = null },
            onSave = {
                manager.save(it)
                editing = null
            },
        )
    }

    deleting?.let { feed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${feed.alias}”?") },
            text = { Text("The feed and its saved password are removed from this device.") },
            confirmButton = {
                TextButton(onClick = { manager.delete(feed.id); deleting = null }) {
                    Text("Delete", color = HostileRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            containerColor = TacticalSurface,
        )
    }

    sharing?.let { feed ->
        val fix = app.locationProvider.fix.value
        val lat = fix?.lat ?: app.mapCameraStore.lastTargetLat
        val lon = fix?.lon ?: app.mapCameraStore.lastTargetLon
        val where = if (fix != null) "your position" else "the map centre"
        val shareUrl = VideoFeedUrls.shareable(feed.url)
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text("Share “${feed.alias}” to the team?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sends a video marker at $where through the connected TAK server. Teammates get a Watch action on it.")
                    Text(shareUrl, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TacticalOnSurfaceMuted)
                    if (VideoFeedUrls.carriesSecrets(feed.url) || feed.hasCredentials) {
                        Text(
                            "Your username, password and access token are NOT sent. Teammates need their own login for this stream.",
                            color = Color(0xFFFFB020), fontSize = 13.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = lat != null && lon != null,
                    onClick = {
                        val f = feed
                        sharing = null
                        scope.launch {
                            val ok = manager.shareAsMarker(f, lat ?: 0.0, lon ?: 0.0, fix?.altitudeM ?: 0.0)
                            Toast.makeText(
                                context,
                                if (ok) "Video marker sent" else "Not sent — no TAK server connected (marker kept on your map)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sharing = null }) { Text("Cancel") } },
            containerColor = TacticalSurface,
        )
    }
}

private fun newFeedTemplate() = VideoFeed(
    id = UUID.randomUUID().toString(),
    alias = "",
    url = "",
    protocol = VideoProtocol.RTSP,
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = TacticalAccent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String, color: Color = TacticalOnSurfaceMuted) {
    Text(text, color = color, fontSize = 13.sp)
}

@Composable
private fun FeedCard(
    feed: VideoFeed,
    onWatch: () -> Unit,
    onPip: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(TacticalSurface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    feed.alias.ifBlank { "(unnamed)" },
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${feed.protocol.label}${if (feed.hasCredentials) " · login saved" else ""}",
                    color = TacticalAccent, fontSize = 12.sp,
                )
                Text(
                    VideoFeedUrls.redact(feed.url),
                    color = TacticalOnSurfaceMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val hasMenu = onEdit != null || onShare != null || onDelete != null || onSaveCopy != null
            if (hasMenu) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        onEdit?.let {
                            DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menu = false; it() })
                        }
                        onSaveCopy?.let {
                            DropdownMenuItem(text = { Text("Save to my feeds") }, leadingIcon = { Icon(Icons.Filled.Save, null) },
                                onClick = { menu = false; it() })
                        }
                        onShare?.let {
                            DropdownMenuItem(text = { Text("Share to team as marker") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menu = false; it() })
                        }
                        onDelete?.let {
                            DropdownMenuItem(text = { Text("Delete", color = HostileRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = HostileRed) },
                                onClick = { menu = false; it() })
                        }
                    }
                }
            }
        }
        if (!feed.protocol.playable) {
            Hint(
                "RTMP can't be played directly. " +
                    (VideoFeedUrls.rtmpPlaybackHint(feed.url)?.let { "Edit it to the RTSP address: ${VideoFeedUrls.redact(it)}" } ?: ""),
                color = Color(0xFFFFB020),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onWatch,
                enabled = feed.protocol.playable,
                colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent, contentColor = TacticalBackground),
                modifier = Modifier.weight(1f).heightIn(min = GloveTarget),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Watch", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = onPip,
                enabled = feed.protocol.playable,
                modifier = Modifier.weight(1f).heightIn(min = GloveTarget),
            ) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("On map", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Add / edit a manual feed. The password field is always masked, never
 * pre-filled: leaving it blank keeps the saved one.
 */
@Composable
private fun FeedEditorDialog(
    initial: VideoFeed,
    isNew: Boolean,
    passwordsPersisted: Boolean,
    onDismiss: () -> Unit,
    onSave: (VideoFeed) -> Unit,
) {
    // Never show secrets in the URL box: user-info and token parameters of
    // the stored (or peer-supplied) URL are split off before display.
    val start = remember(initial) {
        val (noCreds, u, p) = VideoFeedUrls.splitCredentials(initial.url)
        val (clean, q) = VideoFeedUrls.splitSecretQuery(noCreds)
        object {
            val url = clean
            val query = q
            val user = initial.username ?: u
            val pass = initial.password ?: p
        }
    }
    var alias by remember { mutableStateOf(initial.alias) }
    var url by remember { mutableStateOf(start.url) }
    var secretQuery by remember { mutableStateOf(start.query) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var protocolTouched by remember { mutableStateOf(!isNew) }
    var username by remember { mutableStateOf(start.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var overTcp by remember { mutableStateOf(initial.rtspOverTcp) }
    var lowLatency by remember { mutableStateOf(initial.lowLatency) }

    // Credentials pasted inside the URL move to the fields right away, so
    // they are never shown in clear in the URL box.
    fun onUrlChange(v: String) {
        val (noCreds, u, p) = VideoFeedUrls.splitCredentials(v.trim())
        val (clean, q) = VideoFeedUrls.splitSecretQuery(noCreds)
        url = clean
        if (q != null) secretQuery = q
        if (u != null) username = u
        if (p != null) password = p
        if (!protocolTouched) {
            VideoFeedUrls.detectProtocol(clean)?.let { protocol = it }
        }
    }

    val error = VideoFeedUrls.validate(url, protocol)
    val rtspHint = VideoFeedUrls.rtmpPlaybackHint(url)
    val hadPassword = start.pass != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = { Text(if (isNew) "Add video feed" else "Edit video feed") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = ::onUrlChange,
                    label = { Text("URL") },
                    placeholder = { Text("rtsp://192.168.50.1:8554/drone") },
                    singleLine = true,
                    isError = url.isNotBlank() && error != null,
                    supportingText = { if (url.isNotBlank() && error != null) Text(VideoFeedUrls.redact(error)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (secretQuery != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Access token in the URL: kept, hidden, stored encrypted.",
                            color = TacticalOnSurfaceMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { secretQuery = null }) { Text("Remove") }
                    }
                }
                if (rtspHint != null) {
                    TextButton(onClick = {
                        url = rtspHint
                        protocol = VideoProtocol.RTSP
                        protocolTouched = true
                    }) { Text("Use ${VideoFeedUrls.redact(rtspHint)} instead") }
                }
                Text("Protocol", color = TacticalOnSurfaceMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(VideoProtocol.RTSP, VideoProtocol.HLS).forEach { p ->
                        ProtocolChip(p, protocol == p) { protocol = p; protocolTouched = true }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(VideoProtocol.UDP_MPEGTS, VideoProtocol.UDP_RAW_H264).forEach { p ->
                        ProtocolChip(p, protocol == p) { protocol = p; protocolTouched = true }
                    }
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; clearPassword = false },
                    label = { Text(if (hadPassword && !clearPassword) "Password (saved — blank keeps it)" else "Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hadPassword && !clearPassword && password.isEmpty()) {
                    TextButton(onClick = { clearPassword = true }) { Text("Forget saved password") }
                }
                if (!passwordsPersisted) {
                    Hint("Secure storage is unavailable on this phone: the password is kept until the app closes, never written to disk.", color = Color(0xFFFFB020))
                }
                if (protocol == VideoProtocol.RTSP) {
                    ToggleRow("RTSP over TCP", "Works through NAT, VPN and lossy Wi-Fi. Turn off only on a clean LAN.", overTcp) { overTcp = it }
                }
                ToggleRow("Low latency", "Small buffer: less delay, more stutter on a weak link.", lowLatency) { lowLatency = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && alias.isNotBlank(),
                onClick = {
                    val pw = when {
                        password.isNotEmpty() -> password
                        clearPassword -> null
                        else -> start.pass
                    }
                    onSave(
                        initial.copy(
                            alias = alias.trim(),
                            url = VideoFeedUrls.joinQuery(url.trim(), secretQuery),
                            protocol = protocol,
                            username = username.trim().ifBlank { null },
                            password = if (username.isBlank()) null else pw,
                            rtspOverTcp = overTcp,
                            lowLatency = lowLatency,
                            origin = VideoFeedOrigin.MANUAL,
                            serverId = null,
                            sourceUid = null,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProtocolChip(p: VideoProtocol, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(p.label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = TacticalAccent,
            selectedLabelColor = TacticalBackground,
        ),
        modifier = Modifier.heightIn(min = 44.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(subtitle, color = TacticalOnSurfaceMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
