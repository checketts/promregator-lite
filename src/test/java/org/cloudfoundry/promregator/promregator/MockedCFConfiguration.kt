package org.cloudfoundry.promregator.promregator

import org.cloudfoundry.promregator.cfaccessor.CFAccessor
import org.mockito.Mockito.mock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MockedCFConfiguration {

    @Bean
    fun cfAccessor() = mock(CFAccessor::class.java)
}