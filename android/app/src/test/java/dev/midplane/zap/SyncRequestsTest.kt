package dev.midplane.zap

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRequestsTest {
    @Test fun burstDuringSyncRunsOneFollowUp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = CompletableDeferred<Unit>()
            var calls = 0
            val requests = SyncRequests(scope) { calls++; if (calls == 1) first.await() }
            requests.request()
            repeat(100) { requests.request() }
            assertEquals(1, calls)
            first.complete(Unit)
            assertEquals(2, calls)
            requests.request()
            assertEquals(3, calls)
        } finally { scope.cancel() }
    }
}
