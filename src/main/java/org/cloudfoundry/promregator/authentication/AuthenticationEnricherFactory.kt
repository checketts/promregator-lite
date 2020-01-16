package org.cloudfoundry.promregator.authentication

import org.cloudfoundry.promregator.config.AuthenticatorConfiguration
import org.slf4j.LoggerFactory

object AuthenticationEnricherFactory {
    private val log = LoggerFactory.getLogger(AuthenticationEnricherFactory::class.java)
    fun create(authConfig: AuthenticatorConfiguration): AuthenticationEnricher? {
        var ae: AuthenticationEnricher? = null
        val type = authConfig.type
        if ("OAuth2XSUAA".equals(type, ignoreCase = true)) {
//            ae = OAuth2XSUAAEnricher(authConfig.oauth2xsuaa)
        } else if ("none".equals(type, ignoreCase = true) || "null".equals(type, ignoreCase = true)) {
            ae = NullEnricher()
        } else if ("basic".equals(type, ignoreCase = true)) {
            val username = authConfig.basic?.username ?: throw RuntimeException("Basic auth config is missing username")
            val password = authConfig.basic?.password?: throw RuntimeException("Basic auth config is missing password")
            ae = BasicAuthenticationEnricher(username, password)
        } else {
            log.warn(String.format("Authenticator type %s is unknown; skipping", type))
        }
        return ae
    }
}