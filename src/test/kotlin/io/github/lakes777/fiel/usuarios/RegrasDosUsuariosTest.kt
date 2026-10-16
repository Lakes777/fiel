package io.github.lakes777.fiel.usuarios

import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Ataca o banco direto com SQL, como RegrasDoBancoTest faz com o razão: dono, e-mail e chaves têm
 * que valer mesmo sem passar pela API.
 */
@TesteDeIntegracao
class RegrasDosUsuariosTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Autowired
    private lateinit var cadastro: Cadastro

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

    private fun chave(
        conta: Long,
        tipo: String,
        valor: String,
    ) = sql("insert into chave (conta_id, tipo, valor) values (:c, :t, :v)", "c" to conta, "t" to tipo, "v" to valor)

    private fun aleatoria() = UUID.randomUUID().toString()

    @Test
    fun `o dono de uma conta não muda, seria mover o dinheiro sem lançamento`() {
        val (ana, bob) = pessoa() to pessoa()

        val erro =
            erroDoBanco { sql("update conta set dono_id = :b where id = :c", "b" to bob.id, "c" to ana.carteiraId) }

        assertEquals("23001", erro.sqlState)
    }

    @Test
    fun `o tipo de uma conta também não muda`() {
        val ana = pessoa()
        val erro =
            erroDoBanco {
                sql(
                    "update conta set tipo = 'interna', dono_id = null where id = :c",
                    "c" to ana.carteiraId,
                )
            }
        assertEquals("23001", erro.sqlState)
    }

    @Test
    fun `carteira sem dono, conta interna com dono e segunda carteira são recusadas`() {
        val ana = pessoa()
        assertEquals("23514", erroDoBanco { sql("insert into conta (nome, tipo) values ('x', 'carteira')") }.sqlState)
        assertEquals(
            "23514",
            erroDoBanco { sql("insert into conta (nome, dono_id) values ('x', :d)", "d" to ana.id) }.sqlState,
        )
        assertEquals(
            "23505",
            erroDoBanco {
                sql(
                    "insert into conta (nome, tipo, dono_id) values ('x', 'carteira', :d)",
                    "d" to ana.id,
                )
            }.sqlState,
        )
    }

    @Test
    fun `só existe uma conta de emissão`() {
        assertEquals(
            "23505",
            erroDoBanco { sql("insert into conta (nome, tipo) values ('outra', 'emissao')") }.sqlState,
        )
    }

    @Test
    fun `o e-mail de um usuário não muda`() {
        val ana = pessoa()
        val erro = erroDoBanco { sql("update usuario set email = 'outro@teste.fiel' where id = :id", "id" to ana.id) }
        assertEquals("23001", erro.sqlState)
    }

    @Test
    fun `chave de e-mail só com o e-mail do dono, e chave só em carteira`() {
        val (ana, bob) = pessoa() to pessoa()
        val interna =
            jdbc
                .sql(
                    "insert into conta (nome) values ('interna') returning id",
                ).query(Long::class.java)
                .single()

        assertEquals("23514", erroDoBanco { chave(ana.carteiraId, "email", bob.email) }.sqlState)
        assertEquals("23514", erroDoBanco { chave(interna, "aleatoria", aleatoria()) }.sqlState)
        assertEquals("23514", erroDoBanco { chave(ana.carteiraId, "aleatoria", "não-é-uuid") }.sqlState)
        assertEquals("23514", erroDoBanco { chave(ana.carteiraId, "cpf", "12345678900") }.sqlState)
        transacao.executeWithoutResult { chave(ana.carteiraId, "email", ana.email) }
    }

    @Test
    fun `a sexta chave é recusada pelo banco, e chave não muda`() {
        val ana = pessoa()
        transacao.executeWithoutResult { repeat(5) { chave(ana.carteiraId, "aleatoria", aleatoria()) } }

        assertEquals("23514", erroDoBanco { chave(ana.carteiraId, "aleatoria", aleatoria()) }.sqlState)
        val erro =
            erroDoBanco {
                sql(
                    "update chave set valor = :v where conta_id = :c",
                    "v" to aleatoria(),
                    "c" to ana.carteiraId,
                )
            }
        assertEquals("23001", erro.sqlState)
    }

    @Test
    fun `duas chaves criadas ao mesmo tempo não passam juntas do limite`() =
        corridaPelaQuintaChave(TransactionDefinition.ISOLATION_READ_COMMITTED)

    @Test
    fun `nem em REPEATABLE READ, onde uma contagem veria a foto antiga`() =
        corridaPelaQuintaChave(TransactionDefinition.ISOLATION_REPEATABLE_READ)

    /**
     * A primeira transação insere a 5ª chave e segura o commit até a segunda estar esperando a
     * linha da conta (o contador de chaves). Sem o contador, a segunda não esperaria nada: contaria
     * 4 chaves (a 5ª ainda não foi confirmada) e passaria junto.
     */
    private fun corridaPelaQuintaChave(isolamento: Int) {
        val ana = pessoa()
        transacao.executeWithoutResult { repeat(4) { chave(ana.carteiraId, "aleatoria", aleatoria()) } }
        val emTransacao =
            TransactionTemplate(checkNotNull(transacao.transactionManager)).apply {
                isolationLevel =
                    isolamento
            }

        fun tentar(bloco: () -> Unit) = runCatching { emTransacao.executeWithoutResult { bloco() } }.isSuccess
        val primeiraInseriu = CountDownLatch(1)
        val tarefas = Executors.newFixedThreadPool(2)
        val primeira =
            tarefas.submit<Boolean> {
                tentar {
                    chave(ana.carteiraId, "aleatoria", aleatoria())
                    primeiraInseriu.countDown()
                    esperarAlguemNaFilaDaTrava()
                }
            }
        val segunda =
            tarefas.submit<Boolean> {
                primeiraInseriu.await()
                tentar { chave(ana.carteiraId, "aleatoria", aleatoria()) }
            }
        val resultados = listOf(primeira, segunda).map { it.get(TEMPO_LIMITE_S, TimeUnit.SECONDS) }
        tarefas.shutdown()

        assertEquals(listOf(true, false), resultados)
        assertEquals(5, chavesNoBanco(ana.carteiraId))
        assertEquals(5, contadorDaConta(ana.carteiraId))
    }

    @Test
    fun `apagar uma chave libera a vaga, e o contador não se mexe à mão`() {
        val ana = pessoa()
        transacao.executeWithoutResult { repeat(5) { chave(ana.carteiraId, "aleatoria", aleatoria()) } }

        sql("delete from chave where id = (select min(id) from chave where conta_id = :c)", "c" to ana.carteiraId)
        transacao.executeWithoutResult { chave(ana.carteiraId, "aleatoria", aleatoria()) }

        assertEquals(5, contadorDaConta(ana.carteiraId))
        assertEquals(
            "23001",
            erroDoBanco { sql("update conta set chaves = 0 where id = :c", "c" to ana.carteiraId) }.sqlState,
        )
        assertEquals("23001", erroDoBanco { sql("truncate chave") }.sqlState)
    }

    private fun chavesNoBanco(conta: Long) =
        jdbc
            .sql("select count(*) from chave where conta_id = :c")
            .param("c", conta)
            .query(Long::class.java)
            .single()

    private fun contadorDaConta(conta: Long) =
        jdbc
            .sql("select chaves from conta where id = :c")
            .param("c", conta)
            .query(Long::class.java)
            .single()

    /** Espera (até ~3 s) alguma conexão ficar parada esperando uma trava; sem ninguém, segue assim mesmo. */
    private fun esperarAlguemNaFilaDaTrava() {
        repeat(TENTATIVAS) {
            val esperando =
                jdbc
                    .sql(
                        """
                        select count(*) from pg_stat_activity
                        where wait_event_type = 'Lock' and datname = current_database()
                        """.trimIndent(),
                    ).query(Long::class.java)
                    .single()
            if (esperando > 0) return
            Thread.sleep(INTERVALO_MS)
        }
    }

    private companion object {
        const val TEMPO_LIMITE_S = 10L
        const val TENTATIVAS = 60
        const val INTERVALO_MS = 50L
    }
}
