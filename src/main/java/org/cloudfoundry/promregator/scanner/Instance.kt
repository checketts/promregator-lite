package org.cloudfoundry.promregator.scanner

/**
 * An instance provides a mapping from a target (provided by configuration)
 * to an exact descriptor consisting of the Access URL and the instance identifier,
 * which can be used for scraping.
 */
data class Instance(
        var target: ResolvedTarget,
        var instanceId: String,
        var accessUrl: String? = null
) {

    val instanceNumber: String
        get() {
            return instanceId.split(":")[1]
        }

    val applicationId: String
        get() {
            return instanceId.split(":")[0]
        }

}