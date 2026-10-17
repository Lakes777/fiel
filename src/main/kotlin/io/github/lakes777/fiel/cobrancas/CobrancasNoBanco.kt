package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.chaves.mascararNome
import io.github.lakes777.fiel.erros.Conflito
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.transferencias.Saidas
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Uma linha de cobranca, com o nome do dono da carteira que recebe. */
data class Cobranca(
    val id: Long,
    val codigo: UUID,
    val recebedorConta: Long,
    val recebedor: String,
    val valor: Centavos,
    val descricao: String,
    val estado: String,
    val criadaEm: Instant,
    val expiraEm: Instant,
    val pagadorConta: Long?,
    val pagaEm: Instant?,
    /** A soma dos estornos até agora. */
    val estornado: Centavos = Centavos.ZERO,
) {
    /** Pendente que já venceu aparece como expirada, mesmo antes da tarefa de expiração passar. */
    fun estadoEm(agora: Instant) = if (estado == "pendente" && !agora.isBefore(expiraEm)) "expirada" else estado

    fun qr() = Qr(codigo, valor.valor, mascararNome(recebedor)).texto()

    fun doLojista(agora: Instant) =
        CobrancaDoLojista(
            id,
            valor.valor,
            descricao,
            estadoEm(agora),
            criadaEm,
            expiraEm,
            pagaEm,
            estornado.valor,
            qr(),
        )

    /** O que vai no aviso ao lojista: o suficiente para ele achar o pedido dele, sem o QR. */
    fun dadosDoAviso(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "valorCentavos" to valor.valor,
            "descricao" to descricao,
            "criadaEm" to criadaEm.toString(),
            "expiraEm" to expiraEm.toString(),
        )

    fun pagamento() = PagamentoFeito(valor.valor, descricao, mascararNome(recebedor), checkNotNull(pagaEm))

    /** O CRC não é segurança: um QR com outro valor impresso foi mexido, e não é pago. */
    fun conferir(lido: Qr) {
        if (lido.valorCentavos != valor.valor) {
            throw RegraDeNegocio("o valor do QR não é o da cobrança; peça um QR novo ao recebedor")
        }
    }

    fun exigirPagavel(
        pagador: Long,
        agora: Instant,
    ) {
        val agoraEsta = estadoEm(agora)
        if (agoraEsta != "pendente") throw Conflito("esta cobrança está $agoraEsta")
        if (recebedorConta == pagador) throw PedidoInvalido("esta cobrança é sua")
    }

    /** Só a paga (ou estornada em parte) recebe estorno, e nunca mais do que falta devolver. */
    fun exigirEstornavel(
        valor: Centavos,
        agora: Instant,
    ) {
        if (estado != "paga" && estado != "parcialmente_estornada") {
            throw Conflito("só cobrança paga pode ser estornada; esta está ${estadoEm(agora)}")
        }
        val falta = this.valor - estornado
        if (valor > falta) throw RegraDeNegocio("passa do que falta devolver: ${Saidas.reais(falta)}")
    }
}

