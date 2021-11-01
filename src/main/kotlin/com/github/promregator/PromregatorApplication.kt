package com.github.promregator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication
class PromregatorApplication {

}

fun main(args: Array<String>) {
	runApplication<PromregatorApplication>(*args)
}
