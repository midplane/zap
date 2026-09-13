package dev.midplane.zap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class SyncRequests(scope: CoroutineScope, sync: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init { scope.launch { for (request in requests) sync() } }
    fun request() { requests.trySend(Unit) }
}
