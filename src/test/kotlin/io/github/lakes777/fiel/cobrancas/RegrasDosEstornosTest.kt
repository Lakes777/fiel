package io.github.lakes777.fiel.cobrancas

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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Estornos atacados direto com SQL: a soma, o estado e o lançamento valem mesmo sem a API. */
@TesteDeIntegracao
class RegrasDosEstornosTest {
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

    private fun estado(cobranca: Long) =
        jdbc
            .sql("select estado from cobranca where id = :id")
            .param("id", cobranca)
            .query(String::class.java)
            .single()

    /** Uma cobrança de [valor] da [loja], paga pela [ana]; devolve o id e o lançamento do pagamento. */
    private fun paga(
        loja: NovoUsuario,
        ana: NovoUsuario,
        valor: Long = 500,
    ): Pair<Long, Long> =
        checkNotNull(
            transacao.execute {
                val id =
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
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, valor))
                sql(
                    "update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l, paga_em = now() " +
                        "where id = :id",
                    "p" to ana.carteiraId,
                    "l" to l.id,
                    "id" to id,
                )
                id to l.id
            },
        )

    private fun estornoAMao(
        cobranca: Long,
        valor: Long,
        lancamento: Long,
    ) = sql(
        """
        insert into estorno (cobranca_id, chave_idempotencia, resumo_do_pedido, valor, lancamento_id, criado_em)
        values (:c, :k, :r, :v, :l, now())
        """.trimIndent(),
        "c" to cobranca,
        "k" to UUID.randomUUID().toString(),
        "r" to "0".repeat(64),
        "v" to valor,
        "l" to lancamento,
    )

    /** Um estorno feito direito: lançamento da loja para a Ana e a linha do estorno. */
    private fun estornar(
        loja: NovoUsuario,
        ana: NovoUsuario,
        cobranca: Long,
        valor: Long,
    ) = transacao.executeWithoutResult {
        val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, valor))
        estornoAMao(cobranca, valor, l.id)
    }

    @Test
    fun `o estado segue a soma dos estornos`() {
        val (loja, ana) = pessoa() to pessoa()
        val (id, _) = paga(loja, ana, 500)

        estornar(loja, ana, id, 200)
        assertEquals("parcialmente_estornada", estado(id))
        estornar(loja, ana, id, 300)
        assertEquals("estornada", estado(id))
    }

    @Test
    fun `estorno só em cobrança paga, e a soma não passa do valor`() {
        val (loja, ana) = pessoa() to pessoa()
        val (id, _) = paga(loja, ana, 500)
        val pendente =
            jdbc
                .sql(
                    """
                    insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                    values (gen_random_uuid(), :c, 500, 'teste', now(), now() + interval '1 minute') returning id
                    """.trimIndent(),
                ).param("c", loja.carteiraId)
                .query(Long::class.java)
                .single()

        val semPagamento =
            erroDoBanco {
                val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 100))
                estornoAMao(pendente, 100, l.id)
            }
        val demais =
            erroDoBanco {
                val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 501))
                estornoAMao(id, 501, l.id)
            }

        assertEquals("23514", semPagamento.sqlState)
        assertEquals("23514", demais.sqlState)
        assertEquals("paga", estado(id))
    }

    @Test
    fun `o estado de estorno não se escreve à mão`() {
        val (loja, ana) = pessoa() to pessoa()
        val (id, _) = paga(loja, ana, 500)
        estornar(loja, ana, id, 100)

        for (alvo in listOf("estornada", "paga", "pendente")) {
            val erro = erroDoBanco { sql("update cobranca set estado = :e where id = :id", "e" to alvo, "id" to id) }
            assertEquals("23001", erro.sqlState, alvo)
        }
        val (outra, _) = paga(loja, ana, 500)
        assertEquals(
            "23001",
            erroDoBanco {
                sql(
                    "update cobranca set estado = 'parcialmente_estornada' where id = :id",
                    "id" to outra,
                )
            }.sqlState,
        )
    }

    @Test
    fun `estorno com lançamento errado é recusado no commit`() {
        val (loja, ana, eva) = Triple(pessoa(), pessoa(), pessoa())
        val (id, doPagamento) = paga(loja, ana, 500)

        val outroValor =
            erroDoBanco {
                val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 50))
                estornoAMao(id, 100, l.id)
            }
        val outraPessoa =
            erroDoBanco {
                val l = razao.registrar(transferencia(loja.carteiraId, eva.carteiraId, 100))
                estornoAMao(id, 100, l.id)
            }
        val aoContrario =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 100))
                estornoAMao(id, 100, l.id)
            }
        val oDoPagamento = erroDoBanco { estornoAMao(id, 500, doPagamento) }

        assertEquals("23514", outroValor.sqlState)
        assertEquals("23514", outraPessoa.sqlState)
        assertEquals("23514", aoContrario.sqlState)
        assertEquals("23514", oDoPagamento.sqlState)
        assertEquals("paga", estado(id))
    }

    @Test
    fun `o lançamento de um estorno não serve de transferência nem de pagamento`() {
        val (loja, ana) = pessoa() to pessoa()
        val (id, _) = paga(loja, ana, 500)
        val doEstorno =
            checkNotNull(
                transacao.execute {
                    val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 500))
                    estornoAMao(id, 500, l.id)
                    l.id
                },
            )
        val outra =
            jdbc
                .sql(
                    """
                    insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                    values (gen_random_uuid(), :c, 500, 'teste', now(), now() + interval '1 minute') returning id
                    """.trimIndent(),
                ).param("c", ana.carteiraId)
                .query(Long::class.java)
                .single()

        val comoTransferencia =
            erroDoBanco {
                sql(
                    """
                    insert into transferencia (usuario_id, chave_idempotencia, resumo_do_pedido,
                                               de_conta, para_conta, valor, lancamento_id, criada_em)
                    values (:u, :k, :r, :de, :para, 500, :l, now())
                    """.trimIndent(),
                    "u" to loja.id,
                    "k" to UUID.randomUUID().toString(),
                    "r" to "0".repeat(64),
                    "de" to loja.carteiraId,
                    "para" to ana.carteiraId,
                    "l" to doEstorno,
                )
            }
        val comoPagamento =
            erroDoBanco {
                sql(
                    "update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l, paga_em = now() " +
                        "where id = :id",
                    "p" to loja.carteiraId,
                    "l" to doEstorno,
                    "id" to outra,
                )
            }

        assertEquals("23514", comoTransferencia.sqlState)
        assertEquals("23514", comoPagamento.sqlState)
    }

    @Test
    fun `estorno não muda nem some`() {
        val (loja, ana) = pessoa() to pessoa()
        val (id, _) = paga(loja, ana, 500)
        estornar(loja, ana, id, 100)
        val estorno =
            jdbc
                .sql("select id from estorno where cobranca_id = :c")
                .param("c", id)
                .query(Long::class.java)
                .single()

        for (comando in listOf("update estorno set valor = 1 where id = :id", "delete from estorno where id = :id")) {
            assertEquals("23001", erroDoBanco { sql(comando, "id" to estorno) }.sqlState, comando)
        }
        assertEquals("23001", erroDoBanco { sql("truncate estorno") }.sqlState)
    }
}
