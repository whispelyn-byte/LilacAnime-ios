package com.lilac.anime.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IosServicesCancellationTest {
    @Test fun immediateCancellationStillCompletesTheSwiftCallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val service = IosServices(dispatcher)
        try {
            var calls = 0
            var error: String? = null
            service.catalogKoreanIndex { value, failure -> calls++; assertNull(value); error = failure }
            service.cancel()
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals("요청을 취소했습니다.", error)
        } finally { service.close(); Dispatchers.resetMain() }
    }
    @Test fun closingBeforeWorkerStartsStillCompletesTheSwiftCallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val service = IosServices(dispatcher)
        try {
            var calls = 0
            service.catalogKoreanIndex { value, failure -> calls++; assertNull(value); assertNotNull(failure) }
            service.close()
            advanceUntilIdle()
            assertEquals(1, calls)
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
