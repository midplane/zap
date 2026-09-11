package dev.midplane.zap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val repo get() = (application as ZapApplication).repository
    private var incomingPair by mutableStateOf<String?>(null)
    private var incomingShare by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            ZapTheme {
                ZapScreen(repo, incomingPair, { incomingPair = null }, incomingShare, { finish() })
            }
        }
    }
    override fun onStart() { super.onStart(); repo.foreground(true) }
    override fun onStop() { repo.foreground(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) { incomingPair = intent.dataString; return }
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        incomingShare = true
        lifecycleScope.launch {
            try {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    require(uris.isNotEmpty()) { "This app did not provide an image. Try sharing again." }
                    for (uri in uris) repo.addImage(uri)
                } else repo.addText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
                repo.status.value = if (repo.connected.value) "Saved · syncing" else "Saved on this phone"
            } catch (e: Exception) { repo.error.value = e.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ZapScreen(repo: Repository, incomingPair: String?, onPairConsumed: () -> Unit, incomingShare: Boolean, finishShare: () -> Unit) {
    val clips by repo.clips.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val error by repo.error.collectAsStateWithLifecycle()
    val connected by repo.connected.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var settings by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Clip?>(null) }
    var pairing by remember { mutableStateOf<String?>(null) }
    var manualPair by remember { mutableStateOf(false) }
    var pairingBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun runAction(action: suspend () -> Unit) { scope.launch { try { action() } catch (e: Exception) { repo.error.value = e.message } } }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> if (result.contents != null) pairing = result.contents }
    LaunchedEffect(incomingPair) { if (incomingPair != null) { pairing = incomingPair; onPairConsumed() } }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it, "Dismiss"); repo.error.value = null } }
    BackHandler(settings) { settings = false }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                if (settings) Text("Settings", style = MaterialTheme.typography.headlineSmall)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_zap_foreground), null, Modifier.size(44.dp).background(colorResource(R.color.zap_icon_background), RoundedCornerShape(12.dp)))
                    Column {
                        Text("Zap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Clipboard history", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }, navigationIcon = {
                if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.Outlined.ArrowBack, "Back") }
            }, actions = {
                if (!settings) {
                    if (incomingShare) TextButton(onClick = finishShare) { Text("Done") }
                    FilledTonalIconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!settings) ExtendedFloatingActionButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                runAction {
                    if (item?.uri != null && clipboard.primaryClipDescription?.hasMimeType("image/*") == true) repo.addImage(item.uri)
                    else repo.addText(item?.coerceToText(context)?.toString().orEmpty())
                    snackbar.showSnackbar("Added to history")
                }
            }, icon = { Icon(Icons.Outlined.ContentPaste, null) }, text = { Text("Add from clipboard") })
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (settings) SettingsContent(repo, onScan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the pairing code in Zap on Mac").setBeepEnabled(false).setOrientationLocked(false)) }, onManual = { manualPair = true }, onAction = { runAction(it) })
            else Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text("Search history") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }, singleLine = true, shape = RoundedCornerShape(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (choice in listOf("All", "Text", "Images")) FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice) })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { runAction { repo.sync() } }) { Icon(Icons.Outlined.Sync, "Sync now") }
                }
                val pending = clips.count { it.pending }
                Text(if (connected && pending > 0) "$pending waiting to sync" else status, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val filtered = clips.filter { (filter == "All" || it.kind == if (filter == "Text") "text" else "image") && (search.isBlank() || it.text.contains(search, true) || it.source.contains(search, true)) }
                if (filtered.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (search.isEmpty()) Icons.Outlined.ContentPaste else Icons.Outlined.Search, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Text(if (search.isNotEmpty()) "No matches" else if (filter == "All") "Your clipboard, remembered" else "No ${filter.lowercase()} yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(if (search.isEmpty()) "Share text or an image to Zap, or add what you’ve copied." else "Try another word or content filter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        if (!connected) { Spacer(Modifier.height(20.dp)); TextButton(onClick = { settings = true }) { Text("Connect your Mac") } }
                    }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp)) {
                    items(filtered, key = { it.id }) { clip ->
                        ListItem(
                            headlineContent = { Text(if (clip.kind == "image") "Image" else clip.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${clip.source} · ${relativeDate(clip.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                if (clip.kind == "image") ClipImage(clip, Modifier.size(48.dp))
                                else Icon(Icons.Outlined.Notes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = { IconButton(onClick = { runAction { copyClip(context, clip); snackbar.showSnackbar("Copied") } }) { Icon(Icons.Outlined.ContentCopy, if (clip.kind == "image") "Copy image" else "Copy text") } },
                            modifier = Modifier.clickable { preview = clip }.padding(horizontal = 4.dp)
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
    preview?.let { clip ->
        ModalBottomSheet(onDismissRequest = { preview = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (clip.kind == "image") "Image" else "Text", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.heightIn(max = 360.dp)) { item {
                    if (clip.kind == "image") ClipImage(clip, Modifier.fillMaxWidth())
                    else SelectionContainer { Text(clip.text, style = MaterialTheme.typography.bodyLarge) }
                } }
                Text(DateFormat.getDateTimeInstance().format(Date(clip.createdAt)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runAction { copyClip(context, clip); preview = null; snackbar.showSnackbar("Copied") } }) { Text("Copy") }
                    FilledTonalButton(onClick = { runAction { shareClip(context, clip) } }) { Text("Share") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { runAction { repo.delete(clip); preview = null } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (manualPair) {
        var code by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { manualPair = false }, title = { Text("Pair with Mac") }, text = { OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, maxLines = 5) }, confirmButton = { TextButton(onClick = { pairing = code.trim(); manualPair = false }, enabled = code.isNotBlank()) { Text("Continue") } }, dismissButton = { TextButton(onClick = { manualPair = false }) { Text("Cancel") } })
    }
    pairing?.let { code ->
        val host = remember(code) { pairingHost(code) }
        AlertDialog(onDismissRequest = { if (!pairingBusy) pairing = null }, title = { Text("Connect this phone?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host ?: "This code does not name a server.", style = MaterialTheme.typography.titleMedium)
                Text("This phone will join the encrypted history on that server. Only continue with a code you created on a device you already use.")
            }
        }, confirmButton = {
            TextButton(enabled = !pairingBusy, onClick = { pairingBusy = true; scope.launch { try { repo.pair(code); pairing = null } catch (e: Exception) { repo.error.value = e.message } finally { pairingBusy = false } } }) { Text(if (pairingBusy) "Connecting…" else "Connect") }
        }, dismissButton = { TextButton(enabled = !pairingBusy, onClick = { pairing = null }) { Text("Cancel") } })
    }
}

@Composable private fun SettingsContent(repo: Repository, onScan: () -> Unit, onManual: () -> Unit, onAction: (suspend () -> Unit) -> Unit) {
    val days by repo.days.collectAsStateWithLifecycle()
    val connected by repo.connected.collectAsStateWithLifecycle()
    val devices by repo.devices.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val server by repo.server.collectAsStateWithLifecycle()
    val self by repo.deviceId.collectAsStateWithLifecycle()
    var retention by remember(days) { mutableStateOf(days.toString()) }
    var clearing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var remove by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(retention, { retention = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Days to keep") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                TextButton(onClick = { onAction { repo.setDays(retention.toInt()) } }, enabled = retention.toIntOrNull() in 1..365 && retention != days.toString()) { Text("Save") }
            }
            TextButton(onClick = { clearing = true }) { Text("Clear history", color = MaterialTheme.colorScheme.error) }
        }
        item { HorizontalDivider() }
        item {
            Text(if (connected) "Connected devices" else "Connect your Mac", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (!connected) {
                Text("In Zap on Mac, open Settings and choose Pair another device. Your content is encrypted before it leaves either device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onScan) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan pairing code") }
                TextButton(onClick = onManual) { Text("Enter pairing code") }
            } else {
                Text(server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
        }
        items(devices, key = { it.getString("id") }) { device ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(device.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (device.getString("id") != self) TextButton(onClick = { remove = device.getString("id") }) { Text("Remove") }
                else Text("This phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (connected) item {
            Column {
                SettingsAction("Sync now", "Send pending items and check for new history", Icons.Outlined.Sync) { onAction { repo.sync() } }
                HorizontalDivider()
                SettingsAction("Pair another device", "Create a private code for your Mac or phone", Icons.Outlined.QrCodeScanner) { onAction { invitation = repo.invite() } }
                HorizontalDivider()
                SettingsAction("Disconnect", "Keep local history on this phone", Icons.Outlined.LinkOff) { disconnecting = true }
            }
        }
    }
    invitation?.let { code -> AlertDialog(onDismissRequest = { invitation = null }, title = { Text("Pair another device") }, text = { Text("Copy this private code and enter it in Zap on the new device. It expires in five minutes.") }, confirmButton = { TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pairing code", code)); invitation = null }) { Text("Copy code") } }, dismissButton = { TextButton(onClick = { invitation = null }) { Text("Cancel") } }) }
    if (disconnecting) AlertDialog(onDismissRequest = { disconnecting = false }, title = { Text("Disconnect?") }, text = { Text("Local history stays on this phone and will upload when you connect again.") }, confirmButton = { TextButton(onClick = { disconnecting = false; onAction { repo.disconnect() } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnecting = false }) { Text("Cancel") } })
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear history?") }, text = { Text(if (connected) "History will be deleted on all connected devices when they sync." else "History will be deleted from this phone.") }, confirmButton = { TextButton(onClick = { clearing = false; onAction { repo.clear() } }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } })
    remove?.let { id -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove this device?") }, text = { Text("It will no longer receive new items. Content already downloaded remains on that device.") }, confirmButton = { TextButton(onClick = { remove = null; onAction { repo.removeDevice(id) } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable private fun SettingsAction(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)
    )
}

@Composable private fun ClipImage(clip: Clip, modifier: Modifier) {
    var data by remember(clip.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(clip.id) {
        data = withContext(Dispatchers.IO) { clip.payload.getString("png").unb64() }
    }
    AsyncImage(data, "Clipboard image", modifier, contentScale = ContentScale.Fit)
}

private fun pairingHost(code: String): String? = runCatching {
    Uri.parse(JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.substringAfter('#')))).getString("url")).host
}.getOrNull()
private fun relativeDate(time: Long): String {
    val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
    return when { minutes < 1 -> "Just now"; minutes < 60 -> "${minutes}m ago"; minutes < 1440 -> "${minutes / 60}h ago"; else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time)) }
}
private suspend fun imageUri(context: android.content.Context, clip: Clip): Uri = withContext(Dispatchers.IO) {
    val folder = File(context.cacheDir, "shared").apply { mkdirs() }
    folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
    val file = File(folder, "${clip.id}.png"); file.writeBytes(clip.payload.getString("png").unb64())
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
private suspend fun copyClip(context: android.content.Context, clip: Clip) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clip.kind == "image") clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", imageUri(context, clip)))
    else clipboard.setPrimaryClip(ClipData.newPlainText("Text", clip.text))
}
private suspend fun shareClip(context: android.content.Context, clip: Clip) {
    val intent = Intent(Intent.ACTION_SEND)
    if (clip.kind == "image") { val uri = imageUri(context, clip); intent.type = "image/png"; intent.putExtra(Intent.EXTRA_STREAM, uri); intent.clipData = ClipData.newUri(context.contentResolver, "Image", uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    else { intent.type = "text/plain"; intent.putExtra(Intent.EXTRA_TEXT, clip.text) }
    context.startActivity(Intent.createChooser(intent, null))
}
