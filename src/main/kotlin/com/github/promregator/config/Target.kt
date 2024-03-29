package com.github.promregator.config

import java.util.regex.PatternSyntaxException
import java.util.regex.Pattern

private val log = mu.KotlinLogging.logger {  }

data class Target(
        val orgName: String? = null,
        val orgRegex: String? = null,
        val spaceName: String? = null,
        val spaceRegex: String? = null,
        val applicationName: String? = null,
        val applicationRegex: String? = null,
        val path: String = "/metrics",
        val kubernetesAnnotations: Boolean = false,
        val protocol: String = "https",
        val authenticatorId: String? = null,
        val preferredRouteRegex: List<String> = listOf(),
        val internalRoutePort: Int = 0
) {
    val cachedPreferredRouteRegexPattern: List<Pattern>


    init {
        if ("http" != protocol && "https" != protocol) {
            throw InvalidTargetProtocolSpecifiedError(String.format("Invalid configuration: Target attempted to be configured with non-http(s) protocol: %s", protocol))
        }

        cachedPreferredRouteRegexPattern = preferredRouteRegex.mapNotNull { routeRegex ->
            try {
                Pattern.compile(routeRegex)
            } catch (e: PatternSyntaxException) {
                log.warn(e) { "Invalid preferredRouteRegex '$routeRegex' detected. Fix your configuration; until then, the regex will be ignored" }
                null
            }
        }
    }

}