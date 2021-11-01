package com.github.promregator.discovery

import com.github.promregator.cfaccessor.SimpleCFAccessorImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.cloudfoundry.client.v2.routes.Route
import com.github.promregator.config.CloudFoundryConfig
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.github.promregator.config.Target


data class OrgResponse(
        val api: String,
        val id: String,
        val name: String
)

data class SpaceResponse(
        val org: OrgResponse,
        val spaceName: String,
        val spaceId: String,
        val domains: List<DomainResponse>,
)

data class SpaceSummaryAppResponse(
        val space: SpaceResponse,
        val appName: String,
        val appSummary: AppSummary
)

data class AppSummary(
        val instances: Int,
        val urls: List<String>,
        val routes: List<Route>,
)

data class DomainResponse(
        val orgId: String,
        val domainId: String,
        val isInternal: Boolean,
)

data class AppResponse(
        val appId: String,
        val appName: String,
        val space: SpaceResponse,
        val isScrappable: Boolean,
        val annotationMetricsPath: String? = null,
        val originalTarget: Target? = null,
        val spaceAppSummary: AppSummary? = null,
) {
    val protocol: String
        get() = originalTarget?.protocol ?: "https"
    val metricsPath: String
        get() = annotationMetricsPath ?: originalTarget?.path ?: "/metrics"
}

@Component
class SimpleTargetResolver(
        private val cfAccessor: SimpleCFAccessorImpl,
        private val cf: CloudFoundryConfig
) {
    val supportsV3 = AtomicBoolean(true)


    suspend fun resolveTargets(configTargets: List<Target>): List<AppResponse> = coroutineScope {
        val orgs = cf.api.keys.map { api ->
            async { cfAccessor.retrieveAllOrgIds(api).map {
                OrgResponse(api, it.metadata.id, it.entity.name)
            } }
        }.awaitAll().flatten()

        val domains = orgs.map { org ->
            async {
                cfAccessor.retrieveAllDomains(org.api, org.id)?.resources?.map { domainResource ->
                    DomainResponse(org.id, domainResource.metadata.id, domainResource.entity.internal ?: false)
                } ?: listOf()
            }
        }.awaitAll().flatten()

        val spaces = orgs.map { org ->
             async { cfAccessor.retrieveSpaceIdsInOrg(org.api, org.id).map { space ->
                 SpaceResponse(org, space.entity.name, space.metadata.id, domains.filter { it.orgId == org.id })
             } }
        }.awaitAll().flatten()

        val spaceSummaries = spaces.map { space ->
             async {
                 cfAccessor.retrieveSpaceSummary(space.org.api, space.spaceId)?.applications?.map { app ->
                     val appName = app.name.lowercase(LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON)
                    SpaceSummaryAppResponse(space, appName, AppSummary(app.instances, app.urls, app.routes))
                 } ?: listOf()
             }
        }.awaitAll().flatten()

        var apps = if(supportsV3.get()) {
            spaces.map { space ->
                async {
                    val appsv3 = cfAccessor.retrieveAllApplicationsInSpaceV3(space.org.api, space.org.id, space.spaceId)
                    appsv3.map { appRes ->
                        val scrapable = isApplicationInScrapableState(appRes.state.toString())
                        val annotationScrape = appRes.metadata.annotations.getOrDefault(PROMETHEUS_IO_SCRAPE, "false") == "true"
                        val annotationMetricsPath = appRes.metadata.annotations[PROMETHEUS_IO_PATH]

                        /*
                         * Due to https://github.com/cloudfoundry/cloud_controller_ng/issues/1523, we
                         * cannot rely on sas.getId() (i.e. it may contain wrong information) So we use the name
                         */
                        val appNameLower = appRes.name.lowercase(LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON)
                        AppResponse(appRes.id, appRes.name, space, scrapable && annotationScrape, annotationMetricsPath,
                                spaceAppSummary = spaceSummaries.firstOrNull { it.appName == appNameLower}?.appSummary)
                    }

                }
            }.awaitAll().flatten()
        } else null

        if(apps == null || !supportsV3.get()) {
            apps = spaces.map { space ->
                async {
                    cfAccessor.retrieveAllApplicationIdsInSpace(space.org.api, space.org.id, space.spaceId).map { appRes ->
                        val scrapable = isApplicationInScrapableState(appRes.entity.state.toString())
                        AppResponse(appRes.metadata.id, appRes.entity.name, space, scrapable, "/metrics")
                    }
                }
            }.awaitAll().flatten()
        }

        val orgMatches = apps.filter { app -> configTargets.any{ target ->
            (target.orgName == null && target.orgRegex == null) ||
            (target.orgName == null && target.orgRegex?.toRegex()?.matches(app.space.org.name) == true) ||
                    (target.orgRegex == null && target.orgName == app.space.org.name)
        } }

        val spaceMatches = orgMatches.filter { app -> configTargets.any{ target ->
            (target.spaceName == null && target.spaceRegex == null) ||
            (target.spaceName == null && target.spaceRegex?.toRegex()?.matches(app.space.spaceName) == true ) ||
                    (target.spaceName == app.space.spaceName && target.spaceRegex == null)
        } }

        val filteredApps = spaceMatches.map { app ->
            val target = configTargets.firstOrNull { t ->
                (t.applicationName == null && t.applicationRegex == null) ||
                        (t.applicationName == null && t.applicationRegex?.toRegex()?.matches(app.appName) == true) ||
                        (t.applicationName == app.appName && t.applicationRegex == null)
            }
            app.copy(originalTarget= target)
        }.filter { it.originalTarget != null }

        filteredApps
    }

    private fun isApplicationInScrapableState(state: String): Boolean {
        return "STARTED" == state
    }

    companion object {
        const val PROMETHEUS_IO_SCRAPE = "prometheus.io/scrape"
        const val PROMETHEUS_IO_PATH = "prometheus.io/path"
        val LOCALE_OF_LOWER_CASE_CONVERSION_FOR_IDENTIFIER_COMPARISON = Locale.ENGLISH
    }
}
