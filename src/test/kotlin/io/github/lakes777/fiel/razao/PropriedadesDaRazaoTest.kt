package io.github.lakes777.fiel.razao

import io.github.lakes777.fiel.TesteDeIntegracao
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Testes de propriedade contra o Postgres de verdade: sequências aleatórias de lançamentos. */
@TesteDeIntegracao
class PropriedadesDaRazaoTest {
    @Autowired
    private lateinit var razao: Razao

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Test
    fun `depois de qualquer sequência de lançamentos, cada saldo bate com a conta feita à parte`() =
        runBlocking<Unit> {
            // (de, para, valor) com índices numa lista de 5 contas; de == para vira um caso pulado
            val movimentos = Arb.list(Arb.triple(Arb.int(0..4), Arb.int(0..4), Arb.long(1L..1_000_000L)), 1..15)
            checkAll(40, movimentos) { sequencia ->
                val contas = List(5) { razao.abrirConta("propriedade $it").id }
                val esperado = LongArray(5)
                for ((de, para, valor) in sequencia) {
                    if (de == para) continue
                    razao.registrar(
                        NovoLancamento(
                            "aleatório",
                            listOf(
                                NovaPartida(contas[de], Centavos(-valor)),
                                NovaPartida(contas[para], Centavos(valor)),
                            ),
                        ),
                    )
                    esperado[de] -= valor
                    esperado[para] += valor
                }
                assertEquals(esperado.toList(), contas.map { razao.saldo(it).valor })
                assertEquals(0L, contas.sumOf { razao.saldo(it).valor })
                for (conta in contas) {
                    val extrato = razao.extrato(conta, limite = 500)
                    // O saldo depois do movimento mais recente é o saldo da conta
                    assertEquals(razao.saldo(conta), extrato.firstOrNull()?.saldoDepois ?: Centavos.ZERO)
                }
            }
        }

    @Test
    fun `o banco recusa qualquer lançamento que não fecha, mesmo escrito à mão`() =
        runBlocking<Unit> {
            val contas = List(3) { razao.abrirConta("à mão $it").id }
            val partidas = Arb.list(Arb.pair(Arb.int(0..2), Arb.long(-1000L..1000L)), 0..3)
            checkAll(60, partidas) { lista ->
                // Só interessam os casos que não fecham: os outros o banco aceitaria (e devia)
                val distintas = lista.distinctBy { it.first }.filter { it.second != 0L }
                if (distintas.size >= 2 && distintas.sumOf { it.second } == 0L) return@checkAll
                assertFailsWith<DataAccessException>("partidas: $distintas") {
                    transacao.executeWithoutResult {
                        val l =
                            jdbc
                                .sql(
                                    "insert into lancamento (descricao) values ('à mão') returning id",
                                ).query(Long::class.java)
                                .single()
                        for ((conta, valor) in distintas) {
                            jdbc
                                .sql("insert into partida (lancamento_id, conta_id, valor) values (:l, :c, :v)")
                                .param("l", l)
                                .param("c", contas[conta])
                                .param("v", valor)
                                .update()
                        }
                    }
                }
            }
            assertEquals(listOf(0L, 0L, 0L), contas.map { razao.saldo(it).valor })
        }
}
