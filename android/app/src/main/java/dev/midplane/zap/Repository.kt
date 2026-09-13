package dev.midplane.zap

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class ZapApplication : Application() {
    val repository by lazy { Repository(this) }
    override fun onCreate() {
        super.onCreate()
        repository.updateBackgroundSync()
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if ((applicationContext as ZapApplication).repository.sync()) Result.success() else Result.retry()
}
data class Clip(val id: String, val createdAt: Long, val payload: JSONObject, val pending: Boolean) {
    val kind: String get() = payload.getString("kind")
    val text: String get() = payload.optString("text")
    val source: String get() = payload.optString("source", "Android")
}
class ApiException(val status: Int, override val message: String) : Exception(message)

class Repository(private val context: Context) {
    val identity = Identity(context)
    private val dao = Room.databaseBuilder(context, HistoryDatabase::class.java, "history.sqlite").build().clips()
    private val directory = File(context.filesDir, "content").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).pingInterval(60, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = SyncRequests(scope) { sync() }
    private val payloads = PayloadFiles(directory, identity.localKey)
    private val unavailableIds = mutableSetOf<String>()
    private var nextCacheCleanup = 0L
    private var socket: WebSocket? = null
    private var active = false
    private var reconnect: Job? = null
    private var reconnectDelay = 2_000L
    private var cursor = -1L
    val clips = MutableStateFlow<List<Clip>>(emptyList())
    val days = MutableStateFlow(prefs.getInt("days", 10))
    val status = MutableStateFlow(if (identity.connected) "Connecting…" else "Local history")
    val error = MutableStateFlow<String?>(null)
    val recovery = MutableStateFlow<String?>(null)
    val damagedCount = MutableStateFlow(0)
    val devices = MutableStateFlow<List<JSONObject>>(emptyList())
    val connected = MutableStateFlow(identity.connected)
    val server = MutableStateFlow(identity.value("url"))
    val deviceId = MutableStateFlow(identity.value("deviceId"))
    init { scope.launch { mutex.withLock { try { refresh() } catch (e: Exception) { error.value = e.message } } } }
    private suspend fun save(clip: Clip) {
        payloads.save(clip.id, clip.payload)
        dao.save(StoredClip(clip.id, clip.createdAt, clip.pending))
    }
    private suspend fun removeLocal(id: String) { payloads.remove(id); dao.remove(id) }
    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (now >= nextCacheCleanup) {
            File(context.cacheDir, "shared").listFiles()?.filter { now - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            nextCacheCleanup = now + 3_600_000
        }
        val cutoff = now - days.value * 86_400_000L
        val stored = dao.all()
        val readable = payloads.readAll(stored.map { it.id }.toSet())
        for (clip in stored.filter { it.createdAt <= cutoff && it.id !in payloads.damaged }) removeLocal(clip.id)
        val retained = stored.filter { it.createdAt > cutoff }
        clips.value = retained.mapNotNull { record ->
            readable[record.id]?.let { Clip(record.id, record.createdAt, it, record.pending) }
        }
        damagedCount.value = payloads.damaged.size
        val count = (payloads.damaged + unavailableIds).size
        recovery.value = if (count == 0) null else "Unable to read $count saved ${if (count == 1) "item" else "items"}. Synced content retries automatically. Damaged local records are kept for recovery."
    }
    fun updateBackgroundSync() {
        val manager = WorkManager.getInstance(context)
        if (identity.connected || identity.enrollment() != null) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            manager.enqueueUniquePeriodicWork("zap-refresh", ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            manager.cancelUniqueWork("zap-refresh")
            manager.cancelUniqueWork("zap-send")
        }
    }
    fun enqueue() {
        if (!identity.connected) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("zap-send", ExistingWorkPolicy.KEEP, work)
        syncRequests.request()
    }
    suspend fun addText(text: String) = withContext(Dispatchers.IO) {
        add(TextCapture.payload(text))
    }
    suspend fun addImage(uri: Uri) = withContext(Dispatchers.IO) {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 20 * 1024 * 1024) { "Image is too large. Maximum: 20 MiB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Image could not be opened")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 40_000_000) { "Use a static image up to 40 megapixels." }
        require(options.outMimeType != "image/gif") { "Animated images are not supported. Share a PNG or JPEG." }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Image format is not supported")
        val output = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        require(output.size() <= 20 * 1024 * 1024) { "Converted image exceeds 20 MiB." }
        add(JSONObject().put("kind", "image").put("png", output.toByteArray().b64()))
    }
    private suspend fun add(payload: JSONObject) {
        mutex.withLock {
            val previous = clips.value.firstOrNull()?.payload
            if (previous?.optString("text") == payload.optString("text") && previous?.optString("png") == payload.optString("png")) return
            val now = System.currentTimeMillis(); payload.put("source", Build.MODEL).put("createdAt", now)
            val clip = Clip(UUID.randomUUID().toString(), now, payload, true)
            save(clip); refresh()
        }
        enqueue()
    }
    suspend fun delete(clip: Clip) = withContext(Dispatchers.IO) {
        mutex.withLock { if (identity.connected) dao.queue(Deletion(clip.id)); removeLocal(clip.id); refresh() }; enqueue()
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putBoolean("clearPending", identity.connected).commit()
            for (clip in dao.all()) removeLocal(clip.id)
            unavailableIds.clear()
            refresh()
        }; enqueue()
    }
    suspend fun discardDamaged() = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (id in payloads.damaged.toList()) { if (identity.connected) dao.queue(Deletion(id)); removeLocal(id) }
            refresh()
        }; enqueue()
    }
    suspend fun setDays(value: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            days.value = value.coerceIn(1, 365); prefs.edit().putInt("days", days.value).putBoolean("daysPending", true).commit(); refresh()
        }; enqueue()
    }
    private fun request(path: String, method: String = "GET", body: ByteArray? = null, headers: Map<String, String> = emptyMap(), url: String = identity.value("url")): ByteArray {
        val builder = Request.Builder().url(url + path)
        if (url == identity.value("url") && identity.connected) builder.header("Authorization", "Bearer ${identity.value("token")}")
        for ((key, value) in headers) builder.header(key, value)
        builder.method(method, if (method in listOf("POST", "PUT")) (body ?: ByteArray(0)).toRequestBody() else body?.toRequestBody())
        http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) throw ApiException(response.code, runCatching { JSONObject(String(bytes)).getString("error") }.getOrDefault("Server returned ${response.code}"))
            return bytes
        }
    }
    suspend fun pair(code: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!identity.connected) { "Disconnect before pairing with another server." }
            if (identity.enrollment() != null) { finishEnrollment(); return@withLock }
            require(code.startsWith("zap://pair#")) { "Scan the pairing code shown in Zap on Mac." }
            val pairing = JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.substringAfter('#'))))
            val url = pairing.getString("url").trimEnd('/')
            val endpoint = Uri.parse(url)
            require(endpoint.scheme == "https" || (BuildConfig.DEBUG && endpoint.scheme == "http" && endpoint.host in listOf("localhost", "127.0.0.1"))) { "Pairing requires an HTTPS server." }
            val keys = pairing.getJSONObject("keys"); val keyId = pairing.getString("keyId"); val public = identity.value("public").unb64()
            val envelopes = JSONObject()
            keys.keys().forEach { id ->
                val key = keys.getString(id).unb64()
                require(key.size == 32) { "Invalid pairing key" }
                envelopes.put(id, Crypto.wrap(key, public, id))
            }
            val body = JSONObject().put("token", pairing.getString("token")).put("name", Build.MODEL).put("publicKey", public.b64()).put("keyId", keyId).put("envelope", envelopes.getJSONObject(keyId)).put("historyEnvelopes", envelopes)
            identity.update("enrollment" to PendingEnrollment.create(url, body, keys).record.toString())
            updateBackgroundSync()
            finishEnrollment()
        }
        updateBackgroundSync(); sync(); if (active) connectSocket()
    }
    private fun finishEnrollment() {
        val pending = identity.enrollment() ?: return
        try {
            val result = JSONObject(String(request("/v1/pair", "POST", pending.body.toString().toByteArray(), url = pending.url)))
            identity.update(*pending.completed(result))
            connected.value = true; server.value = pending.url; deviceId.value = identity.value("deviceId"); cursor = -1; error.value = null
            updateBackgroundSync(); if (active) connectSocket()
        } catch (e: ApiException) {
            if (e.status in listOf(400, 401, 409)) { identity.update("enrollment" to ""); updateBackgroundSync() }
            throw e
        }
    }
    suspend fun invite(): String = withContext(Dispatchers.IO) {
        sync()
        mutex.withLock {
            val token = JSONObject(String(request("/v1/invitations", "POST"))).getString("token")
            val code = JSONObject().put("url", identity.value("url")).put("token", token).put("keyId", identity.value("keyId")).put("keys", identity.keys())
            "zap://pair#" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(code.toString().toByteArray())
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var warning: String? = null
            if (identity.connected) {
                socket?.close(1000, null); socket = null; reconnect?.cancel(); status.value = "Disconnecting…"
                val unreachable = "Disconnected on this phone. The server could not be reached, so remove this phone from another paired device."
                warning = try { request("/v1/devices/me", "DELETE"); null }
                catch (e: CancellationException) { throw e }
                catch (e: ApiException) { if (e.status == 401) null else unreachable }
                catch (e: Exception) { unreachable }
            }
            forget(warning)
        }
    }
    /** Return to local-only history, keeping everything captured here for the next server. */
    private suspend fun forget(message: String?) {
        socket?.close(1000, null); socket = null; reconnect?.cancel()
        dao.markAllPending(); unavailableIds.clear()
        for (id in dao.deletions()) dao.deleted(id)
        identity.update("url" to "", "token" to "", "deviceId" to "", "keyId" to "", "keys" to JSONObject())
        connected.value = false; server.value = ""; deviceId.value = ""; cursor = -1; devices.value = emptyList()
        prefs.edit().remove("clearPending").putBoolean("daysPending", true).commit()
        status.value = "Local history"; error.value = message; refresh()
        updateBackgroundSync()
    }
    suspend fun removeDevice(deviceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = Crypto.randomKey(); val keyId = UUID.randomUUID().toString(); val envelopes = JSONObject()
            for (device in devices.value.filter { it.getString("id") != deviceId }) envelopes.put(device.getString("id"), Crypto.wrap(key, device.getString("publicKey").unb64(), keyId))
            val body = JSONObject().put("removeDevice", deviceId).put("keyId", keyId).put("envelopes", envelopes)
            request("/v1/rotate", "POST", body.toString().toByteArray())
        }; sync()
    }
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                refresh()
                unavailableIds.clear()
                if (!identity.connected) finishEnrollment()
                if (!identity.connected) return@withLock true
                if (prefs.getBoolean("clearPending", false)) { request("/v1/items", "DELETE"); prefs.edit().remove("clearPending").commit() }
                for (id in dao.deletions()) { request("/v1/items/$id", "DELETE"); dao.deleted(id) }
                if (prefs.getBoolean("daysPending", false)) { request("/v1/settings", "PUT", JSONObject().put("days", days.value).toString().toByteArray()); prefs.edit().remove("daysPending").commit() }
                val requestedCursor = if (payloads.damaged.isEmpty()) cursor else -1L
                val state = JSONObject(String(request("/v1/sync?cursor=$requestedCursor")))
                val keys = state.getJSONArray("keys")
                var identityChanged = identity.value("keyId") != state.getString("keyId")
                for (i in 0 until keys.length()) {
                    val entry = keys.getJSONObject(i); val id = entry.getString("keyId")
                    if (!identity.hasKey(id)) { identity.putKey(id, Crypto.unwrap(entry.getJSONObject("envelope"), identity.value("private").unb64(), id)); identityChanged = true }
                }
                identity.put("keyId", state.getString("keyId")); if (identityChanged) identity.save()
                if (days.value != state.getInt("days")) { days.value = state.getInt("days"); prefs.edit().putInt("days", days.value).commit() }
                val deviceList = state.getJSONArray("devices"); devices.value = (0 until deviceList.length()).map { deviceList.getJSONObject(it) }
                if (!state.isNull("items")) {
                    val array = state.getJSONArray("items"); val items = (0 until array.length()).map { array.getJSONObject(it) }
                    val remoteIds = items.map { it.getString("id") }.toSet(); val localIds = clips.value.map { it.id }.toSet()
                    for (clip in dao.all().filter { !it.pending && it.id !in remoteIds }) removeLocal(clip.id)
                    for (item in items.filter { it.getString("id") !in localIds }) {
                        val id = item.getString("id")
                        try {
                            val raw = Crypto.open(request("/v1/items/$id"), identity.key(item.getString("keyId")), id)
                            val payload = JSONObject(String(raw)); require(payload.getLong("createdAt") == item.getLong("createdAt")) { "Content metadata did not match" }
                            save(Clip(id, item.getLong("createdAt"), payload, false))
                        } catch (e: CancellationException) { throw e }
                        catch (e: ApiException) { if (e.status == 401) throw e; if (e.status != 404) unavailableIds.add(id) }
                        catch (e: java.io.IOException) { throw e }
                        catch (e: Exception) { unavailableIds.add(id) }
                    }
                }
                cursor = if (unavailableIds.isEmpty()) state.getLong("cursor") else -1L; refresh()
                for (clip in clips.value.filter { it.pending }) {
                    val keyId = identity.value("keyId")
                    val encrypted = Crypto.seal(clip.payload.toString().toByteArray(), identity.key(keyId), clip.id)
                    try { request("/v1/items/${clip.id}", "PUT", encrypted, mapOf("X-Key-Id" to keyId, "X-Created-At" to clip.createdAt.toString())); dao.sent(clip.id) }
                    catch (e: ApiException) { if (e.status == 410) removeLocal(clip.id) else throw e }
                }
                refresh(); status.value = if (recovery.value == null) "Up to date" else "Some items need recovery"; error.value = null; true
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) {
                if (e.status == 401) forget("This phone is no longer paired. Scan a new pairing code to rejoin your history.")
                else { status.value = "Offline · will retry"; error.value = e.message }
                false
            }
            catch (e: Exception) { status.value = "Offline · will retry"; error.value = e.message ?: "Could not sync"; false }
        }
    }
    @Synchronized fun foreground(value: Boolean) {
        active = value
        if (value) { reconnectDelay = 2_000; syncRequests.request(); connectSocket() }
        else { reconnect?.cancel(); socket?.close(1000, null); socket = null }
    }
    @Synchronized private fun connectSocket() {
        if (!active || !identity.connected || socket != null) return
        val request = Request.Builder().url(identity.value("url") + "/v1/events").header("Authorization", "Bearer ${identity.value("token")}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socketChanged(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { socketChanged(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retrySocket(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retrySocket(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }
    @Synchronized private fun socketChanged(current: WebSocket) {
        if (!active || socket !== current) return
        reconnectDelay = 2_000
        syncRequests.request()
    }
    @Synchronized private fun retrySocket(failed: WebSocket) {
        if (socket !== failed) return
        socket = null; reconnect?.cancel()
        if (!active || !identity.connected) return
        val wait = reconnectDelay; reconnectDelay = (wait * 2).coerceAtMost(120_000)
        reconnect = scope.launch { delay(wait); connectSocket() }
    }
}
