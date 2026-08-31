package nl.vdzon.pvdd

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic
class PvddApplication

fun main(args: Array<String>) {
    runApplication<PvddApplication>(*args)
}
