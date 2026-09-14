package dev.midplane.zap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

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
    var showSettings by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ContentFilter.ALL) }
    var preview by remember { mutableStateOf<Clip?>(null) }
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
                    search = search,
                    onSearchChange = { search = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onOpenSettings = { showSettings = true },
                    onPreview = { preview = it },
                    onCopy = { clip ->
                        runAction {
                            copyClip(context, clip)
                            snackbar.showSnackbar("Copied")
                        }
                    },
                    onSync = { runAction { repository.sync() } }
                )
            }
        }
    }

    preview?.let { clip ->
        PreviewSheet(
            clip,
            onDismiss = { preview = null },
            onCopy = {
                runAction {
                    copyClip(context, clip)
                    preview = null
                    snackbar.showSnackbar("Copied")
                }
            },
            onShare = { runAction { shareClip(context, clip) } },
            onDelete = {
                runAction {
                    repository.delete(clip)
                    preview = null
                }
            }
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
                        Modifier.size(44.dp).background(colorResource(R.color.zap_icon_background), RoundedCornerShape(12.dp))
                    )
                    Column {
                        Text("Zap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Clipboard history", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        navigationIcon = {
            if (showSettings) {
                IconButton(onClick = onCloseSettings) { Icon(Icons.Outlined.ArrowBack, "Back") }
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

@Composable
private fun HistoryContent(
    repository: Repository,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: ContentFilter,
    onFilterChange: (ContentFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onPreview: (Clip) -> Unit,
    onCopy: (Clip) -> Unit,
    onSync: () -> Unit
) {
    val clips by repository.clips.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val recovery by repository.recovery.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()

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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (choice in enumValues<ContentFilter>()) {
                FilterChip(selected = filter == choice, onClick = { onFilterChange(choice) }, label = { Text(choice.label) })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSync) { Icon(Icons.Outlined.Sync, "Sync now") }
        }

        val pending = clips.count { it.pending }
        Text(
            if (connected && pending > 0) "$pending waiting to sync" else status,
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recovery != null) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Some items need recovery") }
        }

        val visible = clips.filter {
            filter.includes(it) && (search.isBlank() || it.text.contains(search, true) || it.source.contains(search, true))
        }
        if (visible.isEmpty()) {
            EmptyHistory(
                searching = search.isNotEmpty(),
                filter = filter,
                connected = connected,
                onConnect = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        } else {
            // Bottom padding keeps the last item clear of the floating button.
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(visible, key = { it.id }) { clip ->
                    ClipListItem(clip, onClick = { onPreview(clip) }, onCopy = { onCopy(clip) })
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(searching: Boolean, filter: ContentFilter, connected: Boolean, onConnect: () -> Unit, modifier: Modifier) {
    val title = when {
        searching -> "No matches"
        filter == ContentFilter.ALL -> "Your clipboard, remembered"
        else -> "No ${filter.label.lowercase()} yet"
    }
    val message = if (searching) "Try another word or content filter." else "Share text or an image to Zap, or add what you’ve copied."
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

@Composable
private fun ClipListItem(clip: Clip, onClick: () -> Unit, onCopy: () -> Unit) {
    val isImage = clip.kind == "image"
    ListItem(
        headlineContent = { Text(if (isImage) "Image" else clip.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${clip.source} · ${relativeDate(clip.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (isImage) {
                ClipImage(clip, Modifier.size(48.dp))
            } else {
                Icon(Icons.Outlined.Notes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, if (isImage) "Copy image" else "Copy text") }
        },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(clip: Clip, onDismiss: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val isImage = clip.kind == "image"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (isImage) "Image" else "Text", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    if (isImage) {
                        ClipImage(clip, Modifier.fillMaxWidth())
                    } else {
                        SelectionContainer { Text(clip.text, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            Text(
                DateFormat.getDateTimeInstance().format(Date(clip.createdAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy") }
                FilledTonalButton(onClick = onShare) { Text("Share") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
    var retention by remember(days) { mutableStateOf(days.toString()) }
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<String?>(null) }
    var removingDeviceId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LazyColumn(
        Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text("History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    retention,
                    { retention = it.filter(Char::isDigit).take(3) },
                    Modifier.weight(1f),
                    label = { Text("Days to keep") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                TextButton(
                    onClick = { onAction { repository.setDays(retention.toInt()) } },
                    enabled = retention.toIntOrNull() in 1..365 && retention != days.toString()
                ) { Text("Save") }
            }
            TextButton(onClick = { confirmingClear = true }) { Text("Clear history", color = MaterialTheme.colorScheme.error) }
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
            Text(if (connected) "Connected devices" else "Connect your Mac", style = MaterialTheme.typography.titleMedium)
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
            val id = device.getString("id")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(device.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (id == thisDeviceId) {
                    Text("This phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = { removingDeviceId = id }) { Text("Remove") }
                }
            }
        }
        if (connected) {
            item {
                Column {
                    SettingsAction("Sync now", "Send pending items and check for new history", Icons.Outlined.Sync) {
                        onAction { repository.sync() }
                    }
                    HorizontalDivider()
                    SettingsAction("Pair another device", "Create a private code for your Mac or phone", Icons.Outlined.QrCodeScanner) {
                        onAction { invitation = repository.invite() }
                    }
                    HorizontalDivider()
                    SettingsAction("Disconnect", "Keep local history on this phone", Icons.Outlined.LinkOff) {
                        confirmingDisconnect = true
                    }
                }
            }
        }
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
            message = if (connected) "History will be deleted on all connected devices when they sync." else "History will be deleted from this phone.",
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
    removingDeviceId?.let { id ->
        ConfirmDialog(
            title = "Remove this device?",
            message = "It will no longer receive new items. Content already downloaded remains on that device.",
            confirmLabel = "Remove",
            onConfirm = {
                removingDeviceId = null
                onAction { repository.removeDevice(id) }
            },
            onDismiss = { removingDeviceId = null }
        )
    }
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
private fun SettingsAction(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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

private fun relativeDate(time: Long): String {
    val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
    }
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
