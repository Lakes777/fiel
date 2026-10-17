package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.erros.Conflito
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.PedidoInvalido
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

data class Webhook(
    val url: String,
)

/** Só na hora de cadastrar: o segredo não aparece de novo (trocar a URL gera outro). */
data class WebhookNovo(
    val url: String,
    val segredo: String,
)

data class AvisoDoLojista(
    val id: Long,
    val eventoId: UUID,
    val tipo: String,
    val estado: String,
    val tentativas: Int,
    val criadoEm: Instant,
    val proximaTentativaEm: Instant?,
    val entregueEm: Instant?,
    val ultimaResposta: String?,
)

/**
 * O lado de dentro dos avisos: o registro (outbox), o endereço de cada usuário, o histórico e o
 * reenvio. Quem manda pela rede é o [Entregador].
 */
@Service
class Avisos(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val destino: Destino,
    private val relogio: Clock,
    transacoes: PlatformTransactionManager,
) {
    private val transacao = TransactionTemplate(transacoes)
    private val aleatorio = SecureRandom()

    /**
     * Grava um aviso para o dono da carteira [contaId], se ele tiver webhook. Chamado DENTRO da
     * transação do fato: se ela desfizer, o aviso some junto (é o outbox).
     */
    fun registrar(
        contaId: Long,
        tipo: String,
        dados: Map<String, Any?>,
        agora: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into aviso (codigo, usuario_id, tipo, dados, criado_em, proxima_em)
                select :codigo, c.dono_id, :tipo, cast(:dados as jsonb), :agora, :agora
                from conta c join webhook w on w.usuario_id = c.dono_id
                where c.id = :conta
                """.trimIndent(),
            ).param("codigo", UUID.randomUUID())
            .param("tipo", tipo)
            .param("dados", json.writeValueAsString(dados))
            .param("agora", utc(agora))
            .param("conta", contaId)
            .update()
    }

    /** Cadastra ou troca o endereço; sempre com segredo novo, que só aparece nesta resposta. */
    fun definirWebhook(
        usuarioId: Long,
        url: String,
    ): WebhookNovo {
        val conferida = destino.conferir(url).toString()
        val segredo = HexFormat.of().formatHex(ByteArray(BYTES_DO_SEGREDO).also(aleatorio::nextBytes))
        jdbc
            .sql(
                """
                insert into webhook (usuario_id, url, segredo) values (:usuario, :url, :segredo)
                on conflict (usuario_id) do update set url = excluded.url, segredo = excluded.segredo, criado_em = now()
                """.trimIndent(),
            ).param("usuario", usuarioId)
            .param("url", conferida)
            .param("segredo", segredo)
            .update()
        return WebhookNovo(conferida, segredo)
    }

    fun webhook(usuarioId: Long): Webhook =
        jdbc
            .sql("select url from webhook where usuario_id = :usuario")
            .param("usuario", usuarioId)
            .query { rs, _ -> Webhook(rs.getString("url")) }
            .optional()
            .orElseThrow { NaoEncontrado("nenhum endereço de aviso cadastrado") }

    /** Sem endereço, os pendentes não têm para onde ir: vão para a fila de falhas (dá para reenviar depois). */
    fun removerWebhook(usuarioId: Long) {
        transacao.executeWithoutResult {
            val removidos =
                jdbc.sql("delete from webhook where usuario_id = :usuario").param("usuario", usuarioId).update()
            if (removidos == 0) throw NaoEncontrado("nenhum endereço de aviso cadastrado")
            jdbc
                .sql(
                    """
                    update aviso set estado = 'falhou', proxima_em = null, ultima_resposta = 'endereço de aviso removido'
                    where usuario_id = :usuario and estado = 'pendente'
                    """.trimIndent(),
                ).param("usuario", usuarioId)
                .update()
        }
    }

    /** Os 100 mais recentes; [estado] filtra (a fila de falhas é estado=falhou). */
    fun doUsuario(
        usuarioId: Long,
        estado: String?,
    ): List<AvisoDoLojista> {
        if (estado != null && estado !in ESTADOS) throw PedidoInvalido("estado é pendente, entregue ou falhou")
        return jdbc
            .sql(
                """
                select id, codigo, tipo, estado, tentativas, criado_em, proxima_em, entregue_em, ultima_resposta
                from aviso where usuario_id = :usuario and (cast(:estado as text) is null or estado = :estado)
                order by id desc limit 100
                """.trimIndent(),
            ).param("usuario", usuarioId)
            .param("estado", estado)
            .query { rs, _ -> rs.aviso() }
            .list()
    }

    /**
     * Manda de novo um aviso já entregue ou que falhou: volta a pendente, com as tentativas do zero.
     * O conteúdo é o mesmo (mesmo Fiel-Evento-Id), então um lojista que já processou descarta.
     */
    fun reenviar(
        usuarioId: Long,
        id: Long,
    ): AvisoDoLojista =
        checkNotNull(
            transacao.execute {
                val estado =
                    jdbc
                        .sql("select estado from aviso where id = :id and usuario_id = :usuario for update")
                        .param("id", id)
                        .param("usuario", usuarioId)
                        .query(String::class.java)
                        .optional()
                        .orElseThrow { NaoEncontrado("aviso não encontrado") }
                if (estado == "pendente") throw Conflito("este aviso já está na fila de envio")
                // Sem endereço, o aviso ficaria pendente sem ter para onde ir
                val temEndereco =
                    jdbc
                        .sql("select exists (select 1 from webhook where usuario_id = :usuario)")
                        .param("usuario", usuarioId)
                        .query(Boolean::class.java)
                        .single()
                if (!temEndereco) throw Conflito("cadastre um endereço de aviso antes de reenviar")
                jdbc
                    .sql(
                        """
                        update aviso set estado = 'pendente', tentativas = 0, proxima_em = :agora, entregue_em = null
                        where id = :id
                        returning id, codigo, tipo, estado, tentativas, criado_em, proxima_em, entregue_em, ultima_resposta
                        """.trimIndent(),
                    ).param("agora", utc(relogio.instant().truncatedTo(ChronoUnit.MICROS)))
                    .param("id", id)
                    .query { rs, _ -> rs.aviso() }
                    .single()
            },
        )

    private fun ResultSet.aviso() =
        AvisoDoLojista(
            id = getLong("id"),
            eventoId = getObject("codigo", UUID::class.java),
            tipo = getString("tipo"),
            estado = getString("estado"),
            tentativas = getInt("tentativas"),
            criadoEm = checkNotNull(instante("criado_em")),
            proximaTentativaEm = instante("proxima_em"),
            entregueEm = instante("entregue_em"),
            ultimaResposta = getString("ultima_resposta"),
        )

    private fun ResultSet.instante(coluna: String) = getObject(coluna, OffsetDateTime::class.java)?.toInstant()

    private fun utc(instante: Instant) = OffsetDateTime.ofInstant(instante, ZoneOffset.UTC)

    companion object {
        private const val BYTES_DO_SEGREDO = 32
        private val ESTADOS = setOf("pendente", "entregue", "falhou")
    }
}
