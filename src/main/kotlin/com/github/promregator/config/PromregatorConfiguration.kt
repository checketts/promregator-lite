package com.github.promregator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = "promregator")
data class PromregatorConfiguration(
        val targets: List<Target> = ArrayList(),
        val authenticator: AuthenticatorConfiguration = AuthenticatorConfiguration(),
        val targetAuthenticators: List<TargetAuthenticatorConfiguration> = ArrayList(),
        val internal: InternalConfig = InternalConfig(),
)

data class InternalConfig(
        val preCheckAPIVersion: Boolean = true,
)

open class AuthenticatorConfiguration(
){
    val type: String? = null
    val basic: BasicAuthenticationConfiguration? = null
    val oauth2xsuaa: OAuth2XSUAAAuthenticationConfiguration? = null
}

class BasicAuthenticationConfiguration (
    val username: String,
    val password: String,
)

class OAuth2XSUAAAuthenticationConfiguration {
    var tokenServiceURL: String? = null
    var client_id: String? = null
    var client_secret: String? = null
    var scopes: String? = null
}

class TargetAuthenticatorConfiguration : AuthenticatorConfiguration() {
    var id: String? = null
}