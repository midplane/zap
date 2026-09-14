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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 40_000_000L
private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
private const val MAX_RECONNECT_DELAY_MS = 120_000L
private const val REFRESH_WORK = "zap-refresh"
private const val SEND_WORK = "zap-send"

private const val PREF_DAYS = "days"
private const val PREF_CLEAR_PENDING = "clearPending"
private const val PREF_DAYS_PENDING = "daysPending"

class ZapApplication : Application() {
    val repository by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        repository.updateBackgroundSync()
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val synced = (applicationContext as ZapApplication).repository.sync()
        return if (synced) Result.success() else Result.retry()
    }
}

data class Clip(val id: String, val createdAt: Long, val payload: JSONObject, val pending: Boolean) {
    val kind: String get() = payload.getString("kind")
    val text: String get() = payload.optString("text")
    val source: String get() = payload.optString("source", "Android")
}

class ApiException(val status: Int, override val message: String) : Exception(message)

internal fun sharedImagesDirectory(context: Context) = File(context.cacheDir, "shared")

internal fun deleteStaleSharedImages(context: Context) {
    val now = System.currentTimeMillis()
    sharedImagesDirectory(context).listFiles()?.filter { now - it.lastModified() > HOUR_MS }?.forEach { it.delete() }
}

private fun JSONObject.bytes() = toString().toByteArray()

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

class Repository(private val context: Context) {
    val identity = Identity(context)
    private val dao = Room.databaseBuilder(context, HistoryDatabase::class.java, "history.sqlite").build().clips()
    private val payloads = PayloadFiles(File(context.filesDir, "content").apply { mkdirs() }, identity.localKey)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = SyncRequests(scope) { sync() }

    private val unavailableIds = mutableSetOf<String>()
    private var nextCacheCleanup = 0L
    /** The server revision last synced completely, or -1 to request every item. */
    private var cursor = -1L
    private var socket: WebSocket? = null
    private var inForeground = false
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS

    val clips = MutableStateFlow<List<Clip>>(emptyList())
    val days = MutableStateFlow(prefs.getInt(PREF_DAYS, 10))
    val status = MutableStateFlow(if (identity.connected) "Connecting…" else "Local history")
    val error = MutableStateFlow<String?>(null)
    val recovery = MutableStateFlow<String?>(null)
    val damagedCount = MutableStateFlow(0)
    val devices = MutableStateFlow<List<JSONObject>>(emptyList())
    val connected = MutableStateFlow(identity.connected)
    val server = MutableStateFlow(identity.value("url"))
    val deviceId = MutableStateFlow(identity.value("deviceId"))

    init {
        scope.launch {
            mutex.withLock {
                try {
                    refresh()
                } catch (e: Exception) {
                    error.value = e.message
                }
            }
        }
    }

