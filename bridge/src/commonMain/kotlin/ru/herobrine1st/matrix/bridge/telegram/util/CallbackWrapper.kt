package ru.herobrine1st.matrix.bridge.telegram.util

import kotlinx.coroutines.Job

internal class CallbackWrapper<T>(
    private val value: T,
) {
    private val _job = Job()
    val job: Job get() = _job

    internal suspend inline fun use(block: suspend (T) -> Unit) {
        try {
            block(value)
        } catch (e: Throwable) {
            if (!_job.completeExceptionally(e)) {
                throw IllegalStateException("This can only be used once!")
                    .apply {
                        addSuppressed(e)
                    }
            }
            throw e
        }

        if (!_job.complete()) {
            throw IllegalStateException("This can only be used once!")
        }
    }
}