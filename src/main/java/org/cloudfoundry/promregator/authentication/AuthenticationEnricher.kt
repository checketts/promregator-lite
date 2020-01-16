package org.cloudfoundry.promregator.authentication

import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import java.util.function.Consumer

interface AuthenticationEnricher {
    fun lookupEnrichAuthentication(): Consumer<WebClient.Builder>
}