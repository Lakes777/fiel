package io.github.lakes777.fiel.razao

import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ataca o banco direto com SQL, sem passar pelo Kotlin: as regras do razão têm que valer mesmo para
 * quem escreve SQL à mão ou para uma versão futura da API com bug.
 */
@TesteDeIntegracao
class RegrasDoBancoTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Autowired
    private lateinit var razao: Razao

    private fun conta(): Long = razao.abrirConta("conta de teste").id

    private fun novoLancamento(): Long =
        jdbc.sql("insert into lancamento (descricao) values ('à mão') returning id").query(Long::class.java).single()

    private fun partida(
        lancamento: Long,
        conta: Long,
        valor: Long,
    ) = jdbc
        .sql("insert into partida (lancamento_id, conta_id, valor) values (:l, :c, :v)")
        .param("l", lancamento)
        .param("c", conta)
        .param("v", valor)
        .update()

    /** Roda o bloco numa transação e devolve o erro do Postgres (o SQLSTATE) que ela levou. */
    private fun erroDoBanco(bloco: () -> Unit): SQLException {
        val erro = assertFailsWith<DataAccessException> { transacao.executeWithoutResult { bloco() } }
        return assertNotNull(
            generateSequence<Throwable>(erro) { it.cause }.filterIsInstance<SQLException>().lastOrNull(),
        )
    }

    private fun lancamentosCom(conta: Long) =
        jdbc
            .sql("select count(*) from partida where conta_id = :c")
            .param("c", conta)
            .query(Long::class.java)
            .single()

    @Test
    fun `recusa no commit um lançamento que não soma zero e não grava nada`() {
        val (a, b) = conta() to conta()
        val erro =
            erroDoBanco {
                val l = novoLancamento()
                partida(l, a, -100)
                partida(l, b, 99)
            }
        assertEquals("23514", erro.sqlState)
        assertTrue(erro.message!!.contains("somam -1 centavos"))
        assertEquals(0, lancamentosCom(a))
    }

    @Test
    fun `aceita a soma zero mesmo com partidas inseridas uma por uma`() {
        // O gatilho é deferido: no meio da transação a soma ainda não fecha, e tudo bem
        val (a, b, c) = Triple(conta(), conta(), conta())
        transacao.executeWithoutResult {
            val l = novoLancamento()
            partida(l, a, -500)
            partida(l, b, 200)
            partida(l, c, 300)
        }
        assertEquals(Centavos(-500), razao.saldo(a))
        assertEquals(Centavos(300), razao.saldo(c))
    }

    @Test
    fun `recusa lançamento sem partidas ou com uma só`() {
        assertEquals("23514", erroDoBanco { novoLancamento() }.sqlState)
        val a = conta()
        val erro =
            erroDoBanco {
                partida(novoLancamento(), a, 100)
            }
        assertTrue(erro.message!!.contains("1 partida(s)"))
    }

    @Test
    fun `recusa a mesma conta duas vezes e partida de valor zero`() {
        val (a, b) = conta() to conta()
        assertEquals(
            "23505",
            erroDoBanco {
                novoLancamento().let {
                    partida(it, a, -1)
                    partida(it, a, 1)
                }
            }.sqlState,
        )
        assertEquals(
            "23514",
            erroDoBanco {
                novoLancamento().let {
                    partida(it, a, 0)
                    partida(it, b, 0)
                }
            }.sqlState,
        )
    }

    @Test
    fun `não deixa pôr partida num lançamento já fechado`() {
        // Um par +X/-X manteria a soma zero; a regra é outra: lançamento fechado não muda
        val (a, b) = conta() to conta()
        val (c, d) = conta() to conta()
        val l = razao.registrar(transferencia(a, b, 10, "original")).id
        val erro =
            erroDoBanco {
                partida(l, c, -1_000_000)
                partida(l, d, 1_000_000)
            }
        assertEquals("23001", erro.sqlState)
        assertEquals(Centavos.ZERO, razao.saldo(d))
    }

    @Test
    fun `lançamento e partida não mudam nem somem`() {
        val (a, b) = conta() to conta()
        val l = razao.registrar(transferencia(a, b, 10, "imutável")).id
        val tentativas =
            listOf(
                "update partida set valor = 1000 where lancamento_id = $l",
                "update lancamento set descricao = 'outra' where id = $l",
                "delete from partida where lancamento_id = $l",
                "delete from lancamento where id = $l",
                "truncate partida, lancamento, transferencia",
            )
        for (sql in tentativas) {
            assertEquals("23001", erroDoBanco { jdbc.sql(sql).update() }.sqlState, sql)
        }
        assertEquals(Centavos(10), razao.saldo(b))
    }

    @Test
    fun `ninguém escolhe o próprio id`() {
        val (a, b) = conta() to conta()
        val tentativas =
            listOf(
                "insert into conta (id, nome) overriding system value values (-1, 'fora da fila')",
                "insert into lancamento (id, descricao) overriding system value values (-1, 'fora da fila')",
            )
        for (sql in tentativas) {
            assertEquals("23001", erroDoBanco { jdbc.sql(sql).update() }.sqlState, sql)
        }
        // Partida "antes" de todas as outras mudaria o saldo depois de cada movimento antigo no extrato
        val erro =
            erroDoBanco {
                val l = novoLancamento()
                jdbc
                    .sql(
                        "insert into partida (id, lancamento_id, conta_id, valor) overriding system value " +
                            "values (-2, :l, :a, -5), (-1, :l, :b, 5)",
                    ).param("l", l)
                    .param("a", a)
                    .param("b", b)
                    .update()
            }
        assertEquals("23001", erro.sqlState)
        assertEquals(0, lancamentosCom(a))
    }

    @Test
    fun `o banco carimba a data e a transação do lançamento`() {
        val (a, b) = conta() to conta()
        val (criadoEm, daMesmaTransacao) =
            transacao.execute {
                val l =
                    jdbc
                        .sql(
                            "insert into lancamento (descricao, criado_em, transacao) " +
                                "values ('passado', '2001-01-01', '1') returning id",
                        ).query(Long::class.java)
                        .single()
                partida(l, a, -1)
                partida(l, b, 1)
                jdbc
                    .sql(
                        "select criado_em > now() - interval '1 minute', transacao = pg_current_xact_id() " +
                            "from lancamento where id = :l",
                    ).param("l", l)
                    .query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }
                    .single()
            }
        assertTrue(criadoEm, "a data veio do banco, não do INSERT")
        assertTrue(daMesmaTransacao)
    }

    @Test
    fun `no banco inteiro, todas as partidas somam zero`() {
        // Vale a qualquer momento, com tudo o que os outros testes gravaram
        val soma = jdbc.sql("select coalesce(sum(valor), 0) from partida").query(Long::class.java).single()
        assertEquals(0, soma)
    }
}
