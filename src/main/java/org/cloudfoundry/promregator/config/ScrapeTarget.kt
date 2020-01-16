package org.cloudfoundry.promregator.config

data class ScrapeTarget(
        val applicationId: String,
        val applicationName: String,
        val scrapeUrl: String,
        val instanceNumber: String,
        val authId: String? = null
)