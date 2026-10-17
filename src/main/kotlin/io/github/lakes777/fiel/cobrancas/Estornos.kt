package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.avisos.Avisos
import io.github.lakes777.fiel.chaves.mascararNome
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.eventos.Eventos
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.razao.NovaPartida
import io.github.lakes777.fiel.razao.NovoLancamento
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import io.github.lakes777.fiel.transferencias.Saidas
import io.github.lakes777.fiel.transferencias.Transferencias
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat

data class PedidoDeEstorno(
    @Positive val valorCentavos: Long,
    @Size(min = 1, max = 140) val motivo: String? = null,
)

data class EstornoFeito(
    val id: Long,
    val cobrancaId: Long,
    val valorCentavos: Long,
    val motivo: String?,
    val em: Instant,
)

/**
 * Estorno: só quem recebeu uma cobrança devolve, tudo ou em partes, sem prazo. Quem pagou não pede
 * estorno pela API (não há contestação aqui; ver README).
 *
 * O estorno NÃO conta nos limites diário e noturno de quem devolve (um lojista no limite da noite
 * ainda consegue devolver o dinheiro de um cliente) e NÃO devolve limite a quem pagou (senão pagar,
 * receber o estorno e pagar de novo giraria acima do limite). [Saidas] soma só transferências e
 * pagamentos, então as duas coisas valem sem código a mais.
 *
 * Numa transação, como o pagamento: trava a cobrança, depois as carteiras em ordem de id. A ordem
 * das travas é a mesma do pagamento (cobrança e depois contas), então os dois não se esperam em
 * círculo. A Idempotency-Key vale por cobrança, e a trava da cobrança já faz as cópias de um mesmo
 * pedido esperarem a primeira.
 */
