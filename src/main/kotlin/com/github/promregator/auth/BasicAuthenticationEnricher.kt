package com.github.promregator.auth

import com.github.promregator.config.BasicAuthenticationConfiguration
import org.springframework.http.HttpHeaders

class BasicAuthenticationEnricher(private val authenticatorConfig: BasicAuthenticationConfiguration) : AuthenticationEnricher {
    override fun enrichWithAuthentication(headers: HttpHeaders?) {
        if(headers == null) {
            error("Missing headers")
        }

        headers.setBasicAuth(authenticatorConfig.username, authenticatorConfig.password)
    }
}