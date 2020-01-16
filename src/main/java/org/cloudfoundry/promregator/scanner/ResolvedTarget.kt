package org.cloudfoundry.promregator.scanner

import org.cloudfoundry.promregator.config.InvalidTargetProtocolSpecifiedError
import org.cloudfoundry.promregator.config.Target

data class ResolvedTarget(
        var originalTarget: Target,
        var orgName: String,
        var spaceName: String,
        var applicationName: String,
        var path: String = "/metrics",
        var protocol: String = "https",
        var applicationId: String? = null
) {
    init {
        if ("http" != protocol && "https" != protocol) {
            throw InvalidTargetProtocolSpecifiedError(String.format("Invalid configuration: Target attempted to be configured with non-http(s) protocol: %s", protocol))
        }
    }
}