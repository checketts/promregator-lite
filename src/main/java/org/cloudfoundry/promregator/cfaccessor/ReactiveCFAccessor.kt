package org.cloudfoundry.promregator.cfaccessor

import mu.KotlinLogging
import org.cloudfoundry.client.v2.OrderDirection
import org.cloudfoundry.client.v2.applications.ApplicationResource
import org.cloudfoundry.client.v2.applications.ListApplicationsRequest
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
import org.cloudfoundry.promregator.config.ApiConfig
import org.cloudfoundry.promregator.config.CloudFoundryConfiguration
import org.cloudfoundry.promregator.config.ConfigurationException
import org.cloudfoundry.promregator.config.PromregatorConfiguration
import org.cloudfoundry.reactor.ConnectionContext
import org.cloudfoundry.reactor.DefaultConnectionContext
import org.cloudfoundry.reactor.ProxyConfiguration
import org.cloudfoundry.reactor.TokenProvider
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider
import org.cloudfoundry.util.PaginationUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.channel.BootstrapHandlers
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.regex.Pattern

private val logger = KotlinLogging.logger { }

@Service
@ConditionalOnProperty("cf.api.client.enabled", matchIfMissing = true)
class ReactiveCFAccessor(
        val cf: CloudFoundryConfiguration,
        promregatorConfiguration: PromregatorConfiguration
): CFAccessor {
    private val cloudFoundryClients = ConcurrentHashMap<String,ReactorCloudFoundryClient>()

    init {
        cf.api.forEach { resetCloudFoundryClient(it.key, it.value) }
        if (promregatorConfiguration.internal.performPrecheckAPIVersion) {
            val request = GetInfoRequest.builder().build()
            cloudFoundryClients.forEach { (api, client) ->
                val getInfo = client.info()[request].block()
                        ?: throw RuntimeException("Error connecting to CF api '$api'")
                // NB: This also ensures that the connection has been established properly...
                logger.info { "Target CF platform ($api) is running on API version ${getInfo.apiVersion}" }
            }

        }
    }



    private fun connectionContext(apiConfig: ApiConfig, proxyConfiguration: ProxyConfiguration?): DefaultConnectionContext {
        if (PATTERN_HTTP_BASED_PROTOCOL_PREFIX.matcher(apiConfig.host ?: "").find()) {
            throw ConfigurationException("cf.api_host configuration parameter must not contain an http(s)://-like prefix; specify the hostname only instead")
        }
        var connctx = DefaultConnectionContext.builder()
                .apiHost(apiConfig.host)
                .port(apiConfig.port)
                .secure(!apiConfig.skipSslValidation)
                .skipSslValidation(apiConfig.skipSslValidation)
                .httpClient(HttpClient.create().metrics(true)
                        .tcpConfiguration { tc -> tc.bootstrap{
                    b -> BootstrapHandlers.updateLogSupport(b, CustomLogger(CustomLogger::class.java))}
                }
    )

        proxyConfiguration?.let { connctx = connctx.proxyConfiguration(proxyConfiguration) }
        apiConfig.connectionPool.size?.let { connctx = connctx.connectionPoolSize(it) }
        apiConfig.threadPool.size?.let { connctx = connctx.threadPoolSize(it) }
        return connctx.build()
    }

    private fun tokenProvider(apiConfig: ApiConfig): PasswordGrantTokenProvider {
        return PasswordGrantTokenProvider.builder().password(apiConfig.password).username(apiConfig.username).build()
    }

    private fun proxyConfiguration(apiConfig: ApiConfig): ProxyConfiguration? {
        val effectiveProxyHost = apiConfig.host
        val effectiveProxyPort = apiConfig.proxy?.port ?: 0
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

    private fun resetCloudFoundryClient(api: String, apiConfig: ApiConfig) {
        val proxyConfiguration = proxyConfiguration(apiConfig)
        val connectionContext = connectionContext(apiConfig, proxyConfiguration)
        val tokenProvider = tokenProvider(apiConfig)
        cloudFoundryClients[api] = cloudFoundryClient(connectionContext, tokenProvider)
    }

    @Scheduled(fixedRate = 1 * 60 * 1000, initialDelay = 60 * 1000)
    private fun connectionWatchdog() { // see also https://github.com/promregator/promregator/issues/83
        cloudFoundryClients.forEach { (api, client) ->
            client.info()[DUMMY_GET_INFO_REQUEST]
                .timeout(Duration.ofMillis(2500))
                .doOnError { e: Throwable? -> logger.warn(e) { "Woof woof! It appears that the connection to the Cloud Controller is gone. Trying to restart Cloud Foundry Client" } }
                .onErrorReturn(ERRONEOUS_GET_INFO_RESPONSE)
                .subscribe { response: GetInfoResponse ->
                    if (response === ERRONEOUS_GET_INFO_RESPONSE) {
                        try { // Note that there is no method at this.cloudFoundryClient, which would permit closing the old client
                            resetCloudFoundryClient(api, cf.api[api] ?: error("Unable to locate api config for $api"))
//                            exitProcess(127)
                        } catch (ce: ConfigurationException) {
                            logger.warn(ce) { "Unable to reconstruct connection to CF CC" }
                        }
                    }
                }
        }
    }

    private fun <T> timeoutAndErrorLog(logName: String, key: String, timeoutInMS: Int = 2500) = Function<Mono<T>, Mono<T>> { f ->
        f.timeout(Duration.ofMillis(timeoutInMS.toLong()))
                .onErrorResume { throwable: Throwable ->
                    val unwrappedThrowable: Throwable = reactor.core.Exceptions.unwrap(throwable)
                    if (unwrappedThrowable is TimeoutException) {
                        logger.error { "Async retrieval of $logName with key $key caused a timeout after ${timeoutInMS}ms" }
                    } else {
                        logger.error(unwrappedThrowable) { "Async retrieval of $logName with key $key raised a reactor error" }
                    }
                    Mono.empty()
                }
    }


    override fun retrieveOrgId(api: String, orgName: String): Mono<ListOrganizationsResponse> {
        // Note: even though we use the List request here, the number of values returned is either zero or one
        // ==> No need for a paged request.
        val orgsRequest = ListOrganizationsRequest.builder().name(orgName).build()

        return client(api).organizations().list(orgsRequest)
                .transform(timeoutAndErrorLog("retrieveOrgId", orgName, cf.request.timeout.org))

    }

    override fun retrieveAllOrgIds(api: String): Flux<OrganizationResource> {
        return PaginationUtils.requestClientV2Resources { page ->
            client(api).organizations().list(ListOrganizationsRequest.builder()
                    .orderDirection(OrderDirection.ASCENDING)
                    .resultsPerPage(RESULTS_PER_PAGE)
                    .page(page)
                    .build())
                    .transform(timeoutAndErrorLog("retrieveAllOrgIds", "(empty)", cf.request.timeout.org)) }
    }

    override fun retrieveSpaceId(api: String, orgId: String, spaceName: String): Mono<ListSpacesResponse> {
        // Note: even though we use the List request here, the number of values returned is either zero or one
        // ==> No need for a paged request.
        val key = String.format("%s|%s", orgId, spaceName)
        val spacesRequest = ListSpacesRequest.builder().organizationId(orgId).name(spaceName).build()
        return client(api).spaces().list(spacesRequest)
                .transform(timeoutAndErrorLog( "retrieveSpaceId", key, cf.request.timeout.org))
    }

    override fun retrieveSpaceIdsInOrg(api: String, orgId: String): Flux<SpaceResource> {
        return PaginationUtils.requestClientV2Resources { page -> client(api).spaces().list(ListSpacesRequest.builder()
                .organizationId(orgId)
                .orderDirection(OrderDirection.ASCENDING)
                .resultsPerPage(RESULTS_PER_PAGE)
                .page(page)
                .build())
                .transform(timeoutAndErrorLog("retrieveAllSpaceIdsInOrg", orgId, cf.request.timeout.space)) }
    }

    override fun retrieveAllApplicationIdsInSpace(api: String, orgId: String, spaceId: String): Flux<ApplicationResource> {
        val key = String.format("%s|%s", orgId, spaceId)
        return PaginationUtils.requestClientV2Resources { page ->
            client(api).applicationsV2().list(ListApplicationsRequest.builder()
                    .organizationId(orgId)
                    .spaceId(spaceId)
                    .orderDirection(OrderDirection.ASCENDING)
                    .resultsPerPage(RESULTS_PER_PAGE)
                    .page(page)
                    .build())
                .transform(timeoutAndErrorLog("retrieveAllApplicationIdsInSpace", key, cf.request.timeout.appInSpace)) }
    }

    override fun retrieveSpaceSummary(api: String, spaceId: String): Mono<GetSpaceSummaryResponse> {
        // Note that GetSpaceSummaryRequest is not paginated
        val request = GetSpaceSummaryRequest.builder().spaceId(spaceId).build()
        return client(api).spaces().getSummary(request)
                .transform(timeoutAndErrorLog("retrieveSpaceSummary", spaceId, cf.request.timeout.appSummary))
    }

    private fun client(api: String) = cloudFoundryClients[api] ?: error("No api matching '$api' found")


    private val MAX_SUPPORTED_RESULTS_PER_PAGE = 100
    private val RESULTS_PER_PAGE = MAX_SUPPORTED_RESULTS_PER_PAGE

    companion object {
        private val PATTERN_HTTP_BASED_PROTOCOL_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE)
        private val DUMMY_GET_INFO_REQUEST = GetInfoRequest.builder().build()
        private val ERRONEOUS_GET_INFO_RESPONSE = GetInfoResponse.builder().apiVersion("FAILED").build()
    }
}