package dev.midplane.zap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Runs [sync] one at a time. Requests made while a sync is running collapse into a single follow-up. */
class SyncRequests(scope: CoroutineScope, sync: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) sync()
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
