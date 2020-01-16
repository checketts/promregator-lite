package org.cloudfoundry.promregator.authentication

import org.springframework.web.reactive.function.client.WebClient
import java.util.function.Consumer

class NullEnricher : AuthenticationEnricher {
    override fun lookupEnrichAuthentication(): Consumer<WebClient.Builder> {
        //No action taken, this is a noop enricher
        return Consumer {         }
    }
}