package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.avisos.Avisos
import io.github.lakes777.fiel.chaves.mascararNome
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.razao.NovaPartida
import io.github.lakes777.fiel.razao.NovoLancamento
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat

data class PedidoDeTransferencia(
    @NotBlank @Size(max = 254) val chave: String,
    @Positive val valorCentavos: Long,
)

/** O que quem mandou vê: o recebedor só pelo nome mascarado, como na consulta da chave. */
data class TransferenciaFeita(
    val id: Long,
    val valorCentavos: Long,
    val para: String,
    val em: Instant,
)

/**
 * Transferência por chave, da carteira de quem está logado para a carteira dona da chave.
 *
 * Tudo numa transação, nesta ordem:
 *  1. trava a Idempotency-Key (pg_advisory_xact_lock): o mesmo pedido mandado duas vezes ao mesmo
 *     tempo (o app repetiu porque a rede caiu) espera o primeiro terminar e devolve o resultado dele;
 *  2. trava as duas contas em ordem de id, e só então confere saldo e limites: ninguém gasta o mesmo
 *     dinheiro duas vezes entre a conferência e o lançamento;
 *  3. grava o lançamento e a transferência. O banco confere de novo (carteira nunca negativa,
 *     lançamento igual à transferência) e é a palavra final.
 *
 * Uma transferência recusada (saldo, limite) não fica guardada: repetir a mesma chave tenta de novo.
 */
