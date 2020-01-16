package org.cloudfoundry.promregator.config

import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import java.util.*
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private val logger = KotlinLogging.logger { }

@ConstructorBinding
@ConfigurationProperties(prefix = "promregator")
class PromregatorConfiguration(
        val targets: List<Target> = ArrayList(),
        val authenticator: AuthenticatorConfiguration? = null,
        val targetAuthenticators: List<TargetAuthenticatorConfiguration> = ArrayList(),
        val internal: InternalConfig = InternalConfig()
)

data class Target(
        val orgName: String? = null,
        val orgRegex: String? = null,
        val spaceName: String? = null,
        val spaceRegex: String? = null,
        val applicationName: String? = null,
        val applicationRegex: String? = null,
        val path: String = "/metrics",
        val protocol: String = "https",
        val authenticatorId: String? = null,
        val preferredRouteRegex: List<String> = listOf(),
        val preferredRouteRegexPatterns: List<Pattern> = cachePreferredRouteRegexPatterns(preferredRouteRegex)
) {
    init {
        if ("http" != protocol && "https" != protocol) {
            throw InvalidTargetProtocolSpecifiedError("Invalid configuration: Target attempted to be configured with non-http(s) protocol: $protocol")
        }
    }

    companion object {
        private fun cachePreferredRouteRegexPatterns(regexStringList: List<String>): List<Pattern> {
            return regexStringList.mapNotNull {
                try {
                    Pattern.compile(it)
                } catch (e: PatternSyntaxException) {
                    logger.warn(e) { "Invalid preferredRouteRegex '$it' detected. Fix your configuration; until then, the regex will be ignored" }
                    null
                }
            }
        }
    }
}

data class InternalConfig(
        val performPrecheckAPIVersion: Boolean = true
)

open class AuthenticatorConfiguration(
        var type: String? = null,
        var basic: BasicAuthenticationConfiguration? = null,
        var oauth2xsuaa: OAuth2XSUAAAuthenticationConfiguration? = null
)

data class TargetAuthenticatorConfiguration(
        var id: String? = null
) : AuthenticatorConfiguration()

class BasicAuthenticationConfiguration(
        var username: String? = null,
        var password: String? = null
)

class OAuth2XSUAAAuthenticationConfiguration(
        var tokenServiceURL: String? = null,
        var client_id: String? = null,
        var client_secret: String? = null,
        var scopes: String? = null
)