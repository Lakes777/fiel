package io.github.lakes777.fiel.razao

import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TesteDeIntegracao
class RazaoTest {
    @Autowired
    private lateinit var razao: Razao

    private fun transferir(
        de: Long,
        para: Long,
        valor: Long,
        descricao: String = "transferência",
    ) = razao.registrar(transferencia(de, para, valor, descricao))

    @Test
    fun `abre conta com saldo zero e extrato vazio`() {
        val conta = razao.abrirConta("Ana")
        assertEquals("Ana", conta.nome)
        assertEquals(Centavos.ZERO, razao.saldo(conta.id))
        assertEquals(emptyList(), razao.extrato(conta.id))
    }

    @Test
    fun `registra o lançamento e devolve as partidas gravadas`() {
        val (ana, loja) = razao.abrirConta("Ana") to razao.abrirConta("Loja")
        val lancamento = transferir(ana.id, loja.id, 1050, "café")

        assertEquals("café", lancamento.descricao)
        assertEquals(
            listOf(ana.id to Centavos(-1050), loja.id to Centavos(1050)),
            lancamento.partidas.map {
                it.contaId to
                    it.valor
            },
        )
        assertEquals(Centavos(-1050), razao.saldo(ana.id))
        assertEquals(Centavos(1050), razao.saldo(loja.id))
    }

    @Test
    fun `extrato mostra o mais recente primeiro com o saldo depois de cada movimento`() {
        val (ana, bia, loja) = Triple(razao.abrirConta("Ana"), razao.abrirConta("Bia"), razao.abrirConta("Loja"))
        transferir(bia.id, ana.id, 5000, "mesada")
        transferir(ana.id, loja.id, 1200, "almoço")
        transferir(ana.id, loja.id, 300, "café")

        val extrato = razao.extrato(ana.id)
        assertEquals(listOf("café", "almoço", "mesada"), extrato.map { it.descricao })
        assertEquals(listOf(-300L, -1200L, 5000L), extrato.map { it.valor.valor })
        assertEquals(listOf(3500L, 3800L, 5000L), extrato.map { it.saldoDepois.valor })

        // Com limite, o saldo depois continua contando o que ficou de fora
        assertEquals(listOf(3500L), razao.extrato(ana.id, limite = 1).map { it.saldoDepois.valor })
    }

    @Test
    fun `lançamento com mais de duas contas`() {
        val (cliente, loja, taxa) =
            Triple(
                razao.abrirConta("Cliente"),
                razao.abrirConta("Loja"),
                razao.abrirConta("Taxas"),
            )
        razao.registrar(
            NovoLancamento(
                "venda com taxa",
                listOf(
                    NovaPartida(cliente.id, Centavos(-10000)),
                    NovaPartida(loja.id, Centavos(9700)),
                    NovaPartida(taxa.id, Centavos(300)),
                ),
            ),
        )
        assertEquals(Centavos(9700), razao.saldo(loja.id))
        assertEquals(Centavos(300), razao.saldo(taxa.id))
    }

    @Test
    fun `conta que não existe dá erro claro e não grava nada`() {
        val ana = razao.abrirConta("Ana")
        val erro = assertFailsWith<ContaNaoEncontrada> { transferir(ana.id, 999_999_999, 100) }
        assertEquals(setOf(999_999_999L), erro.ids.toSet())
        assertEquals(Centavos.ZERO, razao.saldo(ana.id))
        assertFailsWith<ContaNaoEncontrada> { razao.saldo(999_999_999) }
        assertFailsWith<ContaNaoEncontrada> { razao.extrato(999_999_999) }
    }

    @Test
    fun `recusa nome e limite fora da faixa`() {
        assertFailsWith<IllegalArgumentException> { razao.abrirConta(" ") }
        assertFailsWith<IllegalArgumentException> { razao.abrirConta("x".repeat(101)) }
        val ana = razao.abrirConta("Ana")
        assertFailsWith<IllegalArgumentException> { razao.extrato(ana.id, limite = 0) }
        assertFailsWith<IllegalArgumentException> { razao.extrato(ana.id, limite = 501) }
    }
}