@Service
class Estornos(
    private val banco: CobrancasNoBanco,
    private val jdbc: JdbcClient,
    private val razao: Razao,
    private val avisos: Avisos,
    private val eventos: Eventos,
    private val relogio: Clock,
    transacoes: PlatformTransactionManager,
) {
    private val transacao = TransactionTemplate(transacoes)

    /** O estorno e se ele já existia (um pedido repetido). */
    data class Resultado(
        val estorno: EstornoFeito,
        val repetido: Boolean,
    )

    fun estornar(
        usuarioId: Long,
        cobrancaId: Long,
        chaveDeIdempotencia: String,
        pedido: PedidoDeEstorno,
    ): Resultado {
        Transferencias.exigirChaveDeIdempotencia(chaveDeIdempotencia)
        val motivo = pedido.motivo?.trim()?.ifEmpty { null }
        val resumo = resumir(pedido.valorCentavos, motivo)
        return checkNotNull(
            transacao.execute {
                val travada =
                    banco.doLojista(usuarioId, cobrancaId, travar = true)
                        ?: throw NaoEncontrado("cobrança não encontrada")
                // A soma de novo, já com a trava: a que veio junto pode não ter o estorno de quem soltou a trava agora
                val cobranca = travada.copy(estornado = banco.estornado(cobrancaId))
                val anterior = anterior(cobrancaId, chaveDeIdempotencia)
                if (anterior != null) {
                    if (anterior.second != resumo) {
                        throw RegraDeNegocio("esta Idempotency-Key já foi usada em outro pedido; gere uma nova")
                    }
                    Resultado(anterior.first, repetido = true)
                } else {
                    Resultado(
                        novo(cobranca, chaveDeIdempotencia, resumo, Centavos(pedido.valorCentavos), motivo),
                        false,
                    )
                }
            },
        )
    }

    private fun novo(
        cobranca: Cobranca,
        chaveDeIdempotencia: String,
        resumo: String,
        valor: Centavos,
        motivo: String?,
    ): EstornoFeito {
        cobranca.exigirEstornavel(valor, agora())
        val pagador = checkNotNull(cobranca.pagadorConta)
        razao.travar(listOf(cobranca.recebedorConta, pagador))
        val saldo = razao.saldo(cobranca.recebedorConta)
        if (saldo < valor) throw RegraDeNegocio("saldo insuficiente para devolver: você tem ${Saidas.reais(saldo)}")

        val agora = agora()
        val (de, para) = mascararNome(cobranca.recebedor) to mascararNome(nomeDoDono(pagador))
        val lancamento =
            razao.registrar(
                NovoLancamento(
                    "Estorno de $de a $para: ${cobranca.descricao}".take(NovoLancamento.MAX_DESCRICAO),
                    listOf(NovaPartida(cobranca.recebedorConta, -valor), NovaPartida(pagador, valor)),
                ),
            )
        val id = gravar(cobranca.id, chaveDeIdempotencia, resumo, valor, motivo, lancamento.id, agora)
        val feito = EstornoFeito(id, cobranca.id, valor.valor, motivo, agora)
        // Na mesma transação do estorno (outbox), como o aviso do pagamento
        val estornado = cobranca.estornado + valor
        avisos.registrar(
            cobranca.recebedorConta,
            "cobranca.estornada",
            mapOf(
                "cobranca" to
                    cobranca.dadosDoAviso() +
                    mapOf(
                        "estado" to if (estornado == cobranca.valor) "estornada" else "parcialmente_estornada",
                        "estornadoCentavos" to estornado.valor,
                    ),
                "estorno" to
                    mapOf("id" to id, "valorCentavos" to valor.valor, "motivo" to motivo, "em" to agora.toString()),
            ),
            agora,
        )
        eventos.publicar(cobranca.recebedorConta, "carteira")
        eventos.publicar(cobranca.recebedorConta, "cobranca", cobranca.id)
        eventos.publicar(pagador, "carteira")
        return feito
    }

    /**
     * A linha do estorno. O gatilho do banco recusa (23514) o que não cabe mais na cobrança: com a
     * soma lida depois da trava isso não deve acontecer, mas se acontecer vira 422, não 500.
     */
    private fun gravar(
        cobrancaId: Long,
        chaveDeIdempotencia: String,
        resumo: String,
        valor: Centavos,
        motivo: String?,
        lancamento: Long,
        agora: Instant,
    ): Long =
        try {
            jdbc
                .sql(
                    """
                    insert into estorno (cobranca_id, chave_idempotencia, resumo_do_pedido, valor, motivo,
                                         lancamento_id, criado_em)
                    values (:cobranca, :chave, :resumo, :valor, :motivo, :lancamento, :em)
                    returning id
                    """.trimIndent(),
                ).param("cobranca", cobrancaId)
                .param("chave", chaveDeIdempotencia)
                .param("resumo", resumo)
                .param("valor", valor.valor)
                .param("motivo", motivo)
                .param("lancamento", lancamento)
                .param("em", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .query(Long::class.java)
                .single()
        } catch (e: DataIntegrityViolationException) {
            if ((e.cause as? SQLException)?.sqlState != "23514") throw e
            throw RegraDeNegocio("o estorno não cabe mais nesta cobrança; confira quanto falta devolver")
        }

    /** Os estornos de uma cobrança do lojista, do mais antigo ao mais novo. */
    fun daCobranca(
        usuarioId: Long,
        cobrancaId: Long,
    ): List<EstornoFeito> {
        banco.doLojista(usuarioId, cobrancaId) ?: throw NaoEncontrado("cobrança não encontrada")
        return jdbc
            .sql("select id, cobranca_id, valor, motivo, criado_em from estorno where cobranca_id = :id order by id")
            .param("id", cobrancaId)
            .query { rs, _ -> rs.estorno() }
            .list()
    }

    private fun anterior(
        cobrancaId: Long,
        chaveDeIdempotencia: String,
    ): Pair<EstornoFeito, String>? =
        jdbc
            .sql(
                """
                select id, cobranca_id, valor, motivo, criado_em, resumo_do_pedido
                from estorno where cobranca_id = :cobranca and chave_idempotencia = :chave
                """.trimIndent(),
            ).param("cobranca", cobrancaId)
            .param("chave", chaveDeIdempotencia)
            .query { rs, _ -> rs.estorno() to rs.getString("resumo_do_pedido") }
            .optional()
            .orElse(null)

    private fun nomeDoDono(conta: Long): String =
        jdbc
            .sql("select u.nome from conta c join usuario u on u.id = c.dono_id where c.id = :conta")
            .param("conta", conta)
            .query(String::class.java)
            .single()

    private fun ResultSet.estorno() =
        EstornoFeito(
            getLong("id"),
            getLong("cobranca_id"),
            getLong("valor"),
            getString("motivo"),
            getObject("criado_em", OffsetDateTime::class.java).toInstant(),
        )

    /** A hora com a precisão do Postgres (microssegundos): a resposta repetida mostra a mesma hora da primeira. */
    private fun agora() = relogio.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        /** O que identifica o pedido: a mesma chave de idempotência com outro resumo é outro pedido. */
        fun resumir(
            valorCentavos: Long,
            motivo: String?,
        ): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("$valorCentavos\n${motivo.orEmpty()}".toByteArray()),
            )
    }
}

@RestController
class EstornosController(
    private val estornos: Estornos,
    private val limites: LimitesDeUso,
) {
    /** Idempotency-Key obrigatória, como na transferência: sem ela, o app que repete devolveria duas vezes. */
    @PostMapping("/cobrancas/{id}/estornos")
    fun estornar(
        @PathVariable id: Long,
        @RequestHeader("Idempotency-Key", required = false) chaveDeIdempotencia: String?,
        @Valid @RequestBody pedido: PedidoDeEstorno,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): ResponseEntity<EstornoFeito> {
        limites.estorno.registrar(usuario.username)
        if (chaveDeIdempotencia == null) throw PedidoInvalido("falta o cabeçalho Idempotency-Key")
        val (feito, repetido) = estornos.estornar(usuario.id, id, chaveDeIdempotencia, pedido)
        val resposta = ResponseEntity.status(HttpStatus.CREATED)
        if (repetido) resposta.header("Idempotent-Replayed", "true")
        return resposta.body(feito)
    }

    @GetMapping("/cobrancas/{id}/estornos")
    fun daCobranca(
        @PathVariable id: Long,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = estornos.daCobranca(usuario.id, id)
}
