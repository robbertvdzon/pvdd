package nl.vdzon.pvdd.runtime

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
class AgentRuntimeConfiguration {
    @Bean
    fun agentRuntimeClient(properties: AgentRuntimeProperties, mapper: ObjectMapper) = AgentRuntimeClient(properties, mapper)
}
