package org.cloudfoundry.promregator.scanner

import mu.KotlinLogging
import org.cloudfoundry.client.v2.applications.ApplicationResource
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse
import org.cloudfoundry.client.v2.organizations.OrganizationResource
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceResource
import org.cloudfoundry.promregator.cfaccessor.CFAccessor
import org.cloudfoundry.promregator.config.Target
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

private val logger = KotlinLogging.logger { }

@Service
class ReactiveTargetResolver(
        private val cfAccessor: CFAccessor
) {

    private data class CFApiMemoizer(
            val retrieveOrgId: ConcurrentHashMap<String, Mono<OrganizationResource>> = ConcurrentHashMap(),
            val retrieveSpaceIdsInOrg: ConcurrentHashMap<String, Mono<ListSpacesResponse>> = ConcurrentHashMap(),
            val retrieveAllApplicationIdsInSpace: ConcurrentHashMap<String, Mono<ListApplicationsResponse>> = ConcurrentHashMap()
    ) {
    }

    private data class IntermediateTarget(
            var configTarget: Target,
            var resolvedOrgName: String? = null,
            var resolvedOrgId: String? = null,
            var resolvedSpaceName: String? = null,
            var resolvedSpaceId: String? = null,
            var resolvedApplicationName: String? = null,
            var resolvedApplicationId: String? = null
    ) {
        fun toResolvedTarget() = ResolvedTarget(
                originalTarget = configTarget,
                orgName = resolvedOrgName
                        ?: throw RuntimeException("Target '$this' created a ResolvedTarget without resolved orgName"),
                spaceName = resolvedSpaceName
                        ?: throw RuntimeException("Target '$this' created a ResolvedTarget without resolved spaceName"),
                applicationName = resolvedApplicationName
                        ?: throw RuntimeException("Target '$this' created a ResolvedTarget without resolved applicationName"),
                protocol = configTarget.protocol,
                path = configTarget.path,
                applicationId = resolvedApplicationId
        )
    }

    fun resolveTargets(configTargets: List<Target>): Mono<List<ResolvedTarget>> {
        val memoizer = CFApiMemoizer()

        return Flux.fromIterable(configTargets)
                .map { IntermediateTarget(it) }
                .flatMap { resolveOrg(memoizer, it) }
                .log(logger.name + ".resolveOrg")
                .flatMap { resolveSpace(memoizer, it) }
                .log(logger.name + ".resolveSpace")
                .flatMap { resolveApplication(memoizer, it) }
                .log(logger.name + ".resolveApplication")
                .map { it.toResolvedTarget() }
                .distinct().collectList()
                .doOnNext { "Successfully resolved ${configTargets.size} configuration targets to ${it.size} resolved targets" }
    }

    private fun resolveOrg(memoizer: CFApiMemoizer, intTarget: IntermediateTarget): Flux<IntermediateTarget> { /* NB: Now we have to consider three cases:
		 * Case 1: both orgName and orgRegex is empty => select all orgs
		 * Case 2: orgName is null, but orgRegex is filled => filter all orgs with the regex
		 * Case 3: orgName is filled, but orgRegex is null => select a single org
		 * In cases 1 and 2, we need the list of all orgs on the platform.
		 */
        if (intTarget.configTarget.orgRegex == null && intTarget.configTarget.orgName != null) { // Case 3: we have the orgName, but we also need its id
            val itMono = memoizer.retrieveOrgId.computeIfAbsent(intTarget.configTarget.orgName!!) {
                cfAccessor.retrieveOrgId(intTarget.configTarget.orgName!!)
                        .doOnNext { logger.info { "retrieveOrgId:${intTarget.configTarget.orgName} : $it"} }
                        .map { it.resources }
                        .flatMap { resList: List<OrganizationResource>? ->
                            if (resList == null || resList.isEmpty()) {
                                Mono.empty()
                            } else {
                                Mono.just(resList[0])
                            }
                        }
                        .doOnError { e: Throwable? -> logger.warn(e) { "Error on retrieving org id for org '${intTarget.configTarget.orgName}'" } }
                        .onErrorResume { _: Throwable? -> Mono.empty() }
                        .cache()
            }

            return itMono.map { res: OrganizationResource ->
                intTarget.resolvedOrgName = res.entity.name
                intTarget.resolvedOrgId = res.metadata.id
                intTarget
            }.flux()
        }
        // Case 1 & 2: Get all orgs from the platform
        val responseMono = cfAccessor.retrieveAllOrgIds()
        var orgResFlux = responseMono.map { obj: ListOrganizationsResponse -> obj.resources }
                .flatMapMany { Flux.fromIterable(it!!) }
        if (intTarget.configTarget.orgRegex != null) { // Case 2
            val filterPattern = Pattern.compile(intTarget.configTarget.orgRegex!!, Pattern.CASE_INSENSITIVE)
            orgResFlux = orgResFlux.filter { orgRes: OrganizationResource ->
                val m = filterPattern.matcher(orgRes.entity.name)
                m.matches()
            }
        }
        return orgResFlux.map { orgRes: OrganizationResource ->
            intTarget.copy(
                resolvedOrgId = orgRes.metadata.id,
                resolvedOrgName = orgRes.entity.name
            )
        }
    }

    private fun resolveSpace(memoizer: CFApiMemoizer, intTarget: IntermediateTarget): Flux<IntermediateTarget> { /* NB: Now we have to consider three cases:
		 * Case 1: both spaceName and spaceRegex is empty => select all spaces (within the org)
		 * Case 2: spaceName is null, but spaceRegex is filled => filter all spaces with the regex
		 * Case 3: spaceName is filled, but spaceRegex is null => select a single space
		 * In cases 1 and 2, we need the list of all spaces in the org.
		 */
        if (intTarget.configTarget.spaceRegex == null && intTarget.configTarget.spaceName != null) { // Case 3: we have the spaceName, but we also need its id
            val itMono = cfAccessor.retrieveSpaceId(intTarget.resolvedOrgId!!, intTarget.configTarget.spaceName!!)
                    .map { obj: ListSpacesResponse -> obj.resources }
                    .flatMap { resList: List<SpaceResource>? ->
                        if (resList == null || resList.isEmpty()) {
                            return@flatMap Mono.empty<SpaceResource>()
                        }
                        Mono.just(resList[0])
                    }
                    .map { res: SpaceResource ->
                        intTarget.resolvedSpaceName = res.entity.name
                        intTarget.resolvedSpaceId = res.metadata.id
                        intTarget
                    }.doOnError { e: Throwable -> logger.warn(e) { "Error on retrieving space id for org '${intTarget.resolvedOrgName}' and space '${intTarget.configTarget!!.spaceName}'" } }
                    .onErrorResume { _: Throwable? -> Mono.empty() }
            return itMono.flux()
        }
        // Case 1 & 2: Get all spaces in the current org
        val responseMono = memoizer.retrieveSpaceIdsInOrg.computeIfAbsent(intTarget.resolvedOrgId!!) {
            cfAccessor.retrieveSpaceIdsInOrg(intTarget.resolvedOrgId!!)
                    .doOnNext { logger.info { "retrieveSpaceIdsInOrg:${intTarget.resolvedOrgId!!}" } }
                    .cache()
        }
        var spaceResFlux = responseMono.map { obj: ListSpacesResponse -> obj.resources }
                .flatMapMany { it: List<SpaceResource>? -> Flux.fromIterable(it!!) }
        if (intTarget.configTarget.spaceRegex != null) { // Case 2
            val filterPattern = Pattern.compile(intTarget.configTarget.spaceRegex!!, Pattern.CASE_INSENSITIVE)
            spaceResFlux = spaceResFlux.filter { spaceRes: SpaceResource ->
                val m = filterPattern.matcher(spaceRes.entity.name)
                m.matches()
            }
        }
        return spaceResFlux.map { spaceRes: SpaceResource ->
            intTarget.copy(
                    resolvedSpaceId = spaceRes.metadata.id,
                    resolvedSpaceName = spaceRes.entity.name
            )
        }
    }

    private fun resolveApplication(memoizer: CFApiMemoizer, intTarget: IntermediateTarget): Flux<IntermediateTarget> { /* NB: Now we have to consider three cases:
		 * Case 1: both applicationName and applicationRegex is empty => select all applications (in the space)
		 * Case 2: applicationName is null, but applicationRegex is filled => filter all applications with the regex
		 * Case 3: applicationName is filled, but applicationRegex is null => select a single application
		 * In cases 1 and 2, we need the list of all applications in the space.
		 */
        if (intTarget.configTarget.applicationRegex == null && intTarget.configTarget.applicationName != null) { // Case 3: we have the applicationName, but we also need its id
            val appNameToSearchFor = intTarget.configTarget.applicationName!!.toLowerCase(Locale.ENGLISH)
            val itMono = cfAccessor.retrieveAllApplicationIdsInSpace(intTarget.resolvedOrgId!!, intTarget.resolvedSpaceId!!)
                    .map { obj: ListApplicationsResponse -> obj.resources }
                    .flatMapMany { Flux.fromIterable(it!!) }
                    .filter { appResource: ApplicationResource -> appNameToSearchFor == appResource.entity.name.toLowerCase(Locale.ENGLISH) }
                    .single()
                    .doOnError { e: Throwable? ->
                        if (e is NoSuchElementException) {
                            logger.warn { "Application id could not be found for org '${intTarget.resolvedOrgName}', space '${intTarget.resolvedSpaceName}' and application '${intTarget.configTarget.applicationName}'. Check your configuration; skipping it for now" }
                        }
                    }
                    .onErrorResume { e: Throwable? -> Mono.empty() }
                    .filter { res: ApplicationResource -> isApplicationInScrapableState(res.entity.state) }
                    .map { res: ApplicationResource ->
                        intTarget.resolvedApplicationName = res.entity.name
                        intTarget.resolvedApplicationId = res.metadata.id
                        intTarget
                    }.doOnError { e: Throwable? -> logger.warn(e) { "Error on retrieving application id for org '${intTarget.resolvedOrgName}', space '${intTarget.resolvedSpaceName}' and application '${intTarget.configTarget.applicationName}'" } }
                    .onErrorResume { _: Throwable? -> Mono.empty() }
            return itMono.flux()
        }
        // Case 1 & 2: Get all applications in the current space
        val responseMono = memoizer.retrieveAllApplicationIdsInSpace.computeIfAbsent("${intTarget.resolvedOrgId!!}:${intTarget.resolvedSpaceId!!}") {
            cfAccessor.retrieveAllApplicationIdsInSpace(intTarget.resolvedOrgId!!, intTarget.resolvedSpaceId!!)
                    .doOnNext { logger.info { "retrieveAllApplicationIdsInSpace:${intTarget.resolvedOrgId!!}:${intTarget.resolvedSpaceId!!}" } }
                    .cache()
        }
            var appResFlux = responseMono.map { obj: ListApplicationsResponse -> obj.resources }
                    .flatMapMany { Flux.fromIterable(it!!) }
                    .doOnError { e: Throwable? -> logger.warn(e) { "Error on retrieving list of applications in org '${intTarget.resolvedOrgName}' and space '${intTarget.resolvedSpaceName}'" } }
                    .onErrorResume { _: Throwable? -> Flux.empty() }

        if (intTarget.configTarget.applicationRegex != null) { // Case 2
            val filterPattern = Pattern.compile(intTarget.configTarget.applicationRegex!!, Pattern.CASE_INSENSITIVE)
            appResFlux = appResFlux.filter { appRes: ApplicationResource ->
                val m = filterPattern.matcher(appRes.entity.name)
                m.matches()
            }
        }
        val scrapableFlux = appResFlux.filter { appRes: ApplicationResource -> isApplicationInScrapableState(appRes.entity.state) }
        return scrapableFlux.map { appRes: ApplicationResource ->
            intTarget.copy(
                    resolvedApplicationId = appRes.metadata.id,
                    resolvedApplicationName = appRes.entity.name
            )
        }
    }

    private fun isApplicationInScrapableState(state: String): Boolean {
        return "STARTED" == state
        /* TODO: To be enhanced, once we know of further states, which are
		 * also scrapeable.
		 */
    }

}