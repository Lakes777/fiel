package io.github.lakes777.fiel.transferencias

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

/** Saldo guardado e transferências atacados direto com SQL: as regras valem mesmo sem a API. */
@TesteDeIntegracao
class RegrasDasTransferenciasTest {
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

    /** Grava uma linha de transferência à mão, como faria alguém com acesso ao banco. */
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
        values (:u, :k, :r, :de, :para, :v, :l, :em)
        """.trimIndent(),
        "u" to usuario,
        "k" to UUID.randomUUID().toString(),
        "r" to "0".repeat(64),
        "de" to de,
        "para" to para,
        "v" to valor,
        "l" to lancamento,
        "em" to OffsetDateTime.now(),
    )

    @Test
    fun `o saldo não muda à mão, só com lançamento`() {
        val ana = pessoa()

        val erro = erroDoBanco { sql("update conta set saldo = 999999999 where id = :c", "c" to ana.carteiraId) }

        assertEquals("23001", erro.sqlState)
        assertEquals(100_000L, razao.saldo(ana.carteiraId).valor)
    }

    @Test
    fun `carteira não fica negativa nem num lançamento escrito direto no razão`() {
        val (ana, bob) = pessoa() to pessoa()

        val erro = erroDoBanco { razao.registrar(transferencia(ana.carteiraId, bob.carteiraId, 100_001)) }

        assertEquals("23514", erro.sqlState)
        assertEquals(100_000L, razao.saldo(ana.carteiraId).valor)
        assertEquals(100_000L, razao.saldo(bob.carteiraId).valor)
    }

    @Test
    fun `transferência que não bate com o lançamento, ou de carteira alheia, é recusada`() {
        val (ana, bob) = pessoa() to pessoa()

        val outroValor =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, bob.carteiraId, 500))
                transferenciaAMao(ana.id, ana.carteiraId, bob.carteiraId, 5, l.id)
            }
        val outraConta =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, bob.carteiraId, 500))
                transferenciaAMao(bob.id, ana.carteiraId, bob.carteiraId, 500, l.id)
            }

        assertEquals("23514", outroValor.sqlState)
        assertEquals("23514", outraConta.sqlState)
    }

    @Test
    fun `partida acrescentada ao lançamento depois da transferência é recusada no commit`() {
        val (ana, bob, eva) = Triple(pessoa(), pessoa(), pessoa())
        val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()

        val erro =
            erroDoBanco {
                val l = razao.registrar(transferencia(ana.carteiraId, bob.carteiraId, 100))
                transferenciaAMao(ana.id, ana.carteiraId, bob.carteiraId, 100, l.id)
                // O par soma zero, então o razão sozinho aceitaria
                sql(
                    "insert into partida (lancamento_id, conta_id, valor) values (:l, :e, -900), (:l, :c, 900)",
                    "l" to l.id,
                    "e" to emissao,
                    "c" to eva.carteiraId,
                )
            }

        assertEquals("23514", erro.sqlState)
        assertEquals(100_000L, razao.saldo(eva.carteiraId).valor)
    }

    @Test
    fun `conta não nasce com saldo nem com chaves`() {
        val ana = pessoa()

        val comSaldo = erroDoBanco { sql("insert into conta (nome, saldo) values ('com saldo', 5000)") }
        val comChaves = erroDoBanco { sql("insert into conta (nome, chaves) values ('com chaves', 5)") }

        assertEquals("23514", comSaldo.sqlState)
        assertEquals("23514", comChaves.sqlState)
        assertEquals(100_000L, razao.saldo(ana.carteiraId).valor)
    }

    @Test
    fun `transferência gravada não muda nem é apagada`() {
        val (ana, bob) = pessoa() to pessoa()
        transacao.executeWithoutResult {
            val l = razao.registrar(transferencia(ana.carteiraId, bob.carteiraId, 500))
            transferenciaAMao(ana.id, ana.carteiraId, bob.carteiraId, 500, l.id)
        }

        val daAna = "c" to ana.carteiraId
        assertEquals(
            "23001",
            erroDoBanco { sql("update transferencia set valor = 1 where de_conta = :c", daAna) }.sqlState,
        )
        assertEquals("23001", erroDoBanco { sql("delete from transferencia where de_conta = :c", daAna) }.sqlState)
        assertEquals("23001", erroDoBanco { sql("truncate transferencia") }.sqlState)
    }
}
