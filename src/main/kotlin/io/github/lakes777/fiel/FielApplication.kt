package io.github.lakes777.fiel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
class FielApplication {
    /** A hora da API vem daqui, e não de Instant.now(): os testes trocam o relógio (limite noturno). */
    @Bean
    fun relogio(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<FielApplication>(*args)
}
