package io.github.lakes777.fiel.razao

import java.time.Instant

data class Conta(
    val id: Long,
    val nome: String,
    val criadaEm: Instant,
)

/** Quanto um lançamento muda o saldo de uma conta: positivo entra, negativo sai. */
data class NovaPartida(
    val contaId: Long,
    val valor: Centavos,
)

/**
 * Um lançamento ainda não gravado. Confere as regras das partidas dobradas já aqui, para o erro
 * sair claro e cedo; o banco confere de novo no commit (V1__razao_contabil.sql) e é a palavra final.
 */
data class NovoLancamento(
    val descricao: String,
    val partidas: List<NovaPartida>,
) {
    init {
        require(descricao.isNotBlank() && descricao.length <= MAX_DESCRICAO) {
            "a descrição precisa ter de 1 a $MAX_DESCRICAO caracteres"
        }
        require(partidas.size >= 2) { "um lançamento precisa de pelo menos duas partidas" }
        require(partidas.none { it.valor == Centavos.ZERO }) { "partida de valor zero não muda nada" }
        require(partidas.map { it.contaId }.toSet().size == partidas.size) {
            "a mesma conta aparece mais de uma vez no lançamento"
        }
        val soma =
            try {
                partidas.map { it.valor }.somar()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("valores grandes demais para somar")
            }
        require(soma == Centavos.ZERO) { "as partidas somam ${soma.valor} centavos; precisam somar zero" }
    }

    companion object {
        const val MAX_DESCRICAO = 200
    }
}

data class Partida(
    val id: Long,
    val contaId: Long,
    val valor: Centavos,
)

data class Lancamento(
    val id: Long,
    val descricao: String,
    val criadoEm: Instant,
    val partidas: List<Partida>,
)

/** Uma linha do extrato: o que entrou ou saiu e como ficou o saldo logo depois. */
data class Movimento(
    val lancamentoId: Long,
    val descricao: String,
    val em: Instant,
    val valor: Centavos,
    val saldoDepois: Centavos,
)

class ContaNaoEncontrada(
    val ids: Collection<Long>,
) : RuntimeException("conta(s) não encontrada(s): ${ids.sorted().joinToString()}")