    /** Call with [mutex] held. */
    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (now >= nextCacheCleanup) {
            deleteStaleSharedImages(context)
            nextCacheCleanup = now + HOUR_MS
        }
        val cutoff = now - days.value * DAY_MS
        val stored = dao.all()
        val readable = payloads.readAll(stored.map { it.id }.toSet())
        // Damaged records outlive retention so they can still be recovered.
        for (clip in stored.filter { it.createdAt <= cutoff && it.id !in payloads.damaged }) removeLocal(clip.id)
        clips.value = stored
            .filter { it.createdAt > cutoff }
            .mapNotNull { record -> readable[record.id]?.let { Clip(record.id, record.createdAt, it, record.pending) } }
        damagedCount.value = payloads.damaged.size
        val unreadable = (payloads.damaged + unavailableIds).size
        recovery.value = if (unreadable == 0) {
            null
        } else {
            "Unable to read $unreadable saved ${if (unreadable == 1) "item" else "items"}. Synced content retries automatically. Damaged local records are kept for recovery."
        }
    }

    private suspend fun save(clip: Clip) {
        payloads.save(clip.id, clip.payload)
        dao.save(StoredClip(clip.id, clip.createdAt, clip.pending))
    }

    private suspend fun removeLocal(id: String) {
        payloads.remove(id)
        dao.remove(id)
    }

    suspend fun addText(text: String) = withContext(Dispatchers.IO) {
        add(TextCapture.payload(text))
    }

    suspend fun addImage(uri: Uri) = withContext(Dispatchers.IO) {
        val data = readImage(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
            "Use a static image up to 40 megapixels."
        }
        require(bounds.outMimeType != "image/gif") { "Animated images are not supported. Share a PNG or JPEG." }

        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Image format is not supported")
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
        bitmap.recycle()
        require(png.size() <= MAX_IMAGE_BYTES) { "Converted image exceeds 20 MiB." }
        add(JSONObject().put("kind", "image").put("png", png.toByteArray().b64()))
    }

    private fun readImage(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: error("Image could not be opened")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMAGE_BYTES) { "Image is too large. Maximum: 20 MiB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private suspend fun add(payload: JSONObject) {
        mutex.withLock {
            val newest = clips.value.firstOrNull()?.payload
            if (newest?.optString("text") == payload.optString("text") && newest?.optString("png") == payload.optString("png")) return
            val now = System.currentTimeMillis()
            payload.put("source", Build.MODEL).put("createdAt", now)
            save(Clip(UUID.randomUUID().toString(), now, payload, pending = true))
            refresh()
        }
        requestSync()
    }

    suspend fun delete(clip: Clip) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (identity.connected) dao.queue(Deletion(clip.id))
            removeLocal(clip.id)
            refresh()
        }
        requestSync()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putBoolean(PREF_CLEAR_PENDING, identity.connected).commit()
            for (clip in dao.all()) removeLocal(clip.id)
            unavailableIds.clear()
            refresh()
        }
        requestSync()
    }

    suspend fun discardDamaged() = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (id in payloads.damaged.toList()) {
                if (identity.connected) dao.queue(Deletion(id))
                removeLocal(id)
            }
            refresh()
        }
        requestSync()
    }

    suspend fun setDays(value: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            days.value = value.coerceIn(1, 365)
            prefs.edit().putInt(PREF_DAYS, days.value).putBoolean(PREF_DAYS_PENDING, true).commit()
            refresh()
        }
        requestSync()
    }

    fun updateBackgroundSync() {
        val workManager = WorkManager.getInstance(context)
        if (identity.connected || identity.enrollment() != null) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            workManager.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            workManager.cancelUniqueWork(REFRESH_WORK)
            workManager.cancelUniqueWork(SEND_WORK)
        }
    }

    private fun requestSync() {
        if (!identity.connected) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SEND_WORK, ExistingWorkPolicy.KEEP, work)
        syncRequests.request()
    }

    suspend fun pair(code: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!identity.connected) { "Disconnect before pairing with another server." }
            if (identity.enrollment() == null) {
                stageEnrollment(code)
                updateBackgroundSync()
            }
            finishEnrollment()
        }
        updateBackgroundSync()
        sync()
        if (inForeground) connectSocket()
    }

    private fun stageEnrollment(code: String) {
        require(code.startsWith(PAIRING_CODE_PREFIX)) { "Scan the pairing code shown in Zap on Mac." }
        val pairing = decodePairingCode(code)
        val url = pairing.getString("url").trimEnd('/')
        val endpoint = Uri.parse(url)
        val allowed = endpoint.scheme == "https" ||
            (BuildConfig.DEBUG && endpoint.scheme == "http" && endpoint.host in listOf("localhost", "127.0.0.1"))
        require(allowed) { "Pairing requires an HTTPS server." }

        val keys = pairing.getJSONObject("keys")
        val keyId = pairing.getString("keyId")
        val publicKey = identity.value("public").unb64()
        // Wrap every key, not just the current one, so this phone can read history from before the last rotation.
        val envelopes = JSONObject()
        keys.keys().forEach { id ->
            val key = keys.getString(id).unb64()
            require(key.size == 32) { "Invalid pairing key" }
            envelopes.put(id, Crypto.wrap(key, publicKey, id))
        }
        val body = JSONObject()
            .put("token", pairing.getString("token"))
            .put("name", Build.MODEL)
            .put("publicKey", publicKey.b64())
            .put("keyId", keyId)
            .put("envelope", envelopes.getJSONObject(keyId))
            .put("historyEnvelopes", envelopes)
        identity.update("enrollment" to PendingEnrollment.create(url, body, keys).record.toString())
    }

    /** Call with [mutex] held. */
    private fun finishEnrollment() {
        val pending = identity.enrollment() ?: return
        try {
            val result = JSONObject(String(request("/v1/pair", "POST", pending.body.bytes(), url = pending.url)))
            identity.update(*pending.completed(result))
            connected.value = true
            server.value = pending.url
            deviceId.value = identity.value("deviceId")
            cursor = -1
            error.value = null
            updateBackgroundSync()
            if (inForeground) connectSocket()
        } catch (e: ApiException) {
            // A definitive rejection permits a new attempt. Transport failures keep the request for retry.
            if (e.status in listOf(400, 401, 409)) {
                identity.update("enrollment" to "")
                updateBackgroundSync()
            }
            throw e
        }
    }

    suspend fun invite(): String = withContext(Dispatchers.IO) {
        sync()
        mutex.withLock {
            val token = JSONObject(String(request("/v1/invitations", "POST"))).getString("token")
            val pairing = JSONObject()
                .put("url", identity.value("url"))
                .put("token", token)
                .put("keyId", identity.value("keyId"))
                .put("keys", identity.keys())
            encodePairingCode(pairing)
        }
    }

    suspend fun removeDevice(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = Crypto.randomKey()
            val keyId = UUID.randomUUID().toString()
            val envelopes = JSONObject()
            for (device in devices.value.filter { it.getString("id") != id }) {
                envelopes.put(device.getString("id"), Crypto.wrap(key, device.getString("publicKey").unb64(), keyId))
            }
            val body = JSONObject().put("removeDevice", id).put("keyId", keyId).put("envelopes", envelopes)
            request("/v1/rotate", "POST", body.bytes())
        }
        sync()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var warning: String? = null
            if (identity.connected) {
                closeSocket()
                status.value = "Disconnecting…"
                val unreachable = "Disconnected on this phone. The server could not be reached, so remove this phone from another paired device."
                warning = try {
                    request("/v1/devices/me", "DELETE")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 401: this phone was already removed.
                    if (e.status == 401) null else unreachable
                } catch (e: Exception) {
                    unreachable
                }
            }
            forget(warning)
        }
    }

    /** Return to local-only history, keeping everything captured here for the next server. Call with [mutex] held. */
    private suspend fun forget(message: String?) {
        closeSocket()
        dao.markAllPending()
        unavailableIds.clear()
        for (id in dao.deletions()) dao.deleted(id)
        identity.update("url" to "", "token" to "", "deviceId" to "", "keyId" to "", "keys" to JSONObject())
        connected.value = false
        server.value = ""
        deviceId.value = ""
        cursor = -1
        devices.value = emptyList()
        // Carry this phone's retention setting to the next server.
        prefs.edit().remove(PREF_CLEAR_PENDING).putBoolean(PREF_DAYS_PENDING, true).commit()
        status.value = "Local history"
        error.value = message
        refresh()
        updateBackgroundSync()
    }

    /** Returns false if the sync failed and should be retried. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                refresh()
                unavailableIds.clear()
                if (!identity.connected) finishEnrollment()
                if (!identity.connected) return@withLock true

                pushLocalChanges()
                // Damaged local files need the full item list so they can be downloaded again.
                val requestedCursor = if (payloads.damaged.isEmpty()) cursor else -1L
                val state = JSONObject(String(request("/v1/sync?cursor=$requestedCursor")))
                applyServerState(state)
                if (!state.isNull("items")) reconcile(state.getJSONArray("items").objects())
                cursor = if (unavailableIds.isEmpty()) state.getLong("cursor") else -1L
                refresh()
                uploadPending()
                refresh()

                status.value = if (recovery.value == null) "Up to date" else "Some items need recovery"
                error.value = null
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 401) {
                    forget("This phone is no longer paired. Scan a new pairing code to rejoin your history.")
                } else {
                    showOffline(e.message)
                }
                false
            } catch (e: Exception) {
                showOffline(e.message ?: "Could not sync")
                false
            }
        }
    }

    private fun showOffline(message: String) {
        status.value = "Offline · will retry"
        error.value = message
    }

    private suspend fun pushLocalChanges() {
        if (prefs.getBoolean(PREF_CLEAR_PENDING, false)) {
            request("/v1/items", "DELETE")
            prefs.edit().remove(PREF_CLEAR_PENDING).commit()
        }
        for (id in dao.deletions()) {
            request("/v1/items/$id", "DELETE")
            dao.deleted(id)
        }
        if (prefs.getBoolean(PREF_DAYS_PENDING, false)) {
            request("/v1/settings", "PUT", JSONObject().put("days", days.value).bytes())
            prefs.edit().remove(PREF_DAYS_PENDING).commit()
        }
    }

    private fun applyServerState(state: JSONObject) {
        var identityChanged = identity.value("keyId") != state.getString("keyId")
        for (entry in state.getJSONArray("keys").objects()) {
            val keyId = entry.getString("keyId")
            if (!identity.hasKey(keyId)) {
                identity.putKey(keyId, Crypto.unwrap(entry.getJSONObject("envelope"), identity.value("private").unb64(), keyId))
                identityChanged = true
            }
        }
        identity.put("keyId", state.getString("keyId"))
        if (identityChanged) identity.save()

        if (days.value != state.getInt("days")) {
            days.value = state.getInt("days")
            prefs.edit().putInt(PREF_DAYS, days.value).commit()
        }
        devices.value = state.getJSONArray("devices").objects()
    }

    private suspend fun reconcile(items: List<JSONObject>) {
        val remoteIds = items.map { it.getString("id") }.toSet()
        val localIds = clips.value.map { it.id }.toSet()
        for (clip in dao.all().filter { !it.pending && it.id !in remoteIds }) removeLocal(clip.id)
        for (item in items.filter { it.getString("id") !in localIds }) {
            val id = item.getString("id")
            try {
                download(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 401) throw e
                if (e.status != 404) unavailableIds.add(id)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                unavailableIds.add(id)
            }
        }
    }

    private suspend fun download(item: JSONObject) {
        val id = item.getString("id")
        val raw = Crypto.open(request("/v1/items/$id"), identity.key(item.getString("keyId")), id)
        val payload = JSONObject(String(raw))
        require(payload.getLong("createdAt") == item.getLong("createdAt")) { "Content metadata did not match" }
        save(Clip(id, item.getLong("createdAt"), payload, pending = false))
    }

    private suspend fun uploadPending() {
        for (clip in clips.value.filter { it.pending }) {
            val keyId = identity.value("keyId")
            val encrypted = Crypto.seal(clip.payload.bytes(), identity.key(keyId), clip.id)
            val headers = mapOf("X-Key-Id" to keyId, "X-Created-At" to clip.createdAt.toString())
            try {
                request("/v1/items/${clip.id}", "PUT", encrypted, headers)
                dao.sent(clip.id)
            } catch (e: ApiException) {
                // 410: deleted on another device, or already past retention.
                if (e.status == 410) removeLocal(clip.id) else throw e
            }
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        url: String = identity.value("url")
    ): ByteArray {
        val builder = Request.Builder().url(url + path)
        // The device token is only sent to the server this phone is connected to.
        if (url == identity.value("url") && identity.connected) builder.header("Authorization", "Bearer ${identity.value("token")}")
        for ((name, value) in headers) builder.header(name, value)
        // OkHttp requires a body for POST and PUT.
        val requestBody = if (method in listOf("POST", "PUT")) (body ?: ByteArray(0)).toRequestBody() else body?.toRequestBody()
        builder.method(method, requestBody)
        http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(String(bytes)).getString("error") }.getOrDefault("Server returned ${response.code}")
                throw ApiException(response.code, message)
            }
            return bytes
        }
    }

    @Synchronized fun onForegroundChanged(visible: Boolean) {
        inForeground = visible
        if (visible) {
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            syncRequests.request()
            connectSocket()
        } else {
            closeSocket()
        }
    }

    @Synchronized private fun connectSocket() {
        if (!inForeground || !identity.connected || socket != null) return
        val request = Request.Builder()
            .url(identity.value("url") + "/v1/events")
            .header("Authorization", "Bearer ${identity.value("token")}")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onSocketActivity(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { onSocketActivity(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { reconnectLater(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnectLater(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }

    @Synchronized private fun closeSocket() {
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    @Synchronized private fun onSocketActivity(current: WebSocket) {
        if (!inForeground || socket !== current) return
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        syncRequests.request()
    }

    @Synchronized private fun reconnectLater(failed: WebSocket) {
        if (socket !== failed) return
        socket = null
        reconnectJob?.cancel()
        if (!inForeground || !identity.connected) return
        val wait = reconnectDelay
        reconnectDelay = (wait * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectJob = scope.launch {
            delay(wait)
            connectSocket()
        }
    }
}
