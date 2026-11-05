package dev.midplane.zap

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val repository get() = (application as ZapApplication).repository
    private var incomingPairingCode by mutableStateOf<String?>(null)
    private var openedForShare by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            ZapTheme {
                ZapScreen(
                    repository = repository,
                    incomingPairingCode = incomingPairingCode,
                    onPairingCodeConsumed = { incomingPairingCode = null },
                    openedForShare = openedForShare,
                    finishShare = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        repository.onForegroundChanged(true)
    }

    override fun onStop() {
        repository.onForegroundChanged(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> incomingPairingCode = intent.dataString
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> receiveShare(intent)
        }
    }

    private fun receiveShare(intent: Intent) {
        openedForShare = true
        lifecycleScope.launch {
            try {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = sharedImageUris(intent)
                    require(uris.isNotEmpty()) { "This app did not provide an image. Try sharing again." }
                    for (uri in uris) repository.addImage(uri)
                } else {
                    repository.addText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
                }
                repository.status.value = if (repository.connected.value) "Saved · syncing" else "Saved on this phone"
            } catch (e: Exception) {
                repository.error.value = e.message
            }
        }
    }

    private fun sharedImageUris(intent: Intent): List<Uri> =
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
}

private enum class ContentFilter(val label: String) {
    ALL("All"),
    TEXT("Text"),
    IMAGES("Images");

