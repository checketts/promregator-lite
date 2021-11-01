package com.github.promregator.discovery

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val log = mu.KotlinLogging.logger {  }

class RateLimiter(
        val maxRequestsPerMinute: Int = 60,
        val meterRegistry: MeterRegistry? = null,
) {
    private val _availableTokens = AtomicInteger(maxRequestsPerMinute)
    private val isRefilling = AtomicBoolean(false)
    private val _totalWaitTimeMs = AtomicLong(0)
    private val _waitQueue = AtomicInteger(0)
    private val refillRate = maxRequestsPerMinute / 60
    val availableTokens : Int get() = _availableTokens.get()
    val totalWaitTimeMs : Long get() = _totalWaitTimeMs.get()
    val waitQueue : Int get() = _waitQueue.get()

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("DeferredResultUnused")
    private suspend fun refillToken() {
        if(isRefilling.get()) return

        // Using GlobalScope because we want to kick it off and have it run outside a coroutine scope
        GlobalScope.async(Dispatchers.Default) {
            isRefilling.set(true)
            while(isRefilling.get()) {
                delay(1000)
                val avail = availableTokens
                if(avail < maxRequestsPerMinute) {
                    val newTokenCount = _availableTokens.addAndGet(refillRate)
                    if(newTokenCount > maxRequestsPerMinute) {
                        _availableTokens.set(maxRequestsPerMinute)
                    }
                } else {
                    isRefilling.set(false)
                }
            }
        }
    }

    suspend fun <T> whenTokenAvailable(block: suspend () -> T): T? {
            var tokenAcquired = false
            while(!tokenAcquired) {
                refillToken()
                if(availableTokens > 0) {
                    val currentCount = _availableTokens.decrementAndGet()
                    log.debug { "Current tokens = $currentCount" }
                    tokenAcquired = currentCount >= 0
                }
                if(tokenAcquired) {
                    return block()
                } else {
                    _waitQueue.incrementAndGet()
                    delay(1000)
                    _totalWaitTimeMs.addAndGet(1000)
                    _waitQueue.decrementAndGet()
                }
            }
            return null


    }
}

fun main() {
    val bucket = RateLimiter()

    runBlocking {
        (1..100).forEach { count ->
            val response = bucket.whenTokenAvailable { "Got $count" }
        }
    }
}