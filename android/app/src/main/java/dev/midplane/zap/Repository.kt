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
    private val payloads = mutableMapOf<String, JSONObject>()
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
    val devices = MutableStateFlow<List<JSONObject>>(emptyList())
    val connected = MutableStateFlow(identity.connected)
    init { scope.launch { mutex.withLock { try { refresh() } catch (e: Exception) { error.value = e.message } } } }
    private suspend fun save(clip: Clip) {
        val file = File(directory, clip.id)
        val temp = File(directory, "${clip.id}.tmp")
        temp.writeBytes(Crypto.seal(clip.payload.toString().toByteArray(), identity.localKey, clip.id))
        check(temp.renameTo(file)) { "Could not save history" }
        dao.save(StoredClip(clip.id, clip.createdAt, clip.pending))
        payloads[clip.id] = clip.payload
    }
    private suspend fun removeLocal(id: String) { dao.remove(id); File(directory, id).delete(); payloads.remove(id) }
    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (now >= nextCacheCleanup) {
            File(context.cacheDir, "shared").listFiles()?.filter { now - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            nextCacheCleanup = now + 3_600_000
        }
        val cutoff = now - days.value * 86_400_000L
        val stored = dao.all()
        for (clip in stored.filter { it.createdAt <= cutoff }) removeLocal(clip.id)
        val retained = stored.filter { it.createdAt > cutoff }
        payloads.keys.retainAll(retained.map { it.id }.toSet())
        clips.value = retained.map {
            val payload = payloads.getOrPut(it.id) { JSONObject(String(Crypto.open(File(directory, it.id).readBytes(), identity.localKey, it.id))) }
            Clip(it.id, it.createdAt, payload, it.pending)
        }
    }
    fun updateBackgroundSync() {
        val manager = WorkManager.getInstance(context)
        if (identity.connected) {
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
        require(text.isNotEmpty()) { "Clipboard is empty" }
        require(text.toByteArray().size <= 1024 * 1024) { "Text is too large. Maximum: 1 MiB." }
        add(JSONObject().put("kind", "text").put("text", text))
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
            for (clip in dao.all()) { if (identity.connected) dao.queue(Deletion(clip.id)); removeLocal(clip.id) }
            refresh()
        }; enqueue()
    }
    suspend fun setDays(value: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            days.value = value.coerceIn(1, 365); prefs.edit().putInt("days", days.value).putBoolean("daysPending", true).commit(); refresh()
        }; enqueue()
    }
    private fun request(path: String, method: String = "GET", body: ByteArray? = null, headers: Map<String, String> = emptyMap(), url: String = identity.state.getString("url")): ByteArray {
        val builder = Request.Builder().url(url + path)
        if (url == identity.state.getString("url") && identity.connected) builder.header("Authorization", "Bearer ${identity.state.getString("token")}")
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
            require(code.startsWith("zap://pair#")) { "Scan the pairing code shown in Zap on Mac." }
            val pairing = JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.substringAfter('#'))))
            val url = pairing.getString("url").trimEnd('/')
            val endpoint = Uri.parse(url)
            require(endpoint.scheme == "https" || (BuildConfig.DEBUG && endpoint.scheme == "http" && endpoint.host in listOf("localhost", "127.0.0.1"))) { "Pairing requires an HTTPS server." }
            val keys = pairing.getJSONObject("keys"); val keyId = pairing.getString("keyId"); val public = identity.state.getString("public").unb64()
            val envelopes = JSONObject()
            keys.keys().forEach { id ->
                val key = keys.getString(id).unb64()
                require(key.size == 32) { "Invalid pairing key" }
                envelopes.put(id, Crypto.wrap(key, public, id))
            }
            val body = JSONObject().put("token", pairing.getString("token")).put("name", Build.MODEL).put("publicKey", public.b64()).put("keyId", keyId).put("envelope", envelopes.getJSONObject(keyId)).put("historyEnvelopes", envelopes)
            val result = JSONObject(String(request("/v1/pair", "POST", body.toString().toByteArray(), url = url)))
            identity.state.put("url", url).put("token", result.getString("token")).put("deviceId", result.getString("deviceId")).put("keyId", keyId).put("keys", keys)
            identity.save(); connected.value = true; cursor = -1; error.value = null
        }
        updateBackgroundSync(); sync(); if (active) connectSocket()
    }
    suspend fun invite(): String = withContext(Dispatchers.IO) {
        sync()
        mutex.withLock {
            val token = JSONObject(String(request("/v1/invitations", "POST"))).getString("token")
            val code = JSONObject().put("url", identity.state.getString("url")).put("token", token).put("keyId", identity.state.getString("keyId")).put("keys", identity.keys)
            "zap://pair#" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(code.toString().toByteArray())
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            socket?.close(1000, null); socket = null; reconnect?.cancel()
            for (clip in clips.value) save(clip.copy(pending = true))
            for (id in dao.deletions()) dao.deleted(id)
            identity.state.put("url", "").put("token", "").put("deviceId", "").put("keyId", "").put("keys", JSONObject())
            identity.save(); connected.value = false; cursor = -1; devices.value = emptyList()
            prefs.edit().remove("clearPending").putBoolean("daysPending", true).commit()
            status.value = "Local history"; error.value = null; refresh()
        }
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
                if (!identity.connected) return@withLock true
                if (prefs.getBoolean("clearPending", false)) { request("/v1/items", "DELETE"); prefs.edit().remove("clearPending").commit() }
                for (id in dao.deletions()) { request("/v1/items/$id", "DELETE"); dao.deleted(id) }
                if (prefs.getBoolean("daysPending", false)) { request("/v1/settings", "PUT", JSONObject().put("days", days.value).toString().toByteArray()); prefs.edit().remove("daysPending").commit() }
                val state = JSONObject(String(request("/v1/sync?cursor=$cursor")))
                val keys = state.getJSONArray("keys")
                var identityChanged = identity.state.getString("keyId") != state.getString("keyId")
                for (i in 0 until keys.length()) {
                    val entry = keys.getJSONObject(i); val id = entry.getString("keyId")
                    if (!identity.keys.has(id)) { identity.keys.put(id, Crypto.unwrap(entry.getJSONObject("envelope"), identity.state.getString("private").unb64(), id).b64()); identityChanged = true }
                }
                identity.state.put("keyId", state.getString("keyId")); if (identityChanged) identity.save()
                if (days.value != state.getInt("days")) { days.value = state.getInt("days"); prefs.edit().putInt("days", days.value).commit() }
                val deviceList = state.getJSONArray("devices"); devices.value = (0 until deviceList.length()).map { deviceList.getJSONObject(it) }
                if (!state.isNull("items")) {
                    val array = state.getJSONArray("items"); val items = (0 until array.length()).map { array.getJSONObject(it) }
                    val remoteIds = items.map { it.getString("id") }.toSet(); val localIds = clips.value.map { it.id }.toSet()
                    for (clip in clips.value.filter { !it.pending && it.id !in remoteIds }) removeLocal(clip.id)
                    for (item in items.filter { it.getString("id") !in localIds }) {
                        val id = item.getString("id")
                        try {
                            val raw = Crypto.open(request("/v1/items/$id"), identity.keys.getString(item.getString("keyId")).unb64(), id)
                            val payload = JSONObject(String(raw)); require(payload.getLong("createdAt") == item.getLong("createdAt")) { "Content metadata did not match" }
                            save(Clip(id, item.getLong("createdAt"), payload, false))
                        } catch (e: ApiException) { if (e.status != 404) throw e }
                    }
                }
                cursor = state.getLong("cursor"); refresh()
                for (clip in clips.value.filter { it.pending }) {
                    val keyId = identity.state.getString("keyId")
                    val encrypted = Crypto.seal(clip.payload.toString().toByteArray(), identity.keys.getString(keyId).unb64(), clip.id)
                    try { request("/v1/items/${clip.id}", "PUT", encrypted, mapOf("X-Key-Id" to keyId, "X-Created-At" to clip.createdAt.toString())); dao.sent(clip.id) }
                    catch (e: ApiException) { if (e.status == 410) removeLocal(clip.id) else throw e }
                }
                refresh(); status.value = "Up to date"; error.value = null; true
            } catch (e: CancellationException) { throw e }
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
        val request = Request.Builder().url(identity.state.getString("url") + "/v1/events").header("Authorization", "Bearer ${identity.state.getString("token")}").build()
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