    fun includes(clip: Clip) = when (this) {
        ALL -> true
        TEXT -> clip.kind == "text"
        IMAGES -> clip.kind == "image"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapScreen(
    repository: Repository,
    incomingPairingCode: String?,
    onPairingCodeConsumed: () -> Unit,
    openedForShare: Boolean,
    finishShare: () -> Unit
) {
    val error by repository.error.collectAsStateWithLifecycle()
    val clips by repository.clips.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ContentFilter.ALL) }
    var previewId by remember { mutableStateOf<String?>(null) }
    // Deleted items stay hidden while their Undo is offered, and are deleted when it expires.
    var undoableDeletes by remember { mutableStateOf(emptySet<String>()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var enteringCode by remember { mutableStateOf(false) }
    var pairingBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                repository.error.value = e.message
            }
        }
    }

    fun copy(clip: Clip) = runAction {
        copyClip(context, clip)
        // Android 13 and later confirm every copy with their own clipboard preview.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar("Copied")
    }

    fun delete(clip: Clip) {
        previewId = null
        undoableDeletes = undoableDeletes + clip.id
        scope.launch {
            try {
                val result = snackbar.showSnackbar("Deleted", "Undo", duration = SnackbarDuration.Short)
                if (result != SnackbarResult.ActionPerformed) repository.delete(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.error.value = e.message
            } finally {
                undoableDeletes = undoableDeletes - clip.id
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) pairingCode = result.contents
    }
    LaunchedEffect(incomingPairingCode) {
        if (incomingPairingCode != null) {
            pairingCode = incomingPairingCode
            onPairingCodeConsumed()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it, "Dismiss")
            repository.error.value = null
        }
    }
    BackHandler(showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            ZapTopBar(
                showSettings = showSettings,
                openedForShare = openedForShare,
                onOpenSettings = { showSettings = true },
                onCloseSettings = { showSettings = false },
                onDone = finishShare
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!showSettings) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        runAction {
                            if (item?.uri != null && clipboard.primaryClipDescription?.hasMimeType("image/*") == true) {
                                repository.addImage(item.uri)
                            } else {
                                repository.addText(item?.coerceToText(context)?.toString().orEmpty())
                            }
                            snackbar.showSnackbar("Added to history")
                        }
                    },
                    icon = { Icon(Icons.Outlined.ContentPaste, null) },
                    text = { Text("Add from clipboard") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (showSettings) {
                SettingsContent(
                    repository,
                    onScan = { scanner.launch(pairingScanOptions()) },
                    onEnterCode = { enteringCode = true },
                    onAction = { runAction(it) }
                )
            } else {
                HistoryContent(
                    repository,
                    hidden = undoableDeletes,
                    search = search,
                    onSearchChange = { search = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onOpenSettings = { showSettings = true },
                    onPreview = { previewId = it.id },
                    onCopy = ::copy
                )
            }
        }
    }

    clips.firstOrNull { it.id == previewId }?.let { clip ->
        PreviewSheet(
            clip,
            onDismiss = { previewId = null },
            onCopy = {
                copy(clip)
                previewId = null
            },
            onOpen = { link ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (e: ActivityNotFoundException) {
                    repository.error.value = "No app on this phone can open that link."
                }
            },
            onPin = { runAction { repository.setPinned(clip, !clip.pinned) } },
            onShare = { runAction { shareClip(context, clip) } },
            onDelete = { delete(clip) }
        )
    }
    if (enteringCode) {
        EnterPairingCodeDialog(
            onContinue = { code ->
                pairingCode = code
                enteringCode = false
            },
            onDismiss = { enteringCode = false }
        )
    }
    pairingCode?.let { code ->
        ConfirmPairingDialog(
            code,
            busy = pairingBusy,
            onConnect = {
                pairingBusy = true
                scope.launch {
                    try {
                        repository.pair(code)
                        pairingCode = null
                    } catch (e: Exception) {
                        repository.error.value = e.message
                    } finally {
                        pairingBusy = false
                    }
                }
            },
            onDismiss = { pairingCode = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZapTopBar(
    showSettings: Boolean,
    openedForShare: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onDone: () -> Unit
) {
    TopAppBar(
        title = {
            if (showSettings) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        painterResource(R.drawable.ic_zap_foreground),
                        null,
                        Modifier.size(40.dp).background(colorResource(R.color.zap_icon_background), RoundedCornerShape(11.dp))
                    )
                    Text("Zap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        navigationIcon = {
            if (showSettings) {
                IconButton(onClick = onCloseSettings) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }
        },
        actions = {
            if (!showSettings) {
                if (openedForShare) TextButton(onClick = onDone) { Text("Done") }
                FilledTonalIconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        }
    )
}

/** Statuses that need no attention. The status line hides for these. */
private val QUIET_STATUSES = setOf("Up to date", "Local history")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryContent(
    repository: Repository,
    hidden: Set<String>,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: ContentFilter,
    onFilterChange: (ContentFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onPreview: (Clip) -> Unit,
    onCopy: (Clip) -> Unit
) {
    val clips by repository.clips.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val recovery by repository.recovery.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        OutlinedTextField(
            search,
            onSearchChange,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text("Search history") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (choice in enumValues<ContentFilter>()) {
                FilterChip(selected = filter == choice, onClick = { onFilterChange(choice) }, label = { Text(choice.label) })
            }
        }

        val pending = clips.count { it.pending }
        val statusLine = when {
            connected && pending > 0 -> "$pending waiting to sync"
            status !in QUIET_STATUSES -> status
            else -> null
        }
        statusLine?.let {
            Text(
                it,
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (recovery != null) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Some items need recovery") }
        }

        val visible = clips.filter {
            it.id !in hidden && filter.includes(it) &&
                (search.isBlank() || it.text.contains(search, true) || it.source.contains(search, true))
        }
        val groups = remember(visible) { groupByDay(visible) }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        repository.sync()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            if (visible.isEmpty()) {
                EmptyHistory(
                    searching = search.isNotEmpty(),
                    filter = filter,
                    connected = connected,
                    onConnect = onOpenSettings,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Bottom padding keeps the last item clear of the floating button.
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                    for ((heading, group) in groups) {
                        stickyHeader(key = "heading:$heading") { DayHeading(heading) }
                        items(group, key = { it.id }) { clip ->
                            ClipListItem(
                                clip,
                                time = if (heading == TODAY) relativeTime(clip.createdAt) else timeOfDay(clip.createdAt),
                                onCopy = { onCopy(clip) },
                                onPreview = { onPreview(clip) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun EmptyHistory(searching: Boolean, filter: ContentFilter, connected: Boolean, onConnect: () -> Unit, modifier: Modifier) {
    val title = when {
        searching -> "No matches"
        filter == ContentFilter.ALL -> "Your clipboard, remembered"
        else -> "No ${filter.label.lowercase()} yet"
    }
    val message = if (searching) "Try another word or content filter." else "Share text or an image to Zap, or add what you’ve copied."
    // Scrollable so pull-to-refresh also works on an empty list.
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            if (searching) Icons.Outlined.Search else Icons.Outlined.ContentPaste,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!connected) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onConnect) { Text("Connect your Mac") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipListItem(clip: Clip, time: String, onCopy: () -> Unit, onPreview: () -> Unit, modifier: Modifier = Modifier) {
    val isImage = clip.kind == "image"
    ListItem(
        headlineContent = { Text(if (isImage) "Image" else clip.text.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${clip.source} · $time", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // Only images get a leading element; text starts at the edge.
        leadingContent = if (isImage) ({ ClipImage(clip, Modifier.size(48.dp)) }) else null,
        trailingContent = {
            IconButton(onClick = onPreview) { Icon(Icons.Outlined.OpenInFull, "Preview") }
        },
        modifier = modifier
            .combinedClickable(
                onClickLabel = if (isImage) "Copy image" else "Copy text",
                onLongClickLabel = "Preview",
                onLongClick = onPreview,
                onClick = onCopy
            )
            .padding(horizontal = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    clip: Clip,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onOpen: (String) -> Unit,
    onPin: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val isImage = clip.kind == "image"
    val link = clip.link
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${clip.source} · ${fullTime(clip.createdAt)}", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    if (isImage) {
                        ClipImage(clip, Modifier.fillMaxWidth())
                    } else {
                        SelectionContainer { Text(clip.text, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy") }
                if (link != null) FilledTonalButton(onClick = { onOpen(link) }) { Text("Open") }
                Spacer(Modifier.weight(1f))
                IconToggleButton(checked = clip.pinned, onCheckedChange = { onPin() }) {
                    Icon(if (clip.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (clip.pinned) "Unpin" else "Pin")
                }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            }
            if (clip.pinned) {
                Text(
                    "Pinned items are kept until you unpin them.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EnterPairingCodeDialog(onContinue: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Mac") },
        text = {
            OutlinedTextField(
                code,
                { code = it },
                label = { Text("Pairing code") },
                maxLines = 5,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        },
        confirmButton = { TextButton(onClick = { onContinue(code.trim()) }, enabled = code.isNotBlank()) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmPairingDialog(code: String, busy: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    val host = remember(code) { pairingHost(code) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host ?: "This code does not name a server.", style = MaterialTheme.typography.titleMedium)
                Text("This phone will join the encrypted history on that server. Only continue with a code you created on a device you already use.")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onConnect) { Text(if (busy) "Connecting…" else "Connect") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

private val RETENTION_CHOICES = listOf(1, 7, 10, 30, 90, 365)

private fun daysLabel(days: Int) = if (days == 1) "1 day" else "$days days"

@Composable
private fun SettingsContent(
    repository: Repository,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onAction: (suspend () -> Unit) -> Unit
) {
    val recovery by repository.recovery.collectAsStateWithLifecycle()
    val damagedCount by repository.damagedCount.collectAsStateWithLifecycle()
    val days by repository.days.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    val devices by repository.devices.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val server by repository.server.collectAsStateWithLifecycle()
    val thisDeviceId by repository.deviceId.collectAsStateWithLifecycle()
    var choosingRetention by remember { mutableStateOf(false) }
    var shorterRetention by remember { mutableStateOf<Int?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<String?>(null) }
    var removingDevice by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current

    LazyColumn(
        Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text("History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Box {
                ListItem(
                    headlineContent = { Text("Keep history for") },
                    supportingContent = { Text("Pinned items are kept until you unpin them.") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(daysLabel(days), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                    },
                    modifier = Modifier.clickable(role = Role.DropdownList) { choosingRetention = true }
                )
                DropdownMenu(choosingRetention, onDismissRequest = { choosingRetention = false }, offset = DpOffset(200.dp, 0.dp)) {
                    for (choice in (RETENTION_CHOICES + days).distinct().sorted()) {
                        DropdownMenuItem(
                            text = { Text(daysLabel(choice)) },
                            trailingIcon = { if (choice == days) Icon(Icons.Outlined.Check, "Selected") },
                            onClick = {
                                choosingRetention = false
                                when {
                                    choice == days -> Unit
                                    // Shortening deletes items, so it asks first.
                                    choice < days -> shorterRetention = choice
                                    else -> onAction { repository.setDays(choice) }
                                }
                            }
                        )
                    }
                }
            }
            recovery?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onAction { repository.sync() } }) { Text("Retry recovery") }
                if (damagedCount > 0) {
                    TextButton(onClick = { confirmingDiscard = true }) { Text("Discard damaged items", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { HorizontalDivider() }
        item {
            Text(if (connected) "Devices" else "Connect your Mac", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (connected) {
                Text(server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelMedium)
            } else {
                Text(
                    "In Zap on Mac, open Settings and choose Pair another device. Your content is encrypted before it leaves either device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onScan) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan pairing code")
                }
                TextButton(onClick = onEnterCode) { Text("Enter pairing code") }
            }
        }
        items(devices, key = { it.getString("id") }) { device ->
            val isThis = device.getString("id") == thisDeviceId
            DeviceRow(device, isThis, onRemove = { removingDevice = device })
        }
        if (connected) {
            item {
                Column {
                    SettingsAction("Pair another device", "Create a private code for your Mac or phone", Icons.Outlined.QrCodeScanner) {
                        onAction { invitation = repository.invite() }
                    }
                    HorizontalDivider()
                    SettingsAction("Sync now", "Send pending items and check for new history", Icons.Outlined.Sync) {
                        onAction { repository.sync() }
                    }
                }
            }
        }
        // Actions that remove history or this phone's connection stay apart from everyday settings.
        item { HorizontalDivider() }
        item {
            Column {
                if (connected) {
                    SettingsAction("Disconnect", "Stop syncing. Local history stays on this phone.", Icons.Outlined.LinkOff) {
                        confirmingDisconnect = true
                    }
                }
                SettingsAction(
                    "Clear history",
                    if (connected) "Delete every item on all your devices" else "Delete every item on this phone",
                    Icons.Outlined.DeleteForever,
                    destructive = true
                ) { confirmingClear = true }
            }
        }
    }

    shorterRetention?.let { choice ->
        ConfirmDialog(
            title = "Keep ${daysLabel(choice)} of history?",
            message = "Unpinned items older than ${daysLabel(choice)} will be deleted" + if (connected) " on all your devices." else " from this phone.",
            confirmLabel = "Delete older items",
            onConfirm = {
                shorterRetention = null
                onAction { repository.setDays(choice) }
            },
            onDismiss = { shorterRetention = null }
        )
    }
    invitation?.let { code ->
        ConfirmDialog(
            title = "Pair another device",
            message = "Copy this private code into Zap on the new device. The invitation lets one device join within five minutes. The code also contains encryption keys that remain sensitive after expiry.",
            confirmLabel = "Copy code",
            onConfirm = {
                copyPairingCode(context, code)
                invitation = null
            },
            onDismiss = { invitation = null }
        )
    }
    if (confirmingDisconnect) {
        ConfirmDialog(
            title = "Disconnect?",
            message = "Local history stays on this phone and will upload when you connect again.",
            confirmLabel = "Disconnect",
            onConfirm = {
                confirmingDisconnect = false
                onAction { repository.disconnect() }
            },
            onDismiss = { confirmingDisconnect = false }
        )
    }
    if (confirmingClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = if (connected) "Every item, including pinned ones, will be deleted on all connected devices when they sync." else "Every item, including pinned ones, will be deleted from this phone.",
            confirmLabel = "Clear history",
            onConfirm = {
                confirmingClear = false
                onAction { repository.clear() }
            },
            onDismiss = { confirmingClear = false }
        )
    }
    if (confirmingDiscard) {
        ConfirmDialog(
            title = "Discard damaged items?",
            message = if (connected) {
                "Unreadable items will be deleted here and on paired devices when you sync. Healthy items stay."
            } else {
                "Unreadable items will be permanently deleted from this phone. Healthy items stay."
            },
            confirmLabel = "Discard",
            onConfirm = {
                confirmingDiscard = false
                onAction { repository.discardDamaged() }
            },
            onDismiss = { confirmingDiscard = false }
        )
    }
    removingDevice?.let { device ->
        ConfirmDialog(
            title = "Remove ${device.getString("name")}?",
            message = "It will stop receiving new items, and your other devices switch to a new encryption key. Content already downloaded stays on that device.",
            confirmLabel = "Remove",
            onConfirm = {
                removingDevice = null
                onAction { repository.removeDevice(device.getString("id")) }
            },
            onDismiss = { removingDevice = null }
        )
    }
}

@Composable
private fun DeviceRow(device: JSONObject, isThis: Boolean, onRemove: () -> Unit) {
    val platform = if (isThis) "android" else device.optString("platform")
    val lastSeen = device.optLong("lastSeen")
    ListItem(
        headlineContent = { Text(device.getString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                when {
                    isThis -> "This phone"
                    lastSeen > 0 -> "Last synced ${relativeTime(lastSeen).replaceFirstChar(Char::lowercase)}"
                    else -> "Waiting for its next sync"
                }
            )
        },
        leadingContent = {
            Icon(
                when (platform) {
                    "android" -> Icons.Outlined.PhoneAndroid
                    "mac" -> Icons.Outlined.LaptopMac
                    else -> Icons.Outlined.Devices
                },
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (!isThis) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsAction(title: String, description: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(title, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null, tint = tint) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)
    )
}

@Composable
private fun ClipImage(clip: Clip, modifier: Modifier) {
    var data by remember(clip.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(clip.id) {
        data = withContext(Dispatchers.IO) { clip.payload.getString("png").unb64() }
    }
    AsyncImage(data, "Clipboard image", modifier, contentScale = ContentScale.Fit)
}

private fun pairingScanOptions() = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Scan the pairing code in Zap on Mac")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

private fun pairingHost(code: String): String? = runCatching {
    Uri.parse(decodePairingCode(code).getString("url")).host
}.getOrNull()

private fun copyPairingCode(context: Context, code: String) {
    val clip = ClipData.newPlainText("Pairing code", code)
    // Use the documented key string so this preview hint also works with our Android 10 minimum.
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val TODAY = "Today"

/** History in display order: pinned items first, then one group per day, newest first. */
private fun groupByDay(clips: List<Clip>): List<Pair<String, List<Clip>>> {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val (pinned, rest) = clips.partition { it.pinned }
    val days = rest.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.map { (day, group) ->
        val heading = when {
            day == today -> TODAY
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> day.format(dayFormat)
        }
        heading to group
    }
    return (if (pinned.isEmpty()) emptyList() else listOf("Pinned" to pinned)) + days
}

/** "Just now" and "12m ago" within the hour, then the time of day. */
private fun relativeTime(time: Long): String {
    val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> timeOfDay(time)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
    }
}

private fun timeOfDay(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

private fun fullTime(time: Long): String {
    val day = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (day == LocalDate.now()) timeOfDay(time) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
}

private suspend fun imageUri(context: Context, clip: Clip): Uri = withContext(Dispatchers.IO) {
    val folder = sharedImagesDirectory(context).apply { mkdirs() }
    deleteStaleSharedImages(context)
    val file = File(folder, "${clip.id}.png")
    file.writeBytes(clip.payload.getString("png").unb64())
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

private suspend fun copyClip(context: Context, clip: Clip) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clip.kind == "image") {
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", imageUri(context, clip)))
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("Text", clip.text))
    }
}

private suspend fun shareClip(context: Context, clip: Clip) {
    val intent = Intent(Intent.ACTION_SEND)
    if (clip.kind == "image") {
        val uri = imageUri(context, clip)
        intent.type = "image/png"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newUri(context.contentResolver, "Image", uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, clip.text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
