package org.cloudfoundry.promregator.cfaccessor

import mu.KotlinLogging
import org.cloudfoundry.client.v2.OrderDirection
import org.cloudfoundry.client.v2.applications.ApplicationResource
import org.cloudfoundry.client.v2.applications.ListApplicationsRequest
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse
import org.cloudfoundry.client.v2.info.GetInfoRequest
import org.cloudfoundry.client.v2.info.GetInfoResponse
import org.cloudfoundry.client.v2.organizations.ListOrganizationsRequest
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse
import org.cloudfoundry.client.v2.organizations.OrganizationResource
import org.cloudfoundry.client.v2.spaces.GetSpaceSummaryRequest
import org.cloudfoundry.client.v2.spaces.GetSpaceSummaryResponse
import org.cloudfoundry.client.v2.spaces.ListSpacesRequest
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceResource
import org.cloudfoundry.promregator.config.CloudFoundryConfiguration
import org.cloudfoundry.promregator.config.ConfigurationException
import org.cloudfoundry.promregator.config.PromregatorConfiguration
import org.cloudfoundry.reactor.ConnectionContext
import org.cloudfoundry.reactor.DefaultConnectionContext
import org.cloudfoundry.reactor.ProxyConfiguration
import org.cloudfoundry.reactor.TokenProvider
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.lang.RuntimeException
import java.time.Duration
import java.util.function.Function
import java.util.regex.Pattern
import javax.annotation.PostConstruct

private val logger = KotlinLogging.logger {  }

