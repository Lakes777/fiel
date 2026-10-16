package io.github.lakes777.fiel.chaves

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TesteDeIntegracao
class ChavesEContasTest {
    @LocalServerPort
    private var porta = 0

    private fun navegador() = Navegador(porta)

    @Test
    fun `Bob não vê o extrato da Ana, responde igual a uma conta que não existe`() {
        val ana = navegador().cadastrarEEntrar()
        val bob = navegador()
        bob.cadastrarEEntrar("Bob Lima")

        val daAna = bob.get("/contas/${ana.carteira}/extrato")
        val inexistente = bob.get("/contas/999999999/extrato")

        assertEquals(404, daAna.status)
        assertEquals(daAna.json()["detail"], inexistente.json()["detail"])
        assertEquals(1, bob.get("/contas").json().size())
    }

    @Test
    fun `cria a chave do próprio e-mail e uma aleatória, e lista as duas`() {
        val n = navegador()
        val ana = n.cadastrarEEntrar()

        val email = n.post("/chaves", mapOf("tipo" to "email"))
        val aleatoria = n.post("/chaves", mapOf("tipo" to "aleatoria"))

        assertEquals(201, email.status)
        assertEquals(ana.email, email.json()["valor"].asString())
        assertEquals(201, aleatoria.status)
        assertTrue(Regex("[0-9a-f-]{36}").matches(aleatoria.json()["valor"].asString()))
        assertEquals(
            listOf("email", "aleatoria"),
            n.get("/chaves").json().let { lista ->
                List(lista.size()) { lista[it]["tipo"].asString() }
            },
        )
    }

    @Test
    fun `a chave do e-mail não se repete e a conta tem no máximo 5 chaves`() {
        val n = navegador()
        n.cadastrarEEntrar()
        assertEquals(201, n.post("/chaves", mapOf("tipo" to "email")).status)

        assertEquals(409, n.post("/chaves", mapOf("tipo" to "email")).status)
        repeat(4) { assertEquals(201, n.post("/chaves", mapOf("tipo" to "aleatoria")).status) }
        assertEquals(409, n.post("/chaves", mapOf("tipo" to "aleatoria")).status)
        assertEquals(400, n.post("/chaves", mapOf("tipo" to "cpf")).status)
        assertEquals(5, n.get("/chaves").json().size())
    }

    @Test
    fun `Bob não apaga a chave da Ana, e a Ana apaga a dela`() {
        val ana = navegador()
        ana.cadastrarEEntrar()
        val chave = ana.post("/chaves", mapOf("tipo" to "aleatoria")).json()["id"].asLong()
        val bob = navegador()
        bob.cadastrarEEntrar("Bob Lima")

        assertEquals(404, bob.delete("/chaves/$chave").status)
        assertEquals(1, ana.get("/chaves").json().size())
        assertEquals(204, ana.delete("/chaves/$chave").status)
        assertEquals(0, ana.get("/chaves").json().size())
    }

    @Test
    fun `consultar uma chave mostra só o primeiro nome e a inicial do último`() {
        val ana = navegador()
        val dadosDaAna = ana.cadastrarEEntrar("Ana Maria Souza")
        ana.post("/chaves", mapOf("tipo" to "email"))
        val bob = navegador()
        bob.cadastrarEEntrar("Bob Lima")

        val achada = bob.post("/chaves/consulta", mapOf("valor" to " ${dadosDaAna.email.uppercase()} "))
        val nenhuma = bob.post("/chaves/consulta", mapOf("valor" to "ninguem@teste.fiel"))

        assertEquals(200, achada.status)
        assertEquals("Ana S.", achada.json()["nome"].asString())
        assertEquals(404, nenhuma.status)
    }

    @Test
    fun `consultar chaves tem limite por minuto, contra varredura`() {
        val n = navegador()
        n.cadastrarEEntrar()
        repeat(30) { assertEquals(404, n.post("/chaves/consulta", mapOf("valor" to "x$it@teste.fiel")).status) }

        assertEquals(429, n.post("/chaves/consulta", mapOf("valor" to "outra@teste.fiel")).status)
    }

    @Test
    fun `mascarar nome`() {
        assertEquals("Ana S.", mascararNome("Ana Maria Souza"))
        assertEquals("Ana S.", mascararNome("  Ana   Souza "))
        assertEquals("Ana", mascararNome("Ana"))
    }
}
