package io.github.lakes777.fiel.razao

/**
 * Dinheiro em centavos. Um tipo próprio em vez de Long solto: o compilador não deixa passar o id
 * de uma conta onde se espera um valor, e as contas estouram com erro em vez de dar a volta.
 */
@JvmInline
value class Centavos(
    val valor: Long,
) : Comparable<Centavos> {
    operator fun plus(outro: Centavos) = Centavos(Math.addExact(valor, outro.valor))

    operator fun minus(outro: Centavos) = Centavos(Math.subtractExact(valor, outro.valor))

    operator fun unaryMinus() = Centavos(Math.negateExact(valor))

    override fun compareTo(other: Centavos) = valor.compareTo(other.valor)

    companion object {
        val ZERO = Centavos(0)
    }
}

fun Iterable<Centavos>.somar(): Centavos = fold(Centavos.ZERO, Centavos::plus)
