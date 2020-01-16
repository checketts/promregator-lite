package org.cloudfoundry.promregator

import io.micrometer.core.instrument.config.MeterFilter
import org.cloudfoundry.promregator.config.CloudFoundryConfiguration
import org.cloudfoundry.promregator.config.PromregatorConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import reactor.blockhound.BlockHound
import java.time.Clock


@SpringBootApplication
@EnableConfigurationProperties(PromregatorConfiguration::class, CloudFoundryConfiguration::class)
@EnableScheduling
class PromregatorApplication {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun renameRegionTagMeterFilter(): MeterFilter {
        return MeterFilter.replaceTagValues("uri",
                java.util.function.Function<String, String> {
                    if (it.contains("?q=")) it.substring(0, it.indexOf("?q=")) + "?q=_query_" else it
                })

    }
}

fun main(args: Array<String>) {
    BlockHound.install()
    runApplication<PromregatorApplication>(*args)
}
