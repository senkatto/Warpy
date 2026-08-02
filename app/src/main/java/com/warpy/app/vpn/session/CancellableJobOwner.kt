package com.warpy.app.vpn.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CancellableJobOwner(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun launch(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        val previous = synchronized(lock) {
            activeJob.also { activeJob = job }
        }
        previous?.cancel()
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJob === job) activeJob = null
            }
        }
        job.start()
        return job
    }

    fun cancel() {
        val job = synchronized(lock) {
            activeJob.also { activeJob = null }
        }
        job?.cancel()
    }

    fun isActive(): Boolean = synchronized(lock) {
        activeJob?.isActive == true
    }
}
