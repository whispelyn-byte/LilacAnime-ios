package com.lilac.anime.android.data

import com.lilac.anime.shared.IosServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The shared services answer through callbacks on the main thread (they were made for Swift); this waits for one. */
class ServiceFailure(message: String) : Exception(message)

suspend fun <T> IosServices.await(call: IosServices.((T?, String?) -> Unit) -> Unit): Result<T> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        call { value, error ->
            if (!continuation.isActive) return@call
            continuation.resume(if (value != null) Result.success(value) else Result.failure(ServiceFailure(error ?: "응답이 없습니다.")))
        }
    }
