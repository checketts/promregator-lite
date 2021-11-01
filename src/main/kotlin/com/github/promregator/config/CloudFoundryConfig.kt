package com.github.promregator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import java.time.Duration

@ConstructorBinding
@ConfigurationProperties(prefix = "cf")
data class CloudFoundryConfig(
        val api_host: String? = null,
        val username: String? = null,
        val password: String? = null,
        val apiPort: Int = 443,
        val skipSslValidation: Boolean = false,
        val proxy: ProxyConfig? = null,
        val connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
        val threadPool: ThreadPoolConfig = ThreadPoolConfig(),
        val api: MutableMap<String, ApiConfig> = mutableMapOf(),
//        val cache: CacheConfig = CacheConfig(),
        val request: RequestConfig = RequestConfig(),
) {
    init {
        if (api[DEFAULT_API] == null && api_host != null && username != null && password != null) api[DEFAULT_API] = ApiConfig(
                host = api_host ?: "",
                username = username ?: "",
                password = password ?: "",
                proxy = proxy,
                port = apiPort,
                skipSslValidation = skipSslValidation,
        )
    }

    companion object {
        const val DEFAULT_API = "__default__"
    }
}

data class RequestConfig(
        val rateLimit: Double = 0.0,
        val backoff: Long = 500,
        val timeout: TimeoutConfig = TimeoutConfig(),
        val cacheDuration: Duration = Duration.ofSeconds(30)
)

//data class CacheConfig(
//        val timeout: CacheTimeoutConfig = CacheTimeoutConfig(),
//        val expiry: CacheExpiryConfig = CacheExpiryConfig()
//)
//
//data class CacheExpiryConfig(
//        val org: Int = 120,
//        val space: Int = 120,
//        val application: Int = 120
//)
//
//data class CacheTimeoutConfig(
//        val org: Int = 3600,
//        val space: Int = 3600,
//        val application: Int = 300
//)


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

data class TimeoutConfig(
        val org: Int = 2500,
        val space: Int = 2500,
        val appInSpace: Int = 2500,
        val appSummary: Int = 4000,
        val domain: Int = 2500,
)

data class ConnectionPoolConfig(
        val size: Int? = null
)

data class ThreadPoolConfig(
        val size: Int? = null
)
