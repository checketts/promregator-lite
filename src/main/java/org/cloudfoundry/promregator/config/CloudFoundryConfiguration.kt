package org.cloudfoundry.promregator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = "cf")
class CloudFoundryConfiguration(
        val apiHost: String?,
        val username: String?,
        val password: String?,
        val skipSslValidation: Boolean = false,
        val proxy: ProxyConfig? = null,
        val request: RequestConfig = RequestConfig(),
        val connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
        val threadPool: ThreadPoolConfig = ThreadPoolConfig(),
        val api: List<ApiConfig> = listOf()
)

data class ApiConfig(
        val id: String,
        val host: String,
        val username: String,
        val password: String
)

data class ProxyConfig(
        val host: String? = null,
        val port: Int? = null
)

data class RequestConfig(
        val timeout: TimeoutConfig = TimeoutConfig()
)

data class TimeoutConfig(
        val org: Int = 2500,
        val space: Int = 2500,
        val appInSpace: Int = 2500,
        val appSummary: Int = 4000
)

data class ConnectionPoolConfig(
        val size: Int? = null
)


data class ThreadPoolConfig(
        val size: Int? = null
)