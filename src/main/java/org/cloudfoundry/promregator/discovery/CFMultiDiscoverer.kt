package org.cloudfoundry.promregator.discovery

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import mu.KotlinLogging
import org.cloudfoundry.promregator.config.PromregatorConfiguration
import org.cloudfoundry.promregator.scanner.Instance
import org.cloudfoundry.promregator.scanner.AppInstanceScanner
import org.cloudfoundry.promregator.scanner.ReactiveTargetResolver
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}
@Service
class CFMultiDiscoverer(
        private val targetResolver: ReactiveTargetResolver,
        private val appInstanceScanner: AppInstanceScanner,
        private val promregatorConfiguration: PromregatorConfiguration,
        meterRegistry: MeterRegistry
) {
    private val discoveryCache: LoadingCache<String, Mono<Map<Int,Instance>>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
//            .expireAfterWrite(discoveryCacheDuration)
            .recordStats()
            .build { _: String->
                logger.debug { "We have ${promregatorConfiguration.targets.size} targets configured" }

                targetResolver.resolveTargets(promregatorConfiguration.targets)
                        .doOnNext { logger.debug { "Raw list contains ${it.size} resolved targets" } }
                        .flatMap { resolvedTargets -> appInstanceScanner.determineInstancesFromTargets(resolvedTargets) }
                        .doOnNext { logger.debug {"Raw list contains ${it.size} instances"} }
                        .map { instances ->
                            instances.map { it.hashCode() to it }.toMap()
                }.cache()
            }, "discoveryCache")

    /**
     * performs the discovery based on the configured set of targets in the configuration, (pre-)filtering the returned set applying the filter criteria supplied.
     * The instances discovered are automatically registered at this Discoverer
     * @param applicationIdFilter the (pre-)filter based on ApplicationIds, allowing to early filter the list of instances to discover
     * @param instanceFilter the (pre-)filter based on the Instance instance, allowing to filter the lost if instances to discover
     * @return the list of Instances which were discovered (and registered).
     */
    fun discover(): Mono<Map<Int,Instance>> = discoveryCache["all"] ?: Mono.error { RuntimeException("Error loading cache") }






}
