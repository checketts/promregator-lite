package com.github.promregator.discovery

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import com.github.promregator.config.PromregatorConfiguration
import com.github.promregator.discovery.SimpleAppInstanceScanner.Instance
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

private val log = mu.KotlinLogging.logger {}

@Service
class DiscoveryService(
        private val promregatorConfiguration: PromregatorConfiguration,
        private val targetResolver: SimpleTargetResolver,
        private val appInstanceScanner: SimpleAppInstanceScanner,
) {
    val cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(60, TimeUnit.MINUTES)
        .refreshAfterWrite(60, TimeUnit.SECONDS)
        // Either: Build with a synchronous computation that is wrapped as asynchronous
        .buildAsync {key: String -> runBlocking { discover() }}

    suspend fun discoverCached(): List<Instance>  {
        return cache.get("default").await()
    }

    suspend fun discover(): List<Instance> {
        log.info { "We have ${promregatorConfiguration.targets.size} targets configured" }

        val resolvedTargets = targetResolver.resolveTargets(promregatorConfiguration.targets)
        if (resolvedTargets.isEmpty()) {
            log.warn { "Target resolved was unable to resolve configured targets" }
            return listOf()
        }
        log.debug { "Raw list contains ${resolvedTargets.size} resolved targets" }

        val instanceList = appInstanceScanner.determineInstancesFromTargets(resolvedTargets)
        if (instanceList == null) {
            log.warn { "Instance Scanner unable to determine instances from provided targets" }
            return listOf()
        }
        log.debug { "Raw list contains ${instanceList.size} instances" }

        return instanceList
    }
}