@Service
class Transferencias(
    private val jdbc: JdbcClient,
    private val razao: Razao,
    private val saidas: Saidas,
    private val avisos: Avisos,
    private val relogio: Clock,
    transacoes: PlatformTransactionManager,
) {
    private val transacao = TransactionTemplate(transacoes)

    /** A transferência e se ela já existia (um pedido repetido). */
    data class Resultado(
        val transferencia: TransferenciaFeita,
        val repetida: Boolean,
    )

    fun transferir(
        usuario: UsuarioLogado,
        chaveDeIdempotencia: String,
        pedido: PedidoDeTransferencia,
    ): Resultado {
        if (!CHAVE_DE_IDEMPOTENCIA.matches(chaveDeIdempotencia)) {
            throw PedidoInvalido("a Idempotency-Key precisa ter de 1 a 100 caracteres visíveis (sem espaço nem acento)")
        }
        val chave = pedido.chave.trim().lowercase()
        val resumo = resumir(chave, pedido.valorCentavos)
        return checkNotNull(
            transacao.execute {
                jdbc
                    .sql("select pg_advisory_xact_lock(hashtextextended(:usuario || ':' || :chave, 0))")
                    .param("usuario", usuario.id.toString())
                    .param("chave", chaveDeIdempotencia)
                    .query { _, _ -> }
                    .single()
                val anterior = anterior(usuario.id, chaveDeIdempotencia)
                if (anterior != null) {
                    if (anterior.second != resumo) {
                        throw RegraDeNegocio("esta Idempotency-Key já foi usada em outro pedido; gere uma nova")
                    }
                    Resultado(anterior.first, repetida = true)
                } else {
                    Resultado(nova(usuario, chaveDeIdempotencia, resumo, chave, Centavos(pedido.valorCentavos)), false)
                }
            },
        )
    }

    private fun nova(
        usuario: UsuarioLogado,
        chaveDeIdempotencia: String,
        resumo: String,
        chave: String,
        valor: Centavos,
    ): TransferenciaFeita {
        val de =
            jdbc
                .sql("select id from conta where dono_id = :dono and tipo = 'carteira'")
                .param("dono", usuario.id)
                .query(Long::class.java)
                .single()
        val (para, nomeDoRecebedor) =
            jdbc
                .sql(
                    """
                    select k.conta_id, u.nome
                    from chave k join conta c on c.id = k.conta_id join usuario u on u.id = c.dono_id
                    where k.valor = :valor
                    """.trimIndent(),
                ).param("valor", chave)
                .query { rs, _ -> rs.getLong("conta_id") to rs.getString("nome") }
                .optional()
                .orElseThrow { NaoEncontrado("chave não encontrada") }
        if (para == de) throw PedidoInvalido("esta chave é da sua própria carteira")

        razao.travar(listOf(de, para))
        val agora = agora()
        saidas.exigirSaldoELimite(de, valor, agora)

        val recebedor = mascararNome(nomeDoRecebedor)
        val lancamento =
            razao.registrar(
                NovoLancamento(
                    "Transferência de ${mascararNome(usuario.nome)} para $recebedor".take(NovoLancamento.MAX_DESCRICAO),
                    listOf(NovaPartida(de, -valor), NovaPartida(para, valor)),
                ),
            )
        val id =
            jdbc
                .sql(
                    """
                    insert into transferencia (usuario_id, chave_idempotencia, resumo_do_pedido,
                                               de_conta, para_conta, valor, lancamento_id, criada_em)
                    values (:usuario, :chave, :resumo, :de, :para, :valor, :lancamento, :em)
                    returning id
                    """.trimIndent(),
                ).param("usuario", usuario.id)
                .param("chave", chaveDeIdempotencia)
                .param("resumo", resumo)
                .param("de", de)
                .param("para", para)
                .param("valor", valor.valor)
                .param("lancamento", lancamento.id)
                .param("em", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .query(Long::class.java)
                .single()
        val feita = TransferenciaFeita(id, valor.valor, recebedor, agora)
        avisarRecebedor(para, feita, mascararNome(usuario.nome))
        return feita
    }

    /** Na mesma transação da transferência (outbox), para o recebedor que tiver webhook. */
    private fun avisarRecebedor(
        para: Long,
        feita: TransferenciaFeita,
        de: String,
    ) = avisos.registrar(
        para,
        "transferencia.recebida",
        mapOf(
            "transferencia" to
                mapOf("id" to feita.id, "valorCentavos" to feita.valorCentavos, "em" to feita.em.toString()),
            "de" to de,
        ),
        feita.em,
    )

    private fun anterior(
        usuarioId: Long,
        chaveDeIdempotencia: String,
    ): Pair<TransferenciaFeita, String>? =
        jdbc
            .sql(
                """
                select t.id, t.valor, t.criada_em, t.resumo_do_pedido, u.nome
                from transferencia t join conta c on c.id = t.para_conta join usuario u on u.id = c.dono_id
                where t.usuario_id = :usuario and t.chave_idempotencia = :chave
                """.trimIndent(),
            ).param("usuario", usuarioId)
            .param("chave", chaveDeIdempotencia)
            .query { rs, _ -> rs.transferencia() to rs.getString("resumo_do_pedido") }
            .optional()
            .orElse(null)

    private fun ResultSet.transferencia() =
        TransferenciaFeita(
            getLong("id"),
            getLong("valor"),
            mascararNome(getString("nome")),
            getObject("criada_em", OffsetDateTime::class.java).toInstant(),
        )

    /** A hora com a precisão do Postgres (microssegundos): a resposta repetida mostra a mesma hora da primeira. */
    private fun agora() = relogio.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        /** ASCII visível, sem espaço: cabe um UUID, um ULID ou o que o cliente preferir. */
        private val CHAVE_DE_IDEMPOTENCIA = Regex("[\\x21-\\x7E]{1,100}")

        /** O que identifica o pedido: a mesma chave de idempotência com outro resumo é outro pedido. */
        fun resumir(
            chave: String,
            valorCentavos: Long,
        ): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("$chave\n$valorCentavos".toByteArray()),
            )
    }
}

@RestController
class TransferenciasController(
    private val transferencias: Transferencias,
    private val limites: LimitesDeUso,
) {
    /**
     * A Idempotency-Key é obrigatória: sem ela, um app que repete o pedido depois de uma queda de
     * rede mandaria o dinheiro duas vezes. Um pedido repetido devolve a mesma transferência, com o
     * cabeçalho Idempotent-Replayed.
     */
    @PostMapping("/transferencias")
    fun transferir(
        @RequestHeader("Idempotency-Key", required = false) chaveDeIdempotencia: String?,
        @Valid @RequestBody pedido: PedidoDeTransferencia,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): ResponseEntity<TransferenciaFeita> {
        // Conta antes de tudo: transferir para chaves inventadas também serviria para descobrir quem tem conta
        limites.transferencia.registrar(usuario.username)
        if (chaveDeIdempotencia == null) throw PedidoInvalido("falta o cabeçalho Idempotency-Key")
        val (feita, repetida) = transferencias.transferir(usuario, chaveDeIdempotencia, pedido)
        val resposta = ResponseEntity.status(HttpStatus.CREATED)
        if (repetida) resposta.header("Idempotent-Replayed", "true")
        return resposta.body(feita)
    }
}
