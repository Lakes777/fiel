package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.razao.Razao
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** O que a carteira já usou dos limites e quanto ainda pode mandar agora (GET /limites). */
data class SituacaoDosLimites(
    val diaLimiteCentavos: Long,
    val diaUsadoCentavos: Long,
    val noiteLimiteCentavos: Long,
    val noiteUsadoCentavos: Long,
    /** Se agora é noite (20h às 6h em Brasília): só então o limite noturno vale. */
    val ehNoite: Boolean,
    val disponivelCentavos: Long,
)

/**
 * Todo dinheiro que sai de uma carteira passa por aqui antes do lançamento: transferência e
 * pagamento de cobrança contam juntos nos [Limites]. Separados, daria para mandar o limite noturno
 * inteiro por transferência e esvaziar o resto da carteira pagando cobranças.
 */
@Service
class Saidas(
    private val jdbc: JdbcClient,
    private val razao: Razao,
) {
    /**
     * Só depois de [Razao.travar] a carteira [de]: com ela travada, nenhuma outra saída muda estes
     * números entre a conferência e o lançamento.
     */
    fun exigirSaldoELimite(
        de: Long,
        valor: Centavos,
        agora: Instant,
    ) {
        val saldo = razao.saldo(de)
        if (saldo < valor) throw RegraDeNegocio("saldo insuficiente: você tem ${reais(saldo)}")
        val noDia = enviadoDesde(de, Limites.inicioDoDia(agora))
        val naNoite = Limites.inicioDaNoite(agora)?.let { enviadoDesde(de, it) }
        val disponivel = Limites.disponivel(noDia, naNoite)
        if (disponivel < valor) {
            val qual = if (naNoite != null && Limites.NOITE - naNoite < Limites.DIA - noDia) "noturno" else "diário"
            throw RegraDeNegocio("passa do limite $qual; ainda dá para mandar ${reais(disponivel)}")
        }
    }

    /**
     * Quanto a carteira [conta] ainda pode mandar agora, para a tela mostrar antes de a pessoa
     * tentar. A noite só aparece quando é noite: de dia, a próxima noite começa do zero.
     */
    fun situacao(
        conta: Long,
        agora: Instant,
    ): SituacaoDosLimites {
        val noDia = enviadoDesde(conta, Limites.inicioDoDia(agora))
        val naNoite = Limites.inicioDaNoite(agora)?.let { enviadoDesde(conta, it) }
        return SituacaoDosLimites(
            diaLimiteCentavos = Limites.DIA.valor,
            diaUsadoCentavos = noDia.valor,
            noiteLimiteCentavos = Limites.NOITE.valor,
            noiteUsadoCentavos = naNoite?.valor ?: 0,
            ehNoite = naNoite != null,
            disponivelCentavos = Limites.disponivel(noDia, naNoite).valor,
        )
    }

    private fun enviadoDesde(
        conta: Long,
        inicio: Instant,
    ) = Centavos(
        jdbc
            .sql(
                """
                select (select coalesce(sum(valor), 0) from transferencia
                        where de_conta = :conta and criada_em >= :inicio)
                     + (select coalesce(sum(valor), 0) from cobranca
                        where pagador_conta = :conta and paga_em >= :inicio)
                """.trimIndent(),
            ).param("conta", conta)
            .param("inicio", OffsetDateTime.ofInstant(inicio, ZoneOffset.UTC))
            .query(Long::class.java)
            .single(),
    )

    companion object {
        /** 123456 centavos -> "R$ 1.234,56". */
        fun reais(valor: Centavos): String =
            "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", valor.valor / CENTAVOS_POR_REAL)

        private const val CENTAVOS_POR_REAL = 100.0
    }
}
