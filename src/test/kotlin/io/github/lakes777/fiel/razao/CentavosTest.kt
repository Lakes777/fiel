package io.github.lakes777.fiel.razao

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CentavosTest {
    @Test
    fun `soma, subtrai e inverte o sinal`() {
        assertEquals(Centavos(150), Centavos(100) + Centavos(50))
        assertEquals(Centavos(-50), Centavos(100) - Centavos(150))
        assertEquals(Centavos(-7), -Centavos(7))
        assertEquals(Centavos(6), listOf(Centavos(1), Centavos(2), Centavos(3)).somar())
        assertEquals(Centavos.ZERO, emptyList<Centavos>().somar())
    }

    @Test
    fun `estourar o Long dá erro em vez de virar um número negativo`() {
        assertFailsWith<ArithmeticException> { Centavos(Long.MAX_VALUE) + Centavos(1) }
        assertFailsWith<ArithmeticException> { Centavos(Long.MIN_VALUE) - Centavos(1) }
        assertFailsWith<ArithmeticException> { -Centavos(Long.MIN_VALUE) }
    }
}
