package io.github.lakes777.fiel.cobrancas

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrTest {
    private val codigo = UUID.fromString("3f2a9c1e-7b4d-4e8a-9f60-1c2d3e4f5a6b")
    private val qr = Qr(codigo, 2_550, "Bob L.")

    /** Troca um campo e recalcula o CRC: um QR "bem formado", mas mexido. */
    private fun comCrcNovo(semCrc: String) = semCrc + "6304" + Qr.crc16(semCrc + "6304")

    @Test
    fun `CRC16 CCITT-FALSE confere com o valor de referência`() {
        // Valor de referência do catálogo de CRCs para "123456789"
        assertEquals("29B1", Qr.crc16("123456789"))
    }

    @Test
    fun `escreve os campos na ordem, com tamanho de 2 dígitos, e lê de volta`() {
        val texto = qr.texto()

        assertEquals(
            "0004FIEL" + "01011" + "2636$codigo" + "54042550" + "5906Bob L." + "6304",
            texto.dropLast(4),
        )
        assertEquals(qr, Qr.ler(texto))
    }

    @Test
    fun `ida e volta com qualquer código, valor e nome, com acento e emoji`() =
        runBlocking<Unit> {
            val nomes = Arb.string(1..40, Arb.of(Codepoint('a'.code), Codepoint('é'.code), Codepoint(0x1F600)))
            checkAll(300, Arb.uuid(), Arb.long(1L..500_000L), nomes) { c, v, nome ->
                val original = Qr(c, v, nome)
                val lido = checkNotNull(Qr.ler(original.texto()))
                assertEquals(original.copy(recebedor = Qr.ateCaracteres(nome, 25)), lido)
                // Nunca meio emoji: nenhuma metade de par UTF-16 sobrando
                assertTrue(lido.recebedor.codePoints().noneMatch { it in 0xD800..0xDFFF }, lido.recebedor)
            }
        }

    @Test
    fun `nome longo é cortado em 25 caracteres de verdade, sem partir emoji`() {
        val nome = "a".repeat(24) + "\uD83D\uDE00" + "b"

        assertEquals("a".repeat(24) + "\uD83D\uDE00", Qr.ateCaracteres(nome, 25))
        assertEquals("Bob L.", Qr.ateCaracteres("Bob L.", 25))
    }

    @Test
    fun `qualquer caractere trocado é pego pelo CRC`() {
        val texto = qr.texto()
        for (i in texto.indices) {
            val trocado = texto.substring(0, i) + (if (texto[i] == '7') '8' else '7') + texto.substring(i + 1)
            assertNull(Qr.ler(trocado), "trocado na posição $i: $trocado")
        }
    }

    @Test
    fun `recusa o que não é um QR do Fiel`() {
        val semCrc = qr.texto().dropLast(8)
        val recusados =
            mapOf(
                "vazio" to "",
                "cortado" to qr.texto().dropLast(1),
                "sem CRC" to semCrc,
                "QR de pagamento EMV (app de banco)" to comCrcNovo("000201" + "2636$codigo" + "54042550"),
                "versão 2" to comCrcNovo(semCrc.replace("01011", "01012")),
                "campo repetido" to comCrcNovo(semCrc + "54042550"),
                "sem código" to comCrcNovo("0004FIEL01011" + "54042550" + "5906Bob L."),
                "código torto" to comCrcNovo("0004FIEL01011" + "26091-1-1-1-1" + "54042550" + "5906Bob L."),
                "valor com letra" to comCrcNovo(semCrc.replace("54042550", "540425a0")),
                "tamanho com letra" to comCrcNovo(semCrc.replace("5906Bob L.", "59x6Bob L.")),
                "valor com sinal" to comCrcNovo(semCrc.replace("54042550", "5405+2550")),
                "valor com zero à esquerda" to comCrcNovo(semCrc.replace("54042550", "540502550")),
                "tamanho em dígitos árabes" to comCrcNovo(semCrc.replace("5906Bob L.", "59\u0660\u0666Bob L.")),
                "tamanho com sinal" to comCrcNovo(semCrc.replace("5906Bob L.", "59+6Bob L.")),
                "CRC no meio" to comCrcNovo(semCrc).let { it.dropLast(8) + "5906Bob L." + it.takeLast(8) },
            )
        for ((caso, texto) in recusados) assertNull(Qr.ler(texto), caso)
    }
}
