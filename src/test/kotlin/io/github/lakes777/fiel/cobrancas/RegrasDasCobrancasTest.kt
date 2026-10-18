package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.COMO_DONO
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.razao.transferencia
import io.github.lakes777.fiel.usuarios.Cadastro
import io.github.lakes777.fiel.usuarios.NovoUsuario
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Cobranças atacadas direto com SQL: os estados e o pagamento valem mesmo sem a API. */
@TesteDeIntegracao
class RegrasDasCobrancasTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Autowired
    private lateinit var cadastro: Cadastro

    @Autowired
    private lateinit var razao: Razao

    private fun pessoa(): NovoUsuario =
        cadastro.cadastrar("Ana Souza", "${UUID.randomUUID()}@teste.fiel", "senha-de-teste-123")

    private fun erroDoBanco(bloco: () -> Unit): SQLException {
        val erro = assertFailsWith<DataAccessException> { transacao.executeWithoutResult { bloco() } }
        return assertNotNull(
            generateSequence<Throwable>(erro) { it.cause }.filterIsInstance<SQLException>().lastOrNull(),
        )
    }

    private fun sql(
        comando: String,
        vararg parametros: Pair<String, Any>,
    ) = parametros.fold(jdbc.sql(comando)) { s, (nome, valor) -> s.param(nome, valor) }.update()

    /** Uma cobrança pendente de [valor] para a carteira de [loja]. */
    private fun cobranca(
        loja: NovoUsuario,
        valor: Long = 500,
    ): Long =
        jdbc
            .sql(
                """
                insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                values (:codigo, :conta, :valor, 'teste', now(), now() + interval '30 minutes')
                returning id
                """.trimIndent(),
            ).param("codigo", UUID.randomUUID())
            .param("conta", loja.carteiraId)
            .param("valor", valor)
            .query(Long::class.java)
            .single()

    /** Paga à mão, com o lançamento que quiser. */
    private fun pagarAMao(
        id: Long,
        pagador: Long,
        lancamento: Long,
    ) = sql(
        "update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l, paga_em = :em where id = :id",
        "p" to pagador,
        "l" to lancamento,
        "em" to OffsetDateTime.now(),
        "id" to id,
    )

    @Test
    fun `cobrança nasce pendente, numa carteira`() {
        val loja = pessoa()

        val paga =
            erroDoBanco {
                sql(
                    """
                    insert into cobranca (codigo, recebedor_conta, valor, descricao, estado, criada_em, expira_em)
                    values (gen_random_uuid(), :c, 500, 'teste', 'expirada', now(), now() + interval '1 minute')
                    """.trimIndent(),
                    "c" to loja.carteiraId,
                )
            }
        val emissao =
            erroDoBanco {
                sql(
                    """
                    insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                    select gen_random_uuid(), id, 500, 'teste', now(), now() + interval '1 minute'
                    from conta where tipo = 'emissao'
                    """.trimIndent(),
                )
            }

        assertEquals("23514", paga.sqlState)
        assertEquals("23514", emissao.sqlState)
    }

    @Test
    fun `o estado só anda para a frente, e o que foi cobrado não muda`() {
        val (loja, ana) = pessoa() to pessoa()
        val id = cobranca(loja)
        transacao.executeWithoutResult {
            val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
            pagarAMao(id, ana.carteiraId, l.id)
        }
        val cancelada = cobranca(loja)
        sql("update cobranca set estado = 'cancelada' where id = :id", "id" to cancelada)

        val tentativas =
            listOf(
                "update cobranca set estado = 'cancelada' where id = :id" to id,
                "update cobranca set estado = 'estornada' where id = :id" to id,
                "update cobranca set valor = 1 where id = :id" to id,
                "update cobranca set paga_em = now() - interval '1 day' where id = :id" to id,
                "update cobranca set estado = 'pendente' where id = :id" to cancelada,
                "update cobranca set expira_em = now() + interval '1 year' where id = :id" to cancelada,
                "delete from cobranca where id = :id" to id,
            )
        for ((comando, alvo) in tentativas) {
            assertEquals("23001", erroDoBanco { sql(comando, "id" to alvo) }.sqlState, comando)
        }
        // TRUNCATE só o dono alcança (a API nem tem a permissão, ver PapelDaApiTest): o gatilho vale para ele também
        assertEquals(
            "23001",
            erroDoBanco {
                sql(COMO_DONO)
                sql("truncate cobranca, estorno")
            }.sqlState,
        )
    }

    @Test
    fun `pagamento com lançamento de outro valor ou de outra conta é recusado no commit`() {
        val (loja, ana, eva) = Triple(pessoa(), pessoa(), pessoa())
        val id = cobranca(loja, 500)

        val outroValor =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 5))
                pagarAMao(id, ana.carteiraId, l.id)
            }
        val outraConta =
            erroDoBanco {
                val l = razao.registrar(transferencia(eva.carteiraId, loja.carteiraId, 500))
                pagarAMao(id, ana.carteiraId, l.id)
            }
        val partidaDepois =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                pagarAMao(id, ana.carteiraId, l.id)
                val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()
                sql(
                    "insert into partida (lancamento_id, conta_id, valor) values (:l, :e, -900), (:l, :c, 900)",
                    "l" to l.id,
                    "e" to emissao,
                    "c" to eva.carteiraId,
                )
            }

        assertEquals("23514", outroValor.sqlState)
        assertEquals("23514", outraConta.sqlState)
        assertEquals("23514", partidaDepois.sqlState)
        assertEquals(100_000L, razao.saldo(ana.carteiraId).valor)
    }

    @Test
    fun `um lançamento é de um fato só, transferência ou pagamento`() {
        val (loja, ana) = pessoa() to pessoa()
        // Uma transferência de R$ 5 da Ana para a loja, gravada direito
        val daTransferencia =
            checkNotNull(
                transacao.execute {
                    val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                    transferenciaAMao(ana.id, ana.carteiraId, loja.carteiraId, 500, l.id)
                    l.id
                },
            )
        // Um pagamento de R$ 5, também direito
        val paga = cobranca(loja, 500)
        val doPagamento =
            checkNotNull(
                transacao.execute {
                    val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                    pagarAMao(paga, ana.carteiraId, l.id)
                    l.id
                },
            )

        val pagamentoComTransferencia = erroDoBanco { pagarAMao(cobranca(loja, 500), ana.carteiraId, daTransferencia) }
        val transferenciaComPagamento =
            erroDoBanco { transferenciaAMao(ana.id, ana.carteiraId, loja.carteiraId, 500, doPagamento) }

        assertEquals("23514", pagamentoComTransferencia.sqlState)
        assertEquals("23514", transferenciaComPagamento.sqlState)
    }

    @Test
    fun `pagamento fora da validade é recusado, inclusive com data de ontem`() {
        val (loja, ana) = pessoa() to pessoa()
        val id = cobranca(loja)

        val ontem =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                sql(
                    """
                    update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l,
                                        paga_em = criada_em - interval '1 day'
                    where id = :id
                    """.trimIndent(),
                    "p" to ana.carteiraId,
                    "l" to l.id,
                    "id" to id,
                )
            }
        val vencida =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                sql(
                    """
                    update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l, paga_em = expira_em
                    where id = :id
                    """.trimIndent(),
                    "p" to ana.carteiraId,
                    "l" to l.id,
                    "id" to id,
                )
            }

        assertEquals("23514", ontem.sqlState)
        assertEquals("23514", vencida.sqlState)
    }

    private fun transferenciaAMao(
        usuario: Long,
        de: Long,
        para: Long,
        valor: Long,
        lancamento: Long,
    ) = sql(
        """
        insert into transferencia (usuario_id, chave_idempotencia, resumo_do_pedido,
                                   de_conta, para_conta, valor, lancamento_id, criada_em)
        values (:u, :k, :r, :de, :para, :v, :l, now())
        """.trimIndent(),
        "u" to usuario,
        "k" to UUID.randomUUID().toString(),
        "r" to "0".repeat(64),
        "de" to de,
        "para" to para,
        "v" to valor,
        "l" to lancamento,
    )
}
