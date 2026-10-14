package io.github.lakes777.fiel.razao

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NovoLancamentoTest {
    private fun lancamento(vararg partidas: Pair<Long, Long>) =
        NovoLancamento("teste", partidas.map { (conta, valor) -> NovaPartida(conta, Centavos(valor)) })

    @Test
    fun `aceita partidas que somam zero em contas diferentes`() {
        val novo = lancamento(1L to -1000L, 2L to 700L, 3L to 300L)
        assertEquals(3, novo.partidas.size)
    }

    @Test
    fun `recusa cada regra quebrada com uma mensagem clara`() {
        val casos =
            mapOf(
                "somam 1 centavos" to { lancamento(1L to -100L, 2L to 101L) },
                "pelo menos duas partidas" to { lancamento() },
                "valor zero" to { lancamento(1L to 0L, 2L to 0L) },
                "mesma conta" to { lancamento(1L to -100L, 1L to 100L) },
                "grandes demais" to { lancamento(1L to Long.MAX_VALUE, 2L to 1L, 3L to Long.MIN_VALUE) },
            )
        for ((trecho, criar) in casos) {
            val erro = assertFailsWith<IllegalArgumentException>(trecho) { criar() }
            assertTrue(erro.message!!.contains(trecho), "esperava \"$trecho\" em \"${erro.message}\"")
        }
        assertFailsWith<IllegalArgumentException> { NovoLancamento(" ", lancamento(1L to -1L, 2L to 1L).partidas) }
        assertFailsWith<IllegalArgumentException> {
            NovoLancamento(
                "x".repeat(201),
                lancamento(1L to -1L, 2L to 1L).partidas,
            )
        }
    }

    @Test
    fun `propriedade - aceita exatamente quando as regras valem`() =
        runBlocking<Unit> {
            // Valores pequenos e poucas contas de propósito: assim os casos aleatórios caem com
            // frequência em soma zero, conta repetida e valor zero, e não só no caso "inválido"
            checkAll(500, Arb.list(Arb.int(1..4), 0..5), Arb.list(Arb.long(-3L..3L), 0..5)) { contas, valores ->
                val pares = contas.zip(valores).map { (conta, valor) -> NovaPartida(conta.toLong(), Centavos(valor)) }
                val deveAceitar =
                    pares.size >= 2 &&
                        pares.none { it.valor.valor == 0L } &&
                        pares.map { it.contaId }.toSet().size == pares.size &&
                        pares.sumOf { it.valor.valor } == 0L
                val aceitou = runCatching { NovoLancamento("aleatório", pares) }.isSuccess
                assertEquals(deveAceitar, aceitou, "partidas: $pares")
            }
        }
}
