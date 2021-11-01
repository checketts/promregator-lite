package com.github.promregator.auth

import com.github.promregator.config.AuthenticatorConfiguration
import com.github.promregator.config.PromregatorConfiguration
import org.springframework.stereotype.Service

private val log = mu.KotlinLogging.logger {  }

/**
 * This class controls the target-dependent set of AuthenticationEnrichers
 */
@Service
class AuthenticatorService(promregatorConfiguration: PromregatorConfiguration) {
    private val mapping: MutableMap<String, AuthenticationEnricher> = HashMap()
    private var globalAuthenticationEnricher: AuthenticationEnricher

    /**
     * retrieves the AuthenticationEnricher identified by its ID (note: this is
     * *NOT* the target!)
     *
     * @param authId
     * the authenticatorId as specified in the configuration definition
     * @return the AuthenticationEnricher, which is associated to the given identifier, or `null` in case
     * that no such AuthenticationEnricher exists.
     */
    fun getAuthenticationEnricherById(authId: String): AuthenticationEnricher? {
        return mapping.getOrDefault(authId, globalAuthenticationEnricher)
    }

    init {
        val authConfig = promregatorConfiguration.authenticator
        globalAuthenticationEnricher = create(authConfig) ?: NullEnricher()

        for (tac in promregatorConfiguration.targetAuthenticators) {
            val id = tac.id
            val ae = create(tac)
            if (ae != null && id != null) {
                mapping[id] = ae
            }
        }
    }

    private fun create(authConfig: AuthenticatorConfiguration): AuthenticationEnricher? {
        var ae: AuthenticationEnricher? = null
        val type = authConfig.type
        if ("OAuth2XSUAA".equals(type, ignoreCase = true)) {
            ae = OAuth2XSUAAEnricher(authConfig.oauth2xsuaa)
        } else if ("none".equals(type, ignoreCase = true) || "null".equals(type, ignoreCase = true)) {
            ae = NullEnricher()
        } else if ("basic".equals(type, ignoreCase = true)) {
            if(authConfig.basic == null) {
                error("auth.basic type is to be used, but config is missing")
            }

            ae = BasicAuthenticationEnricher(authConfig.basic)
        } else {
            log.warn { "Authenticator type $type is unknown; skipping" }
        }
        return ae
    }
}