@Service
class CFAccessor(
        val cf: CloudFoundryConfiguration,
        promregatorConfiguration: PromregatorConfiguration
) {
    private lateinit var cloudFoundryClient: ReactorCloudFoundryClient
    private lateinit var paginatedRequestFetcher: ReactiveCFPaginatedRequestFetcher

    init {
        resetCloudFoundryClient()
        if (promregatorConfiguration.internal.performPrecheckAPIVersion) {
            val request = GetInfoRequest.builder().build()
            val getInfo = cloudFoundryClient.info()[request].block() ?: throw RuntimeException("Error connecting to CF api")
            // NB: This also ensures that the connection has been established properly...
            logger.info { "Target CF platform is running on API version ${getInfo.apiVersion}"}
        }
    }

    private fun connectionContext(proxyConfiguration: ProxyConfiguration?): DefaultConnectionContext {
        if (PATTERN_HTTP_BASED_PROTOCOL_PREFIX.matcher(cf.apiHost).find()) {
            throw ConfigurationException("cf.api_host configuration parameter must not contain an http(s)://-like prefix; specify the hostname only instead")
        }
        var connctx = DefaultConnectionContext.builder()
                .apiHost(cf.apiHost)
                .skipSslValidation(cf.skipSslValidation)
                .httpClient(HttpClient.create().metrics(true));

        proxyConfiguration?.let { connctx = connctx.proxyConfiguration(proxyConfiguration) }
        cf.connectionPool.size?.let { connctx = connctx.connectionPoolSize(it) }
        cf.threadPool.size?.let { connctx = connctx.threadPoolSize(it) }
        return connctx.build()
    }

    private fun tokenProvider(): PasswordGrantTokenProvider {
        return PasswordGrantTokenProvider.builder().password(cf.password).username(cf.username).build()
    }

    private fun proxyConfiguration(): ProxyConfiguration? {
        val effectiveProxyHost = cf.proxy?.host
        val effectiveProxyPort = cf.proxy?.port ?: 0
        if (effectiveProxyHost != null && PATTERN_HTTP_BASED_PROTOCOL_PREFIX.matcher(effectiveProxyHost).find()) {
            throw ConfigurationException("Configuring of cf.proxyHost or cf.proxy.host configuration parameter must not contain an http(s)://-like prefix; specify the hostname only instead")
        }
        return if (effectiveProxyHost != null && effectiveProxyPort != 0) {
            var proxyIP: String? = null
            //			if (!InetAddressUtils.isIPv4Address(effectiveProxyHost) && !InetAddressUtils.isIPv6Address(effectiveProxyHost)) {
//				/*
//				 * NB: There is currently a bug in io.netty.util.internal.SocketUtils.connect()
//				 * which is called implicitly by the CF API Client library, which leads to the effect
//				 * that a hostname for the proxy isn't resolved. Thus, it is only possible to pass
//				 * IP addresses as proxy names.
//				 * To work around this issue, we manually perform a resolution of the hostname here
//				 * and then feed that one to the CF API Client library...
//				 */
//				try {
//					InetAddress ia = InetAddress.getByName(effectiveProxyHost);
//					proxyIP = ia.getHostAddress();
//				} catch (UnknownHostException e) {
//					throw new ConfigurationException(String.format("The proxy host '%s' cannot be resolved to an IP address; is there a typo in your configuration?", effectiveProxyHost), e);
//				}
//			} else {
// the address specified is already an IP address
            proxyIP = effectiveProxyHost
            //			}
            ProxyConfiguration.builder().host(proxyIP).port(effectiveProxyPort).build()
        } else {
            null
        }
    }

    private fun cloudFoundryClient(connectionContext: ConnectionContext, tokenProvider: TokenProvider): ReactorCloudFoundryClient {
        return ReactorCloudFoundryClient.builder().connectionContext(connectionContext).tokenProvider(tokenProvider).build()
    }

    private fun resetCloudFoundryClient() {
        val proxyConfiguration = proxyConfiguration()
        val connectionContext = connectionContext(proxyConfiguration)
        val tokenProvider = tokenProvider()
        cloudFoundryClient = cloudFoundryClient(connectionContext, tokenProvider)
    }

    @Scheduled(fixedRate = 1 * 60 * 1000, initialDelay = 60 * 1000)
    private fun connectionWatchdog() { // see also https://github.com/promregator/promregator/issues/83
        cloudFoundryClient.info()[DUMMY_GET_INFO_REQUEST]
                .timeout(Duration.ofMillis(2500))
                .doOnError { e: Throwable? -> logger.warn(e) {"Woof woof! It appears that the connection to the Cloud Controller is gone. Trying to restart Cloud Foundry Client"}}
                .onErrorReturn(ERRONEOUS_GET_INFO_RESPONSE)
                .subscribe { response: GetInfoResponse ->
                    if (response === ERRONEOUS_GET_INFO_RESPONSE) {
                        try { // Note that there is no method at this.cloudFoundryClient, which would permit closing the old client
                            resetCloudFoundryClient()
                        } catch (ce: ConfigurationException) {
                            logger.warn(ce) {"Unable to reconstruct connection to CF CC"}
                        }
                    }
                }
    }

    @PostConstruct
    private fun setupPaginatedRequestFetcher() {
        paginatedRequestFetcher = ReactiveCFPaginatedRequestFetcher()
    }

    fun retrieveOrgId(orgName: String): Mono<ListOrganizationsResponse> { // Note: even though we use the List request here, the number of values returned is either zero or one
// ==> No need for a paged request.
        val orgsRequest = ListOrganizationsRequest.builder().name(orgName).build()
        return paginatedRequestFetcher.performGenericRetrieval("org", "retrieveOrgId", orgName, orgsRequest,
                { or: ListOrganizationsRequest? ->
                    cloudFoundryClient.organizations()
                            .list(or)
                }, cf.request.timeout.org)
    }

    fun retrieveAllOrgIds(): Mono<ListOrganizationsResponse> {
        val requestGenerator = PaginatedRequestGeneratorFunction { orderDirection: OrderDirection?, resultsPerPage: Int, pageNumber: Int ->
            ListOrganizationsRequest.builder()
                    .orderDirection(orderDirection)
                    .resultsPerPage(resultsPerPage)
                    .page(pageNumber)
                    .build()
        }
        val responseGenerator = PaginatedResponseGeneratorFunction { list: List<OrganizationResource?>, numberOfPages: Int ->
            ListOrganizationsResponse.builder()
                    .addAllResources(list)
                    .totalPages(numberOfPages)
                    .totalResults(list.size)
                    .build()
        }
        return paginatedRequestFetcher.performGenericPagedRetrieval("allOrgs", "retrieveAllOrgIds", "(empty)", requestGenerator,
                Function { r: ListOrganizationsRequest? -> cloudFoundryClient.organizations().list(r) }, cf.request.timeout.org, responseGenerator)
    }

    fun retrieveSpaceId(orgId: String, spaceName: String): Mono<ListSpacesResponse> { // Note: even though we use the List request here, the number of values returned is either zero or one
// ==> No need for a paged request.
        val key = String.format("%s|%s", orgId, spaceName)
        val spacesRequest = ListSpacesRequest.builder().organizationId(orgId).name(spaceName).build()
        return paginatedRequestFetcher.performGenericRetrieval("space", "retrieveSpaceId", key, spacesRequest, { sr: ListSpacesRequest? -> cloudFoundryClient.spaces().list(sr) },
                cf.request.timeout.space)
    }

    fun retrieveSpaceIdsInOrg(orgId: String): Mono<ListSpacesResponse> {
        val requestGenerator = PaginatedRequestGeneratorFunction { orderDirection: OrderDirection?, resultsPerPage: Int, pageNumber: Int ->
            ListSpacesRequest.builder()
                    .organizationId(orgId)
                    .orderDirection(orderDirection)
                    .resultsPerPage(resultsPerPage)
                    .page(pageNumber)
                    .build()
        }
        val responseGenerator = PaginatedResponseGeneratorFunction { list: List<SpaceResource?>, numberOfPages: Int ->
            ListSpacesResponse.builder()
                    .addAllResources(list)
                    .totalPages(numberOfPages)
                    .totalResults(list.size)
                    .build()
        }
        return paginatedRequestFetcher.performGenericPagedRetrieval("space", "retrieveAllSpaceIdsInOrg", orgId, requestGenerator,
                Function { r: ListSpacesRequest? -> cloudFoundryClient.spaces().list(r) }, cf.request.timeout.space, responseGenerator)
    }

    fun retrieveAllApplicationIdsInSpace(orgId: String, spaceId: String): Mono<ListApplicationsResponse> {
        val key = String.format("%s|%s", orgId, spaceId)
        val requestGenerator = PaginatedRequestGeneratorFunction { orderDirection: OrderDirection?, resultsPerPage: Int, pageNumber: Int ->
            ListApplicationsRequest.builder()
                    .organizationId(orgId)
                    .spaceId(spaceId)
                    .orderDirection(orderDirection)
                    .resultsPerPage(resultsPerPage)
                    .page(pageNumber)
                    .build()
        }
        val responseGenerator = PaginatedResponseGeneratorFunction { list: List<ApplicationResource?>, numberOfPages: Int ->
            ListApplicationsResponse.builder()
                    .addAllResources(list)
                    .totalPages(numberOfPages)
                    .totalResults(list.size)
                    .build()
        }
        return paginatedRequestFetcher.performGenericPagedRetrieval("allApps", "retrieveAllApplicationIdsInSpace", key, requestGenerator,
                Function { r: ListApplicationsRequest? -> cloudFoundryClient.applicationsV2().list(r) }, cf.request.timeout.appInSpace, responseGenerator)
    }

    fun retrieveSpaceSummary(spaceId: String): Mono<GetSpaceSummaryResponse> { // Note that GetSpaceSummaryRequest is not paginated
        val request = GetSpaceSummaryRequest.builder().spaceId(spaceId).build()
        return paginatedRequestFetcher.performGenericRetrieval("spaceSummary", "retrieveSpaceSummary", spaceId,
                request, { r: GetSpaceSummaryRequest? -> cloudFoundryClient.spaces().getSummary(r) }, cf.request.timeout.appSummary)
    }

    companion object {
        private val PATTERN_HTTP_BASED_PROTOCOL_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE)
        private val DUMMY_GET_INFO_REQUEST = GetInfoRequest.builder().build()
        private val ERRONEOUS_GET_INFO_RESPONSE = GetInfoResponse.builder().apiVersion("FAILED").build()
    }
}