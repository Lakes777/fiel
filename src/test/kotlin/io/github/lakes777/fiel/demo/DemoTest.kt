package io.github.lakes777.fiel.demo

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A demonstração ligada (os testes de integração rodam com fiel.demo=true). */
@TesteDeIntegracao
class DemoTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var limites: LimitesDeUso

    private fun navegador() = Navegador(porta)

    private fun Navegador.saldo() = get("/contas").json()[0]["saldoCentavos"].asLong()

    /** Entra como visitante e devolve o roteiro que a tela mostraria. */
    private fun Navegador.visitante(): Roteiro {
        val resposta = post("/demo/visitante")
        assertEquals(201, resposta.status, resposta.corpo)
        val roteiro = resposta.json()["roteiro"]
        return Roteiro(
            roteiro["qrDaLoja"].asString(),
            roteiro["chaveDaAna"].asString(),
            roteiro["chaveDoBob"].asString(),
        )
    }

    @Test
    fun `a tela sabe que a demonstração está ligada`() {
        assertEquals(true, navegador().get("/demo").json()["ativa"].asBoolean())
    }

    @Test
    fun `o visitante entra direto, com os R$ 1000 de todo cadastro`() {
        val visitante = navegador()
        val roteiro = visitante.visitante()

        val eu = visitante.get("/eu")
        assertEquals(200, eu.status)
        assertTrue(eu.json()["email"].asString().startsWith("visitante-"))
        assertEquals(100_000, visitante.saldo())
        assertEquals("ana@exemplo.fiel", roteiro.chaveDaAna)
    }

    @Test
    fun `o visitante paga a camiseta da Loja Exemplo pelo QR do roteiro e transfere para a Ana`() {
        val visitante = navegador()
        val roteiro = visitante.visitante()

        val pagamento = visitante.post("/pagamentos", mapOf("qr" to roteiro.qrDaLoja))
        assertEquals(201, pagamento.status, pagamento.corpo)
        assertEquals("Camiseta Fiel", pagamento.json()["descricao"].asString())
        assertEquals(100_000 - 5_990, visitante.saldo())

        val transferencia = visitante.transferir(roteiro.chaveDaAna, 1_000)
        assertEquals(201, transferencia.status, transferencia.corpo)
        assertEquals(100_000 - 5_990 - 1_000, visitante.saldo())
    }

    @Test
    fun `o Bob do visitante paga a cobrança que ele criou`() {
        val visitante = navegador()
        visitante.visitante()
        val cobranca = visitante.post("/cobrancas", mapOf("valorCentavos" to 2_500, "descricao" to "Café"))
        assertEquals(201, cobranca.status, cobranca.corpo)
        val id = cobranca.json()["id"].asLong()

        val pago = visitante.post("/demo/cobrancas/$id/bob-paga")

        assertEquals(200, pago.status, pago.corpo)
        assertEquals("paga", visitante.get("/cobrancas/$id").json()["estado"].asString())
        assertEquals(100_000 + 2_500, visitante.saldo())
    }

    @Test
    fun `cada visitante tem o próprio Bob`() {
        assertNotEquals(navegador().visitante().chaveDoBob, navegador().visitante().chaveDoBob)
    }

    @Test
    fun `o Bob não paga cobrança de outra pessoa`() {
        val outra = navegador().apply { cadastrarEEntrar("Carla Dias") }
        val id = outra.post("/cobrancas", mapOf("valorCentavos" to 2_500, "descricao" to "Café")).json()["id"].asLong()
        val visitante = navegador().apply { visitante() }

        assertEquals(404, visitante.post("/demo/cobrancas/$id/bob-paga").status)
        assertEquals("pendente", outra.get("/cobrancas/$id").json()["estado"].asString())
    }

    @Test
    fun `conta normal não tem Bob`() {
        val pessoa = navegador().apply { cadastrarEEntrar("Carla Dias") }
        val id = pessoa.post("/cobrancas", mapOf("valorCentavos" to 2_500, "descricao" to "Café")).json()["id"].asLong()

        assertEquals(422, pessoa.post("/demo/cobrancas/$id/bob-paga").status)
    }

    @Test
    fun `sem login o Bob não paga nada, e o visitante precisa do token CSRF`() {
        assertEquals(401, navegador().post("/demo/cobrancas/1/bob-paga").status)
        assertEquals(403, navegador().enviar("POST", "/demo/visitante", comCsrf = false).status)
    }

    @Test
    fun `ninguém se cadastra com e-mail da demonstração para fazer o papel da Loja Exemplo`() {
        for (email in listOf("loja@exemplo.fiel", "ANA@Exemplo.Fiel", "bob-0000aaaa@demo.fiel")) {
            val resposta =
                navegador().post("/cadastro", mapOf("nome" to "Golpe", "email" to email, "senha" to Navegador.SENHA))
            assertEquals(422, resposta.status, email)
        }
    }

    @Test
    fun `criar visitante conta no limite de cadastros por IP`() {
        val resposta =
            navegador().enviar("POST", "/demo/visitante", cabecalhos = mapOf("X-Forwarded-For" to "203.0.113.42"))
        assertEquals(201, resposta.status)
        assertTrue(limites.cadastro.contando("203.0.113.42"))
    }
}
