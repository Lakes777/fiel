package io.github.lakes777.fiel.seguranca

import io.github.lakes777.fiel.erros.MuitasTentativas
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitePorJanelaTest {
    /** Um relógio que só anda quando o teste manda. */
    private class RelogioFalso : Clock() {
        var agora: Instant = Instant.parse("2026-10-16T12:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?) = this

        override fun instant() = agora
    }

    private val relogio = RelogioFalso()
    private val limite = LimitePorJanela(maximo = 3, janela = Duration.ofMinutes(1), relogio = relogio)

    @Test
    fun `deixa passar até o máximo e recusa o seguinte, dizendo quanto falta`() {
        repeat(3) { limite.registrar("ana") }
        relogio.agora = relogio.agora.plusSeconds(20)

        val erro = assertFailsWith<MuitasTentativas> { limite.registrar("ana") }

        assertEquals(40, erro.esperarSegundos)
    }

    @Test
    fun `cada chave tem a sua contagem`() {
        repeat(3) { limite.registrar("ana") }
        repeat(3) { limite.registrar("bob") }
    }

    @Test
    fun `a contagem zera quando a janela passa`() {
        repeat(3) { limite.registrar("ana") }
        assertFailsWith<MuitasTentativas> { limite.registrar("ana") }

        relogio.agora = relogio.agora.plusSeconds(60)

        repeat(3) { limite.registrar("ana") }
    }

    @Test
    fun `esquecer zera na hora`() {
        repeat(3) { limite.registrar("ana") }
        limite.esquecer("ana")
        repeat(3) { limite.registrar("ana") }
    }

    @Test
    fun `o tempo de espera arredonda para cima`() {
        repeat(3) { limite.registrar("ana") }
        relogio.agora = relogio.agora.plusMillis(59_500)

        assertEquals(1, assertFailsWith<MuitasTentativas> { limite.registrar("ana") }.esperarSegundos)
    }

    @Test
    fun `contagens vencidas são apagadas na limpeza seguinte`() {
        repeat(3) { limite.registrar("ana") }
        relogio.agora = relogio.agora.plusSeconds(61)
        limite.registrar("bob")

        assertEquals(1, limite.tamanho)
    }
}
