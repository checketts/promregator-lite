package org.cloudfoundry.promregator.discovery

import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.sun.management.HotSpotDiagnosticMXBean
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import io.netty.util.internal.PlatformDependent
import mu.KotlinLogging
import org.cloudfoundry.promregator.config.ScrapeTarget
import org.cloudfoundry.promregator.scanner.Instance
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.io.IOException
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


private var logger = KotlinLogging.logger {}

@RestController
class DiscoveryController(
        private val discoverer: CFMultiDiscoverer,
        @Value("\${promregator.discovery.hostname:#{null}}") private val myHostname: String? = null,
        @Value("\${promregator.discovery.port:#{null}}") private val myPort: Int? = null,
        @Value("\${promregator.discovery.cache.duration:300s}") private val discoveryCacheDuration: Duration,
        val mapper: ObjectMapper,
        private val meterRegistry: MeterRegistry
) {
    private val directMemoryGauge = meterRegistry.gauge("promregator.netty.memory.direct.used", this, { PlatformDependent.usedDirectMemory().toDouble()})
    private final val latestTargetCount = AtomicInteger(0)
    private val discoveryResponse: LoadingCache<String, Mono<List<DiscoveryResponse>>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(discoveryCacheDuration)
            .recordStats()
            .build { _: String->
        val localHostname: String = this.myHostname ?: ""// request.localName
        val localPort: Int = this.myPort ?: 0 // request.localPort
        val targets = listOf("$localHostname:$localPort")

        logger.info { "Using scraping target $targets in discovery response" }

        val instancesMono: Mono<List<Instance>> = this.discoverer.discover(null, null)

        instancesMono.map {instances ->
            if (instances.isEmpty()) {
                throw RuntimeException("No targets configured")
            }

            latestTargetCount.set(instances.size)
            logger.info { "Returning discovery document with ${instances.size} targets" }

            instances.map {
                val scrapeTarget = ScrapeTarget(
                        applicationId = it.applicationId,
                        applicationName = it.target.applicationName,
                        scrapeUrl = it.accessUrl ?: "",
                        instanceNumber = it.instanceNumber,
                        authId = it.target.originalTarget.authenticatorId
                )
                val scrapeJson = Base64.getEncoder().encodeToString(mapper.writeValueAsString(scrapeTarget).toByteArray())
                if (scrapeJson.length > 2000) {
                    logger.warn { "The singleTargetScraping url for ${it.target.applicationName} (${it.target.applicationId}) it greater than 2000 characters and might not be scrapable" }
                }
                DiscoveryResponse(targets,
                        DiscoveryLabel(
                                targetPath = "/v2/singleTargetScraping/$scrapeJson",
                                orgName = it.target.orgName,
                                spaceName = it.target.spaceName,
                                applicationName = it.target.applicationName,
                                applicationId = it.applicationId,
                                instanceNumber = it.instanceNumber,
                                instanceId = it.instanceId))
            }

        }.cache()
    }, "discoveryResponse")

    init {
        meterRegistry.gauge("promregator.discovery.targets", latestTargetCount) {it.toDouble()}
    }

    @GetMapping("/dumpHeap")
    fun dumpHeap(@RequestParam filePath: String = "heap.hprof",  @RequestParam live: Boolean = true) {
        val server = ManagementFactory.getPlatformMBeanServer()
        val mxBean = ManagementFactory.newPlatformMXBeanProxy(
                server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean::class.java)
        mxBean.dumpHeap(filePath, live)
    }

    @GetMapping("/v2/discovery","/discovery")
    fun discoverTaragets()= discoveryResponse.get("all")

    data class DiscoveryLabel(
            @JsonProperty("__meta_promregator_target_path") val targetPath: String,
            @JsonProperty("__meta_promregator_target_orgName") val orgName: String,
            @JsonProperty("__meta_promregator_target_spaceName") val spaceName: String,
            @JsonProperty("__meta_promregator_target_applicationName") val applicationName: String,
            @JsonProperty("__meta_promregator_target_applicationId") val applicationId: String,
            @JsonProperty("__meta_promregator_target_instanceNumber") val instanceNumber: String,
            @JsonProperty("__meta_promregator_target_instanceId") val instanceId: String) {

        @JsonGetter("__metrics_path__")
        fun getMetricsPath() = this.targetPath
    }

    data class DiscoveryResponse(val targets: List<String>, val labels: DiscoveryLabel)
}