package nl.vdzon.pvdd.system

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TimeConfiguration {
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()
}
