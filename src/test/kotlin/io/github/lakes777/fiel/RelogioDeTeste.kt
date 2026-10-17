package io.github.lakes777.fiel

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * O relógio da API nos testes: anda junto com o de verdade até um teste [parar] ele numa hora
 * (meio-dia, 23h), e volta ao normal com [soltar]. Os testes rodam um de cada vez, então um
 * relógio só basta.
 */
class RelogioDeTeste : Clock() {
    @Volatile
    private var parado: Instant? = null

    fun parar(em: Instant) {
        parado = em
    }

    fun soltar() {
        parado = null
    }

    override fun instant(): Instant = parado ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

@TestConfiguration(proxyBeanMethods = false)
class ConfiguracaoDoRelogio {
    @Bean
    @Primary
    fun relogioDeTeste() = RelogioDeTeste()
}
