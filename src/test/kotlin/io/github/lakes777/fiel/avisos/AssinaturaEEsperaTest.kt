package io.github.lakes777.fiel.avisos

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssinaturaEEsperaTest {
    private val segredo = "a".repeat(64)
    private val corpo = """{"id":"x","tipo":"cobranca.paga"}"""

    @Test
    fun `a assinatura confere com o mesmo segredo, carimbo e corpo`() {
        val cabecalho = Assinatura.assinar(segredo, 1_760_000_000, corpo)

        assertTrue(cabecalho.matches(Regex("t=1760000000,v1=[0-9a-f]{64}")))
        assertTrue(Assinatura.confere(segredo, cabecalho, corpo))
    }

    @Test
    fun `corpo mexido, carimbo trocado, outro segredo ou cabeçalho torto não conferem`() {
        val cabecalho = Assinatura.assinar(segredo, 1_760_000_000, corpo)

        assertFalse(Assinatura.confere(segredo, cabecalho, corpo.replace("paga", "expirada")))
        assertFalse(Assinatura.confere(segredo, cabecalho.replace("t=1760000000", "t=1760009999"), corpo))
        assertFalse(Assinatura.confere("b".repeat(64), cabecalho, corpo))
        for (torto in listOf(
            "",
            "t=1",
            "v1=abc",
            "t=x,v1=abc",
            "lixo",
        )) {
            assertFalse(Assinatura.confere(segredo, torto, corpo), torto)
        }
    }

    @Test
    fun `a espera dobra a cada tentativa até 1 hora, com até 20 por cento a mais`() {
        val semFolga = (1..9).map { Entregador.espera(it, sorteio = 0.0) }

        assertEquals(
            listOf(30L, 60, 120, 240, 480, 960, 1920, 3600, 3600).map(Duration::ofSeconds),
            semFolga,
        )
        assertEquals(Duration.ofSeconds(36), Entregador.espera(1, sorteio = 1.0))
        assertEquals(Duration.ofSeconds(4320), Entregador.espera(20, sorteio = 1.0))
    }
}
