package com.github.promregator.cfaccessor

//import org.apache.http.conn.util.InetAddressUtils
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.promregator.cfaccessor.RequestType.*
import com.github.promregator.config.ApiConfig
import com.github.promregator.config.CloudFoundryConfig
import com.github.promregator.config.ConfigurationException
import com.github.promregator.config.PromregatorConfiguration
import com.github.promregator.discovery.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.cloudfoundry.client.v2.OrderDirection
import org.cloudfoundry.client.v2.info.GetInfoRequest
import org.cloudfoundry.client.v2.spaces.*
import org.cloudfoundry.client.v3.Resource
import org.cloudfoundry.reactor.ConnectionContext
import org.cloudfoundry.reactor.DefaultConnectionContext
import org.cloudfoundry.reactor.ProxyConfiguration
import org.cloudfoundry.reactor.TokenProvider
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import org.cloudfoundry.client.v2.PaginatedRequest as PaginatedRequestV2
import org.cloudfoundry.client.v2.Resource as ResourceV2
import org.cloudfoundry.client.v2.applications.ApplicationResource as ApplicationResourceV2
import org.cloudfoundry.client.v2.applications.ListApplicationsRequest as ListApplicationsRequestV2
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse as ListApplicationsResponseV2
import org.cloudfoundry.client.v2.organizations.ListOrganizationsRequest as ListOrganizationsRequestV2
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse as ListOrganizationsResponseV2
import org.cloudfoundry.client.v2.organizations.OrganizationResource as OrganizationResourceV2
import org.cloudfoundry.client.v2.spaces.ListSpacesRequest as ListSpacesRequestV2
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse as ListSpacesResponseV2
import org.cloudfoundry.client.v2.spaces.SpaceResource as SpaceResourceV2
import org.cloudfoundry.client.v3.PaginatedRequest as V3PaginatedRequest
import org.cloudfoundry.client.v3.applications.ApplicationResource as ApplicationResourceV3
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest as ListApplicationsRequestV3
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse as ListApplicationsResponseV3

private val log = mu.KotlinLogging.logger {  }

