package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.razao.Centavos
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LimitesTest {
    /** Uma hora em Brasília (UTC-3, sem horário de verão desde 2019). */
    private fun brasilia(texto: String): Instant = OffsetDateTime.parse("$texto-03:00").toInstant()

    @Test
    fun `o dia começa à meia-noite de Brasília, não à de UTC`() {
        assertEquals(brasilia("2026-10-16T00:00:00"), Limites.inicioDoDia(brasilia("2026-10-16T23:30:00")))
        // 01h UTC do dia 17 ainda é dia 16 em Brasília
        assertEquals(brasilia("2026-10-16T00:00:00"), Limites.inicioDoDia(Instant.parse("2026-10-17T01:00:00Z")))
    }

    @Test
    fun `a noite vai das 20h às 6h e atravessa a meia-noite`() {
        assertNull(Limites.inicioDaNoite(brasilia("2026-10-16T19:59:59")))
        assertEquals(brasilia("2026-10-16T20:00:00"), Limites.inicioDaNoite(brasilia("2026-10-16T20:00:00")))
        assertEquals(brasilia("2026-10-16T20:00:00"), Limites.inicioDaNoite(brasilia("2026-10-16T23:59:59")))
        assertEquals(brasilia("2026-10-16T20:00:00"), Limites.inicioDaNoite(brasilia("2026-10-17T00:30:00")))
        assertEquals(brasilia("2026-10-16T20:00:00"), Limites.inicioDaNoite(brasilia("2026-10-17T05:59:59")))
        assertNull(Limites.inicioDaNoite(brasilia("2026-10-17T06:00:00")))
    }

    @Test
    fun `o disponível é o menor resto entre o dia e a noite, nunca negativo`() {
        assertEquals(Centavos(500_000), Limites.disponivel(Centavos.ZERO, null))
        assertEquals(Centavos(100_000), Limites.disponivel(Centavos.ZERO, Centavos.ZERO))
        assertEquals(Centavos(20_000), Limites.disponivel(Centavos(480_000), Centavos.ZERO))
        assertEquals(Centavos(30_000), Limites.disponivel(Centavos(70_000), Centavos(70_000)))
        assertEquals(Centavos.ZERO, Limites.disponivel(Centavos(600_000), null))
    }

    @Test
    fun `valores em reais no formato brasileiro`() {
        assertEquals("R$ 0,05", Transferencias.reais(Centavos(5)))
        assertEquals("R$ 1.234,56", Transferencias.reais(Centavos(123_456)))
    }
}
