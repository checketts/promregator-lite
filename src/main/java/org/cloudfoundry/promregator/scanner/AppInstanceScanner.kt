package org.cloudfoundry.promregator.scanner

import mu.KotlinLogging
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse
import org.cloudfoundry.client.v2.spaces.GetSpaceSummaryResponse
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceApplicationSummary
import org.cloudfoundry.promregator.cfaccessor.CFAccessor
import org.springframework.stereotype.Service
import org.springframework.util.CollectionUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.RuntimeException
import java.util.*
import java.util.function.Predicate
import java.util.regex.Pattern

private val logger = KotlinLogging.logger {}

@Service
class AppInstanceScanner(
        private val cfAccessor: CFAccessor
) {
    /*
	 * see also https://github.com/promregator/promregator/issues/76
	 * This is the locale, which we use to convert both "what we get from CF" and "the stuff, which we get
	 * from the configuration" into lower case before we try to match them.
	 *
	 * Note that this might be wrong, if someone might have an app(/org/space) name in Turkish and expects a
	 * Turkish case conversion.
	 */
    private val LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON = Locale.ENGLISH

    /**
     * OSA stands for Org-Space-Application
     */
    data class OSAVector(var target: ResolvedTarget,
                         var applicationId: String = target.applicationId ?: throw RuntimeException("ApplicationID missing based on target $target"),
                         var orgId: String? = null,
                         var spaceId: String? = null,
                         var accessURL: String? = null,
                         var numberOfInstances: Int = 0)

    fun determineInstancesFromTargets(targets: List<ResolvedTarget>?, 
                                      applicationIdFilter: Predicate<in String?>?, 
                                      instanceFilter: Predicate<in Instance?>?): Mono<List<Instance>> {
        val instancesFlux = Flux.fromIterable(targets!!) //Create the holder
                .map { target: ResolvedTarget -> OSAVector(target) } //Look up org info
                .flatMap { v: OSAVector ->
                    getOrgId(v.target.orgName).map { orgId: String? ->
                        v.orgId = orgId
                        v
                    }
                } //Look up space
                .flatMap { v: OSAVector ->
                    val orgId = v.orgId ?: return@flatMap Mono.empty<OSAVector>()
                    getSpaceId(orgId, v.target.spaceName).map { spaceId: String? ->
                        v.spaceId = spaceId
                        v
                    }
                } //Look up applicationId, accessUrl, and instance count
                .flatMap { v: OSAVector ->
                    val spaceId = v.spaceId ?: return@flatMap Mono.empty<OSAVector>()
                    getSpaceSummary(spaceId).flatMap { spaceSummaryMap: Map<String?, SpaceApplicationSummary?> ->
                        val sas = spaceSummaryMap[v.target.applicationName.toLowerCase(LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON)] 
                                ?: return@flatMap Mono.empty<OSAVector>()
                        
                        val urls = sas.urls
                        if (urls != null && urls.isNotEmpty()) {
                            v.accessURL = determineAccessURL(v.target.protocol, urls, v.target.originalTarget.preferredRouteRegexPatterns, v.target.path)
                        }
                        v.numberOfInstances = sas.instances
                        Mono.just(v)
                    }
                } // perform pre-filtering, if available
                .filter { v: OSAVector -> applicationIdFilter == null || applicationIdFilter.test(v.applicationId) } //convert to instances
                .flatMap { v: OSAVector ->
                    val instances: MutableList<Instance> = ArrayList(v.numberOfInstances)
                    for (i in 0 until v.numberOfInstances) {
                        val inst = Instance(v.target, "${v.applicationId}:$i", v.accessURL)
                        instances.add(inst)
                    }
                    Flux.fromIterable(instances)
                } // perform pre-filtering, if available
                .filter { instance: Instance? -> instanceFilter == null || instanceFilter.test(instance) }
        return instancesFlux.collectList()
    }

    private fun getOrgId(orgNameString: String): Mono<String> {
        return cfAccessor.retrieveOrgId(orgNameString).flatMap { response: ListOrganizationsResponse ->
            val resources = response.resources ?: return@flatMap Mono.empty<String>()
            if (resources.isEmpty()) {
                logger.warn {"Received empty result on requesting org $orgNameString" }
                return@flatMap Mono.empty<String>()
            }
            Mono.just(resources[0].metadata.id)
        }.onErrorResume { e: Throwable? ->
            logger.error(e) {"retrieving Org Id for org Name '$orgNameString' resulted in an exception"}
            Mono.empty()
        }.cache()
    }

    private fun getSpaceId(orgIdString: String, spaceNameString: String): Mono<String> {
        val listSpacesResponse = cfAccessor.retrieveSpaceId(orgIdString, spaceNameString)
        return listSpacesResponse.flatMap { response: ListSpacesResponse ->
            val resources = response.resources ?: return@flatMap Mono.empty<String>()
            if (resources.isEmpty()) {
                logger.warn {"Received empty result on requesting space $spaceNameString"}
                return@flatMap Mono.empty<String>()
            }
            val spaceResource = resources[0]
            Mono.just(spaceResource.metadata.id)
        }.onErrorResume { e: Throwable? ->
            logger.error {"retrieving space id for org id '$orgIdString' and space name '$spaceNameString' resulted in an exception"}
            Mono.empty()
        }.cache()
    }

    private fun getSpaceSummary(spaceIdString: String): Mono<Map<String?, SpaceApplicationSummary?>> {
        return cfAccessor.retrieveSpaceSummary(spaceIdString)
                .flatMap<Map<String?, SpaceApplicationSummary?>> { response: GetSpaceSummaryResponse ->
                    val applications = response.applications
                            ?: return@flatMap Mono.empty()
                    val map: MutableMap<String?, SpaceApplicationSummary?> = HashMap(applications.size)
                    for (sas in applications) {
                        map[sas.name.toLowerCase(LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON)] = sas
                    }
                    Mono.just<Map<String?, SpaceApplicationSummary?>>(map)
                }.onErrorResume { e: Throwable? ->
                    logger.error(e) {"retrieving summary for space id '$spaceIdString' resulted in an exception" }
                    Mono.empty()
                }
    }

    private fun determineAccessURL(protocol: String, urls: List<String>, preferredRouteRegex: List<Pattern?>, path: String): String {
        val url = determineApplicationRoute(urls, preferredRouteRegex)
        val applicationUrl = "$protocol://$url"
        logger.debug {"Using Application URL: '$applicationUrl'" }
        var applUrl = applicationUrl
        if (!applicationUrl.endsWith("/")) {
            applUrl += '/'
        }
        var internalPath = path
        while (internalPath.startsWith("/")) {
            internalPath = internalPath.substring(1)
        }
        return applUrl + internalPath
    }

    private fun determineApplicationRoute(urls: List<String>?, patterns: List<Pattern?>): String? {
        if (urls == null || urls.isEmpty()) {
            logger.debug("No URLs provided to determine ApplicationURL with")
            return null
        }
        if (CollectionUtils.isEmpty(patterns)) {
            logger.debug("No Preferred Route URL (Regex) provided; taking first Application Route in the list provided")
            return urls[0]
        }
        for (pattern in patterns) {
            for (url in urls) {
                logger.debug {"Attempting to match Application Route '$url' against pattern '$pattern'"}
                val m = pattern!!.matcher(url)
                if (m.matches()) {
                    logger.debug {"Match found, using Application Route '$url'"}
                    return url
                }
            }
        }
        // if we reach this here, then we did not find any match in the regex.
        // The fallback then is the old behavior by returned just the first-guess element
        logger.debug {"Though Preferred Router URLs were provided, no route matched; taking the first route as fallback (compatibility!), which is '${urls[0]}'"}
        return urls[0]
    }

}