@Component
class SimpleCFAccessorImpl(
        private val cf: CloudFoundryConfig,
        private val promregatorConfiguration: PromregatorConfiguration,
        private val meterRegistry: MeterRegistry,
        ) {
    private val rateLimiter = RateLimiter(600, meterRegistry)
    private var isV3Enabled = false

    private val cloudFoundryClients = ConcurrentHashMap<String, ReactorCloudFoundryClient>()

    init {
        meterRegistry.gauge("promregator.cf.ratelimit.allowed", rateLimiter) { it.availableTokens.toDouble() }
        meterRegistry.gauge("promregator.cf.ratelimit.wait_seconds", rateLimiter) { (it.totalWaitTimeMs / 1000.0) }
        meterRegistry.gauge("promregator.cf.ratelimit.queue", rateLimiter) { it.waitQueue.toDouble() }
        createClients()
    }

    private suspend fun <T> rateLimitAndRecord(requestType: RequestType, block: suspend () -> T): T? {
        return rateLimiter.whenTokenAvailable {
            val timer = Timer.start()
            val result = try {
                 block()
            } catch (e: Exception) {
                log.error(e) { "Error making request type: $requestType"  }
                null
            }
            timer.stop(meterRegistry.timer("promregator.cffetch.single", "type", requestType.metricName))
            result
        }
    }

    private fun createClients() {
        cf.api.forEach { api -> reset(api.key) }
        if (promregatorConfiguration.internal.preCheckAPIVersion) {
            val request = GetInfoRequest.builder().build()
            cloudFoundryClients.forEach { (api: String, client: ReactorCloudFoundryClient) ->
                val getInfo = client.info()[request].block()
                        ?: throw RuntimeException("Error connecting to CF api '$api'")
                // NB: This also ensures that the connection has been established properly...
                log.info {"Target CF platform ($api) is running on API version ${getInfo.apiVersion}"}

                // Ensures v3 API exists. The CF Java Client does not yet implement the info endpoint for V3, so we do it manually.
                val v3Info = ReactorInfoV3(client.connectionContext, client.rootV3,
                        client.tokenProvider, client.requestTags)
                        .get().onErrorReturn(JsonNodeFactory.instance.nullNode()).block()
                if (v3Info == null || v3Info.isNull) {
                    log.warn("Unable to get v3 info endpoint of CF platform, some features will not work as expected")
                    isV3Enabled = false
                } else {
                    isV3Enabled = true
                }
            }
        }
    }

    private fun connectionContext(apiConfig: ApiConfig, proxyConfiguration: ProxyConfiguration?): DefaultConnectionContext {
        if (PATTERN_HTTP_BASED_PROTOCOL_PREFIX.matcher(apiConfig.host).find()) {
            throw ConfigurationException("cf.api_host configuration parameter must not contain an http(s)://-like prefix; specify the hostname only instead")
        }
        var connctx = DefaultConnectionContext.builder()
                .apiHost(apiConfig.host)
                .skipSslValidation(apiConfig.skipSslValidation)
        if (proxyConfiguration != null) {
            connctx = connctx.proxyConfiguration(proxyConfiguration)
        }
        if (apiConfig.connectionPool.size != null) {
            connctx = connctx.connectionPoolSize(apiConfig.connectionPool.size)
        }
        if (apiConfig.threadPool.size != null) {
            connctx = connctx.threadPoolSize(apiConfig.threadPool.size)
        }
        return connctx.build()
    }

    private fun tokenProvider(apiConfig: ApiConfig): PasswordGrantTokenProvider {
        return PasswordGrantTokenProvider.builder().password(apiConfig.password).username(apiConfig.username).build()
    }

    private fun proxyConfiguration(apiConfig: ApiConfig): ProxyConfiguration? {
        val effectiveProxyHost = apiConfig.proxy?.host
        val effectiveProxyPort = apiConfig.proxy?.port ?: 0

        if (effectiveProxyHost != null && PATTERN_HTTP_BASED_PROTOCOL_PREFIX.matcher(effectiveProxyHost).find()) {
            throw ConfigurationException("Configuring of cf.proxyHost or cf.proxy.host configuration parameter must not contain an http(s)://-like prefix; specify the hostname only instead")
        }
        return if (effectiveProxyHost != null && effectiveProxyPort != 0) {
            var proxyIP: String? = null
            // TODO add back InetAddressUtils
            proxyIP = if(false) { // (!InetAddressUtils.isIPv4Address(effectiveProxyHost) && !InetAddressUtils.isIPv6Address(effectiveProxyHost)) {
                /*
				 * NB: There is currently a bug in io.netty.util.internal.SocketUtils.connect()
				 * which is called implicitly by the CF API Client library, which leads to the effect
				 * that a hostname for the proxy isn't resolved. Thus, it is only possible to pass 
				 * IP addresses as proxy names.
				 * To work around this issue, we manually perform a resolution of the hostname here
				 * and then feed that one to the CF API Client library...
				 */
                try {
                    val ia = InetAddress.getByName(effectiveProxyHost)
                    ia.hostAddress
                } catch (e: UnknownHostException) {
                    throw ConfigurationException(String.format("The proxy host '%s' cannot be resolved to an IP address; is there a typo in your configuration?", effectiveProxyHost), e)
                }
            } else {
                // the address specified is already an IP address
                effectiveProxyHost
            }
            ProxyConfiguration.builder().host(proxyIP).port(effectiveProxyPort).build()
        } else {
            null
        }
    }

    private fun cloudFoundryClient(connectionContext: ConnectionContext, tokenProvider: TokenProvider): ReactorCloudFoundryClient {
        return ReactorCloudFoundryClient.builder().connectionContext(connectionContext).tokenProvider(tokenProvider).build()
    }

    fun reset(api: String) {
        val apiConfig = cf.api[api]
        if(apiConfig != null) {
            resetCloudFoundryClient(api, apiConfig)
        }
    }

    private fun resetCloudFoundryClient(api: String, apiConfig: ApiConfig) {
        try {
            val proxyConfiguration = proxyConfiguration(apiConfig)
            val connectionContext = connectionContext(apiConfig, proxyConfiguration)
            val tokenProvider = tokenProvider(apiConfig)
            cloudFoundryClients[api] = cloudFoundryClient(connectionContext, tokenProvider)
        } catch (e: ConfigurationException) {
            log.error(e) {"Restarting Cloud Foundry Client failed due to Configuration Exception raised" }
        }
    }

//    try {
//        if (cloudFoundryClient != null) {
//            /*
//             * Note: there may still be connections and threads open, which need to be closed.
//             * https://github.com/promregator/promregator/issues/161 pointed that out.
//             */
//            val connectionContext = cloudFoundryClient!!.connectionContext
//            // Note: connectionContext is ensured to be non-null
//            if (connectionContext is DefaultConnectionContext) {
//                /*
//                 * For the idea see also
//                 * https://github.com/cloudfoundry/cf-java-client/issues/777 and
//                 * https://issues.jenkins-ci.org/browse/JENKINS-53136
//                 */
//                connectionContext.dispose()
//            }
//        }
//        val proxyConfiguration = proxyConfiguration()
//        val connectionContext = connectionContext(proxyConfiguration)
//        val tokenProvider = tokenProvider()
//        cloudFoundryClient = cloudFoundryClient(connectionContext, tokenProvider)
//    } catch (e: ConfigurationException) {
//        log.error("Restarting Cloud Foundry Client failed due to Configuration Exception raised", e)
//    }

    suspend fun retrieveAllOrgIds(api: String): List<OrganizationResourceV2> {
        return cfQueryV2<OrganizationResourceV2, ListOrganizationsResponseV2, ListOrganizationsRequestV2>(api, ORG) {
            request { page ->
                ListOrganizationsRequestV2.builder()
                        .orderDirection(OrderDirection.ASCENDING)
                        .resultsPerPage(100)
                        .page(page)
                        .build()
            }
            query { organizations().list(it) }
        }
    }

    /* (non-Javadoc)
	 * @see org.cloudfoundry.promregator.cfaccessor.CFAccessor#retrieveSpaceIdsInOrg(java.lang.String)
	 */
    suspend fun retrieveSpaceIdsInOrg(api: String, orgId: String): List<SpaceResourceV2> {
        return cfQueryV2<SpaceResourceV2, ListSpacesResponseV2, ListSpacesRequestV2>(api, SPACE_IN_ORG) {
            request { pageNumber ->
                ListSpacesRequestV2.builder()
                        .organizationId(orgId)
                        .orderDirection(OrderDirection.ASCENDING)
                        .resultsPerPage(100)
                        .page(pageNumber)
                        .build()
            }
            query { spaces().list(it) }
        }
    }

    /* (non-Javadoc)
	 * @see org.cloudfoundry.promregator.cfaccessor.CFAccessor#retrieveAllApplicationIdsInSpace(java.lang.String, java.lang.String)
	 */
    suspend fun retrieveAllApplicationIdsInSpace(api: String, orgId: String, spaceId: String): List<ApplicationResourceV2> {
        return cfQueryV2<ApplicationResourceV2, ListApplicationsResponseV2, ListApplicationsRequestV2>(api, ALL_APPS_IN_SPACE) {
            request { pageNumber ->
                ListApplicationsRequestV2.builder()
                        .organizationId(orgId)
                        .spaceId(spaceId)
                        .orderDirection(OrderDirection.ASCENDING)
                        .resultsPerPage(100)
                        .page(pageNumber)
                        .build()
            }
            query { applicationsV2().list(it) }
        }
    }

    suspend fun retrieveSpaceSummary(api: String, spaceId: String): GetSpaceSummaryResponse? {
        // Note that GetSpaceSummaryRequest is not paginated
        val request = GetSpaceSummaryRequest.builder().spaceId(spaceId).build()
        return try {
            cloudFoundryClients[api]?.spaces()?.getSummary(request)?.awaitSingle()
        } catch (e: Exception) {
            log.error(e) { "Error retrieving spaceSummary api:$api spaceId:$spaceId" }
            null
        }
    }

    suspend fun retrieveAllDomains(api: String, orgId: String): org.cloudfoundry.client.v2.organizations.ListOrganizationDomainsResponse? {
        val request = org.cloudfoundry.client.v2.organizations.ListOrganizationDomainsRequest.builder().organizationId(orgId).build()
        return cloudFoundryClients[api]?.organizations()?.listDomains(request)?.awaitSingle()
    }



//    fun retrieveOrgIdV3(api: String, orgName: String?): Mono<ListOrganizationsResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//
//        // Note: even though we use the List request here, the number of values returned is either zero or one
//        // ==> No need for a paged request.
//        val orgsRequest = ListOrganizationsRequest.builder().name(orgName).build()
//        return paginatedRequestFetcher.performGenericRetrieval(RequestType.ORG, orgName, orgsRequest,
//                { or: ListOrganizationsRequest? ->
//                    cloudFoundryClients[api]?.organizationsV3()
//                            ?.list(or) ?: Mono.empty()
//                }, cf.request.timeout.org)
//    }
//
//    fun retrieveAllOrgIdsV3(api: String): Mono<ListOrganizationsResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//        val requestGenerator = PaginatedRequestGeneratorFunctionV3 { resultsPerPage: Int, pageNumber: Int ->
//            ListOrganizationsRequest.builder()
//                    .perPage(resultsPerPage)
//                    .page(pageNumber)
//                    .build()
//        }
//        val responseGenerator = PaginatedResponseGeneratorFunctionV3 { list: List<OrganizationResource?>, numberOfPages: Int ->
//            ListOrganizationsResponse.builder()
//                    .addAllResources(list)
//                    .pagination(Pagination.builder().totalPages(numberOfPages).totalResults(list.size).build())
//                    .build()
//        }
//        return paginatedRequestFetcher.performGenericPagedRetrievalV3(RequestType.ALL_ORGS, "(empty)", requestGenerator,
//                { r: ListOrganizationsRequest? -> cloudFoundryClients[api]?.organizationsV3()?.list(r) ?: Mono.empty() },
//                cf.request.timeout.org, responseGenerator)
//    }
//
//    fun retrieveSpaceIdV3(api: String, orgId: String?, spaceName: String?): Mono<ListSpacesResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//
//        // Note: even though we use the List request here, the number of values returned is either zero or one
//        // ==> No need for a paged request.
//        val key = String.format("%s|%s", orgId, spaceName)
//        val spacesRequest = ListSpacesRequest.builder().organizationId(orgId).name(spaceName).build()
//        return paginatedRequestFetcher.performGenericRetrieval(RequestType.SPACE, key, spacesRequest, { sr: ListSpacesRequest? ->
//            cloudFoundryClients[api]?.spacesV3()?.list(sr) ?: Mono.empty() },
//                cf.request.timeout.space)
//    }
//
//    fun retrieveSpaceIdsInOrgV3(api: String, orgId: String?): Mono<ListSpacesResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//        val requestGenerator = PaginatedRequestGeneratorFunctionV3 { resultsPerPage: Int, pageNumber: Int ->
//            ListSpacesRequest.builder()
//                    .organizationId(orgId)
//                    .perPage(resultsPerPage)
//                    .page(pageNumber)
//                    .build()
//        }
//        val responseGenerator = PaginatedResponseGeneratorFunctionV3 { list: List<SpaceResource?>, numberOfPages: Int ->
//            ListSpacesResponse.builder()
//                    .addAllResources(list)
//                    .pagination(Pagination.builder().totalPages(numberOfPages).totalResults(list.size).build())
//                    .build()
//        }
//        return paginatedRequestFetcher.performGenericPagedRetrievalV3(RequestType.SPACE_IN_ORG, orgId, requestGenerator,
//                { r: ListSpacesRequest? -> cloudFoundryClients[api]?.spacesV3()?.list(r) },
//                cf.request.timeout.space, responseGenerator)
//    }

    suspend fun retrieveAllApplicationsInSpaceV3(api: String, orgId: String, spaceId: String): List<ApplicationResourceV3> {
        if (!isV3Enabled) {
            error("v3 Enpoints are not supported")
        }
//        val key = String.format("%s|%s", orgId, spaceId)
//        val requestGenerator = PaginatedRequestGeneratorFunctionV3 { resultsPerPage: Int, pageNumber: Int ->
//
//        }
//        val responseGenerator = PaginatedResponseGeneratorFunctionV3 { list: List<org.cloudfoundry.client.v3.applications.ApplicationResource?>, numberOfPages: Int ->
//            org.cloudfoundry.client.v3.applications.ListApplicationsResponse.builder()
//                    .addAllResources(list)
//                    .pagination(Pagination.builder().totalPages(numberOfPages).totalResults(list.size).build())
//                    .build()
//        }
//        return paginatedRequestFetcher.performGenericPagedRetrievalV3(RequestType.ALL_APPS_IN_SPACE, key, requestGenerator,
//                { r: org.cloudfoundry.client.v3.applications.ListApplicationsRequest? ->
//                    cloudFoundryClients[api]?.applicationsV3()?.list(r) ?: Mono.empty() },
//                cf.request.timeout.appInSpace, responseGenerator)

        return cfQueryV3<ApplicationResourceV3, ListApplicationsResponseV3, ListApplicationsRequestV3>(api, ALL_APPS_IN_SPACE) {
            request { pageNumber ->
                ListApplicationsRequestV3.builder()
                        .organizationId(orgId)
                        .spaceId(spaceId)
                        .perPage(100)
                        .page(pageNumber)
                        .build()
            }
            query { applicationsV3().list(it) }
        }
    }

    private suspend fun <R: ResourceV2<*>, T: org.cloudfoundry.client.v2.PaginatedResponse<R>,S : PaginatedRequestV2> cfQueryV2(api: String, type: RequestType, requestBlock: CfClientDslV2<R, T, S>.() -> Unit): List<R> {
        val cfClient = cloudFoundryClients[api]
        val dsl = CfClientDslV2<R, T, S>(cfClient, type)
        dsl.requestBlock()
        return dsl.runQueries()
    }

    private suspend fun <R: Resource, T: org.cloudfoundry.client.v3.PaginatedResponse<R>,S : V3PaginatedRequest> cfQueryV3(api: String, type: RequestType, requestBlock: CfClientDslV3<R, T, S>.() -> Unit): List<R> {
        val cfClient = cloudFoundryClients[api]
        val dsl = CfClientDslV3<R, T, S>(cfClient, type)
        dsl.requestBlock()
        return dsl.runQueries()
    }

    inner class CfClientDslV2<R: ResourceV2<*>, T: org.cloudfoundry.client.v2.PaginatedResponse<R>, S: PaginatedRequestV2>(
            private val cfClient: ReactorCloudFoundryClient?,
            private val type: RequestType,
            ) {
        lateinit var requestTemplate : (Int) -> S
        lateinit var queryTemplate: ReactorCloudFoundryClient.(S) -> Mono<T>

        fun request(block: (Int) -> S) {
            requestTemplate = block
        }

        fun query(block: ReactorCloudFoundryClient.(S) -> Mono<T>) {
            queryTemplate = block
        }

        suspend fun runQueries() : List<R> {
            val req = requestTemplate(1)

            val clientSafe = cfClient ?: return listOf()
            val first = rateLimitAndRecord(type) {clientSafe.queryTemplate(req).awaitSingle() }
            val totalPages = first?.totalPages ?: 0
            val firstResources = first?.resources ?: listOf()

            val otherPages = if(totalPages > 1) {
                coroutineScope {
                    (1..totalPages).map {
                        val otherReq = requestTemplate(it)
                        async { rateLimitAndRecord(type) { clientSafe.queryTemplate(otherReq).awaitSingle() } }
                    }.awaitAll().flatMap { it?.resources ?: listOf() }
                }

            } else {
                listOf()
            }

            return (firstResources+otherPages)
        }
    }


    inner class CfClientDslV3<R: Resource, T: org.cloudfoundry.client.v3.PaginatedResponse<R>, S: V3PaginatedRequest>(
            private val cfClient: ReactorCloudFoundryClient?,
            private val type: RequestType,
            ) {
        lateinit var requestTemplate : (Int) -> S
        lateinit var queryTemplate: ReactorCloudFoundryClient.(S) -> Mono<T>

        fun request(block: (Int) -> S) {
            requestTemplate = block
        }

        fun query(block: ReactorCloudFoundryClient.(S) -> Mono<T>) {
            queryTemplate = block
        }

        suspend fun runQueries() : List<R> {
            val req = requestTemplate(1)

            val clientSafe = cfClient ?: return listOf()
            val first = rateLimitAndRecord(type) { clientSafe.queryTemplate(req).awaitSingle() }
            val totalPages = first?.pagination?.totalPages ?: 0
            val firstResources = first?.resources ?: listOf()

            val otherPages = if(totalPages > 1) {
                coroutineScope {
                    (1..totalPages).map {
                        val otherReq = requestTemplate(it)
                        async { rateLimitAndRecord(type) { clientSafe.queryTemplate(otherReq).awaitSingle() } }
                    }.awaitAll().flatMap { it?.resources ?: listOf() }
                }

            } else {
                listOf()
            }

            return (firstResources+otherPages)
        }
    }



//    fun retrieveSpaceV3(api: String, spaceId: String?): Mono<GetSpaceResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//
//        // This API has drastically changed in v3 and does not support the same resources. This call for a space summary will probably
//        // take another call to list applications for a space, list routes for the apps, and list domains in the org
//        // Previously they were all grouped into this API
//        val request = GetSpaceRequest.builder().spaceId(spaceId).build()
//        return paginatedRequestFetcher.performGenericRetrieval(RequestType.SPACE_SUMMARY, spaceId,
//                request, { r: GetSpaceRequest? -> cloudFoundryClients[api]?.spacesV3()?.get(r) ?: Mono.empty() },
//                cf.request.timeout.appSummary)
//    }
//
//    fun retrieveAllDomainsV3(api: String, orgId: String?): Mono<ListOrganizationDomainsResponse> {
//        if (!isV3Enabled) {
//            throw UnsupportedOperationException("V3 API is not supported on your foundation.")
//        }
//        val request = ListOrganizationDomainsRequest.builder().organizationId(orgId).build()
//        return paginatedRequestFetcher.performGenericRetrieval(RequestType.DOMAINS, orgId,
//                request, { r: ListOrganizationDomainsRequest? ->
//            cloudFoundryClients[api]?.organizationsV3()?.listDomains(request) ?: Mono.empty() },
//                cf.request.timeout.domain)
//    }
//
//    fun retrieveRoutesForAppId(appId: String?): Mono<ListApplicationRoutesResponse> {
//        throw UnsupportedOperationException()
//    }

    companion object {
        private val PATTERN_HTTP_BASED_PROTOCOL_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE)
    }
}
