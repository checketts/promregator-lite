package org.cloudfoundry.promregator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import java.time.Duration

@ConstructorBinding
@ConfigurationProperties(prefix = "cf")
class CloudFoundryConfiguration(
        apiHost: String?,
        username: String?,
        password: String?,
        apiPort: Int = 443,
        skipSslValidation: Boolean = false,
        proxy: ProxyConfig? = null,
        connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
        threadPool: ThreadPoolConfig = ThreadPoolConfig(),
        val request: RequestConfig = RequestConfig(),
        val api: MutableMap<String, ApiConfig> = mutableMapOf()
) {
    init {
        if (api["__default__"] == null && apiHost != null && username != null && password != null) api["__default__"] = ApiConfig(
                host = apiHost,
                username = username,
                password = password,
                proxy = proxy,
                port = apiPort,
                skipSslValidation = skipSslValidation,
                connectionPool = connectionPool,
                threadPool = threadPool
        )
    }
}

data class ApiConfig(
        val host: String,
        val username: String,
        val password: String,
        val proxy: ProxyConfig? = null,
        val port: Int = 443,
        val skipSslValidation: Boolean = false,
        val connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
        val threadPool: ThreadPoolConfig = ThreadPoolConfig()
)

data class ProxyConfig(
        val host: String? = null,
        val port: Int? = null
)

data class RequestConfig(
        val timeout: TimeoutConfig = TimeoutConfig(),
        val cacheDuration: Duration = Duration.ofSeconds(30)
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

