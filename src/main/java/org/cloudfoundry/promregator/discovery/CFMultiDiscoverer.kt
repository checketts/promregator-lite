package org.cloudfoundry.promregator.discovery

import mu.KotlinLogging
import java.util.function.Predicate
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
        private val promregatorConfiguration: PromregatorConfiguration
) {

    /**
     * performs the discovery based on the configured set of targets in the configuration, (pre-)filtering the returned set applying the filter criteria supplied.
     * The instances discovered are automatically registered at this Discoverer
     * @param applicationIdFilter the (pre-)filter based on ApplicationIds, allowing to early filter the list of instances to discover
     * @param instanceFilter the (pre-)filter based on the Instance instance, allowing to filter the lost if instances to discover
     * @return the list of Instances which were discovered (and registered).
     */
    fun discover(applicationIdFilter: Predicate<in String?>?,instanceFilter: Predicate<in Instance?>?): Mono<List<Instance>> {
        logger.debug { "We have ${promregatorConfiguration.targets.size} targets configured" }

        return targetResolver.resolveTargets(promregatorConfiguration.targets)
                .doOnNext { logger.debug { "Raw list contains ${it.size} resolved targets" } }
                .flatMap { resolvedTargets -> appInstanceScanner.determineInstancesFromTargets(resolvedTargets, applicationIdFilter, instanceFilter) }
                .doOnNext { logger.debug {"Raw list contains ${it.size} instances"} }
    }
}