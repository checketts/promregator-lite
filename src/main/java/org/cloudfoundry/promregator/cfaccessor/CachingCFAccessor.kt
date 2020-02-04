package org.cloudfoundry.promregator.cfaccessor

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.cloudfoundry.client.v2.applications.ApplicationResource
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse
import org.cloudfoundry.client.v2.organizations.OrganizationResource
import org.cloudfoundry.client.v2.spaces.GetSpaceSummaryResponse
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceResource
import org.cloudfoundry.promregator.config.CloudFoundryConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Primary
@Service
@ConditionalOnProperty("cf.api.client.enabled", matchIfMissing = true)
@Suppress("ReactorUnusedPublisher")
class CachingCFAccessor(
        meterRegistry: MeterRegistry,
        private val cfAccessor: CFAccessor,
        private val cf: CloudFoundryConfiguration
        ) : CFAccessor {
    private val retrieveOrgIdCache: LoadingCache<String, Mono<ListOrganizationsResponse>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { key: String->
                val (api, orgName) = key.split("::")
                cfAccessor.retrieveOrgId(api, orgName).cache()
            }, "retrieveOrgIdCache")

    private val retrieveAllOrgIdsCache: LoadingCache<String, Flux<OrganizationResource>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { api: String->
                cfAccessor.retrieveAllOrgIds(api).cache()
            }, "retrieveAllOrgIdsCache")

    private val retrieveSpaceIdCache: LoadingCache<String,  Mono<ListSpacesResponse>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { key: String->
                val (api, orgName, spaceId) = key.split("::")
                cfAccessor.retrieveSpaceId(api, orgName, spaceId).cache()
            }, "retrieveSpaceIdCache")

    private val retrieveSpaceIdsInOrgCache: LoadingCache<String, Flux<SpaceResource>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { key: String->
                val (api, orgName) = key.split("::")
                cfAccessor.retrieveSpaceIdsInOrg(api, orgName).cache()
            }, "retrieveSpaceIdsInOrgCache")

    private val retrieveAllApplicationIdsInSpaceCache: LoadingCache<String, Flux<ApplicationResource>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { key: String->
                val (api, orgName, spaceId) = key.split("::")
                cfAccessor.retrieveAllApplicationIdsInSpace(api, orgName, spaceId).cache()
            }, "retrieveAllApplicationIdsInSpaceCache")

    private val retrieveSpaceSummaryCache: LoadingCache<String, Mono<GetSpaceSummaryResponse>> = CaffeineCacheMetrics.monitor(meterRegistry, Caffeine.newBuilder()
            .expireAfterWrite(cf.request.cacheDuration)
            .recordStats()
            .build { key: String->
                val (api, orgName) = key.split("::")
                cfAccessor.retrieveSpaceSummary(api, orgName).cache()
            }, "retrieveSpaceSummaryCache")

    override fun retrieveOrgId(api: String, orgName: String) = retrieveOrgIdCache["$api::$orgName"]
            ?: Mono.error { RuntimeException("Error retrieving api=$api orgName=$orgName")}

    override fun retrieveAllOrgIds(api: String) = retrieveAllOrgIdsCache[api]
            ?: Flux.error { RuntimeException("Error retrieving api=$api")}

    override fun retrieveSpaceId(api: String, orgId: String, spaceName: String) = retrieveSpaceIdCache["$api::$orgId::$spaceName"]
            ?: Mono.error { RuntimeException("Error retrieving api=$api orgName=$orgId spaceName=$spaceName")}

    override fun retrieveSpaceIdsInOrg(api: String, orgId: String) = retrieveSpaceIdsInOrgCache["$api::$orgId"]
            ?: Flux.error { RuntimeException("Error retrieving api=$api orgId=$orgId")}

    override fun retrieveAllApplicationIdsInSpace(api: String, orgId: String, spaceId: String) = retrieveAllApplicationIdsInSpaceCache["$api::$orgId::$spaceId"]
            ?: Flux.error { RuntimeException("Error retrieving api=$api orgId=$orgId spaceId=$spaceId")}

    override fun retrieveSpaceSummary(api: String, spaceId: String) = retrieveSpaceSummaryCache["$api::$spaceId"]
            ?: Mono.error { RuntimeException("Error retrieving api=$api spaceId=$spaceId")}

}