/** O SQL das cobranças. As regras de quem pode o quê ficam em [Cobrancas]; as de integridade, no banco (V5). */
@Repository
class CobrancasNoBanco(
    private val jdbc: JdbcClient,
) {
    fun inserir(
        donoId: Long,
        valor: Long,
        descricao: String,
        criadaEm: Instant,
        expiraEm: Instant,
    ): Long =
        jdbc
            .sql(
                """
                insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                select :codigo, id, :valor, :descricao, :criada, :expira
                from conta where dono_id = :dono and tipo = 'carteira'
                returning id
                """.trimIndent(),
            ).param("codigo", UUID.randomUUID())
            .param("valor", valor)
            .param("descricao", descricao)
            .param("criada", utc(criadaEm))
            .param("expira", utc(expiraEm))
            .param("dono", donoId)
            .query(Long::class.java)
            .single()

    fun carteiraDe(usuarioId: Long): Long =
        jdbc
            .sql("select id from conta where dono_id = :dono and tipo = 'carteira'")
            .param("dono", usuarioId)
            .query(Long::class.java)
            .single()

    fun porId(id: Long): Cobranca? = varias("c.id = :id", "id" to id).singleOrNull()

    /** Só se for do lojista [donoId]; [travar] segura a linha até o fim da transação. */
    fun doLojista(
        donoId: Long,
        id: Long,
        travar: Boolean = false,
    ): Cobranca? =
        varias("c.id = :id and r.dono_id = :dono" + forUpdate(travar), "id" to id, "dono" to donoId).singleOrNull()

    /** As 100 mais recentes do lojista. */
    fun doLojista(donoId: Long): List<Cobranca> =
        varias(
            "r.dono_id = :dono order by c.id desc limit 100",
            "dono" to donoId,
        )

    fun porCodigo(
        codigo: UUID,
        travar: Boolean = false,
    ): Cobranca? = varias("c.codigo = :codigo" + forUpdate(travar), "codigo" to codigo).singleOrNull()

    /**
     * [filtro] é o resto do WHERE, montado só aqui dentro com texto fixo: valores sempre vão por
     * parâmetro, nunca colados no SQL.
     */
    private fun varias(
        filtro: String,
        vararg parametros: Pair<String, Any>,
    ): List<Cobranca> =
        parametros
            .fold(
                jdbc.sql(
                    """
                    select c.id, c.codigo, c.recebedor_conta, u.nome, c.valor, c.descricao, c.estado,
                           c.criada_em, c.expira_em, c.pagador_conta, c.paga_em,
                           (select coalesce(sum(e.valor), 0) from estorno e where e.cobranca_id = c.id) as estornado
                    from cobranca c join conta r on r.id = c.recebedor_conta join usuario u on u.id = r.dono_id
                    where $filtro
                    """.trimIndent(),
                ),
            ) { sql, (nome, valor) -> sql.param(nome, valor) }
            .query { rs, _ -> rs.cobranca() }
            .list()

    fun marcarPaga(
        id: Long,
        pagador: Long,
        lancamento: Long,
        em: Instant,
    ) = jdbc
        .sql(
            """
            update cobranca set estado = 'paga', pagador_conta = :pagador, lancamento_id = :lancamento, paga_em = :em
            where id = :id
            """.trimIndent(),
        ).param("pagador", pagador)
        .param("lancamento", lancamento)
        .param("em", utc(em))
        .param("id", id)
        .update()

    /**
     * A soma dos estornos lida num comando próprio. Depois de esperar a trava da cobrança, o SELECT
     * que travou enxerga a linha nova da cobrança, mas uma subconsulta dele ainda usa a foto de antes
     * da espera (READ COMMITTED): o estorno que acabou de ser gravado ficaria de fora da soma.
     */
    fun estornado(id: Long): Centavos =
        Centavos(
            jdbc
                .sql("select coalesce(sum(valor), 0) from estorno where cobranca_id = :id")
                .param("id", id)
                .query(Long::class.java)
                .single(),
        )

    fun marcarCancelada(id: Long) =
        jdbc
            .sql("update cobranca set estado = 'cancelada' where id = :id")
            .param("id", id)
            .update()

    /** As que acabaram de expirar, para avisar cada lojista. */
    fun marcarExpiradas(agora: Instant): List<Cobranca> {
        val ids =
            jdbc
                .sql(
                    """
                    update cobranca set estado = 'expirada'
                    where estado = 'pendente' and expira_em <= :agora
                    returning id
                    """.trimIndent(),
                ).param("agora", utc(agora))
                .query(Long::class.java)
                .list()
        return if (ids.isEmpty()) emptyList() else varias("c.id = any(:ids::bigint[])", "ids" to ids.toTypedArray())
    }
}

private fun ResultSet.cobranca() =
    Cobranca(
        id = getLong("id"),
        codigo = getObject("codigo", UUID::class.java),
        recebedorConta = getLong("recebedor_conta"),
        recebedor = getString("nome"),
        valor = Centavos(getLong("valor")),
        descricao = getString("descricao"),
        estado = getString("estado"),
        criadaEm = checkNotNull(instante("criada_em")),
        expiraEm = checkNotNull(instante("expira_em")),
        pagadorConta = getLong("pagador_conta").takeUnless { wasNull() },
        pagaEm = instante("paga_em"),
        estornado = Centavos(getLong("estornado")),
    )

private fun ResultSet.instante(coluna: String) = getObject(coluna, OffsetDateTime::class.java)?.toInstant()

private fun utc(instante: Instant) = OffsetDateTime.ofInstant(instante, ZoneOffset.UTC)

/** Os pedaços de SQL que [CobrancasNoBanco] cola no WHERE são todos texto fixo, como este. */
private fun forUpdate(travar: Boolean) = if (travar) " for update of c" else ""
