package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.razao.Centavos
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Quanto uma carteira pode mandar: até [DIA] por dia e, à noite (das 20h às 6h, no horário de
 * Brasília), até [NOITE] nesse período. É o desenho da regra do Banco Central para transferências
 * noturnas: quem rouba o celular de madrugada não esvazia a conta.
 *
 * O dia começa à meia-noite; a noite começa às 20h e acaba às 6h do dia seguinte. Entre 0h e 6h
 * valem os dois: o que saiu desde as 20h de ontem conta na noite, e só o de depois da meia-noite no dia.
 */
object Limites {
    val DIA = Centavos(500_000)
    val NOITE = Centavos(100_000)
    val FUSO: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val INICIO_DA_NOITE = LocalTime.of(20, 0)
    private val FIM_DA_NOITE = LocalTime.of(6, 0)

    fun inicioDoDia(agora: Instant): Instant =
        agora
            .atZone(FUSO)
            .toLocalDate()
            .atStartOfDay(FUSO)
            .toInstant()

    /** Quando começou a noite em que [agora] está, ou null se é dia. */
    fun inicioDaNoite(agora: Instant): Instant? {
        val local = agora.atZone(FUSO)
        val hora = local.toLocalTime()
        return when {
            hora >= INICIO_DA_NOITE -> local.toLocalDate().atTime(INICIO_DA_NOITE)
            hora < FIM_DA_NOITE -> local.toLocalDate().minusDays(1).atTime(INICIO_DA_NOITE)
            else -> null
        }?.atZone(FUSO)?.toInstant()
    }

    /**
     * O que ainda dá para mandar agora, dado o que já saiu no dia e na noite. O menor dos dois
     * restos; nunca negativo.
     */
    fun disponivel(
        noDia: Centavos,
        naNoite: Centavos?,
    ): Centavos {
        val restoDoDia = DIA - noDia
        val resto = if (naNoite == null) restoDoDia else minOf(restoDoDia, NOITE - naNoite)
        return maxOf(resto, Centavos.ZERO)
    }
}
