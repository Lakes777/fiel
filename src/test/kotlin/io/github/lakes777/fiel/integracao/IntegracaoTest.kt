package io.github.lakes777.fiel.integracao

import io.github.lakes777.fiel.CHAVE_DE_TESTE
import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O Foil usando o Fiel pela chave: a Loja Foil cria, acompanha, cancela e estorna as cobranças dela. */
@TesteDeIntegracao
class IntegracaoTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var jdbc: JdbcClient

    /** Um programa, sem cookie nem CSRF: só o cabeçalho Authorization. */
    private fun foil(
        metodo: String,
        caminho: String,
        corpo: Any? = null,
        chave: String = CHAVE_DE_TESTE,
        cabecalhos: Map<String, String> = emptyMap(),
    ) = Navegador(porta).enviar(
        metodo,
        caminho,
        corpo,
        comCsrf = false,
        cabecalhos = cabecalhos + ("Authorization" to "Bearer $chave"),
    )

    private fun cobranca(
        referencia: String = UUID.randomUUID().toString(),
        valor: Long = 1_990,
        descricao: String = "Foil: Dragão de Vidro",
    ) = foil(
        "POST",
        "/cobrancas",
        mapOf("valorCentavos" to valor, "descricao" to descricao, "validadeMinutos" to 5, "referencia" to referencia),
    )

    @Test
    fun `a chave cria a cobrança na Loja Foil, sem login nem CSRF`() {
        val resposta = cobranca()

        assertEquals(201, resposta.status, resposta.corpo)
        val criada = resposta.json()
        // O QR traz o nome mascarado de quem recebe
        assertTrue(criada["qr"].asString().contains("Loja F."), criada["qr"].asString())
        val lida = foil("GET", "/cobrancas/${criada["id"]}")
        assertEquals(200, lida.status)
        assertEquals("pendente", lida.json()["estado"].asString())
        // Nenhuma sessão nasce de um pedido com a chave
        assertTrue(resposta.cabecalhos.allValues("Set-Cookie").none { it.startsWith("FIEL_SESSAO=") })
    }

    @Test
    fun `a mesma referência devolve a mesma cobrança, e com outro valor é recusada`() {
        val referencia = UUID.randomUUID().toString()
        val primeira = cobranca(referencia)
        val segunda = cobranca(referencia)

        assertEquals(201, segunda.status)
        assertEquals("true", segunda.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(primeira.json()["id"], segunda.json()["id"])
        assertEquals(referencia, segunda.json()["referencia"].asString())
        assertEquals(422, cobranca(referencia, valor = 2_000).status)
    }

    @Test
    fun `vinte pedidos com a mesma referência ao mesmo tempo criam uma cobrança só`() {
        val referencia = UUID.randomUUID().toString()
        val largada = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(20)
        val respostas =
            try {
                List(20) {
                    executor.submit<Navegador.Resposta> {
                        largada.await()
                        cobranca(referencia)
                    }
                }.also { largada.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdown()
            }

        assertTrue(respostas.all { it.status == 201 }, respostas.joinToString { "${it.status} ${it.corpo}" })
        assertEquals(1, respostas.map { it.json()["id"].asLong() }.distinct().size)
        val noBanco =
            jdbc
                .sql("select count(*) from cobranca where referencia = :referencia")
                .param("referencia", referencia)
                .query(Long::class.java)
                .single()
        assertEquals(1, noBanco)
    }

    @Test
    fun `chave errada é 401 e caminho fora da lista é 403`() {
        val errada =
            foil("POST", "/cobrancas", mapOf("valorCentavos" to 100, "descricao" to "x"), chave = "a".repeat(64))
        assertEquals(401, errada.status)
        assertEquals("Bearer", errada.cabecalhos.firstValue("WWW-Authenticate").orElse(null))

        // A carteira, as transferências e a lista de cobranças ficam só com quem entra pela tela
        assertEquals(403, foil("GET", "/contas").status)
        assertEquals(403, foil("GET", "/cobrancas").status)
        assertEquals(403, foil("POST", "/transferencias", mapOf("chave" to "x", "valorCentavos" to 1)).status)
        assertEquals(403, foil("DELETE", "/webhook").status)
        // Variações do caminho e do método ficam fora da lista
        assertEquals(403, foil("GET", "/cobrancas/1/").status)
        assertEquals(403, foil("HEAD", "/cobrancas/1").status)
    }

    @Test
    fun `referência fora do formato é 400`() {
        assertEquals(400, cobranca("a b").status)
        assertEquals(400, cobranca("a".repeat(65)).status)
    }

    @Test
    fun `cancela a pendente e estorna a paga, uma vez só`() {
        val pendente = cobranca().json()["id"]
        assertEquals("cancelada", foil("POST", "/cobrancas/$pendente/cancelamento").json()["estado"].asString())

        val paga = cobranca().json()
        val visitante = Navegador(porta).apply { cadastrarEEntrar("Visitante") }
        assertEquals(201, visitante.post("/pagamentos", mapOf("qr" to paga["qr"].asString())).status)
        // Cancelar a paga é 409: é assim que o Foil descobre que precisa estornar
        assertEquals(409, foil("POST", "/cobrancas/${paga["id"]}/cancelamento").status)
        val estorno = mapOf("valorCentavos" to 1_990, "motivo" to "pedido cancelado no Foil")
        val chave = mapOf("Idempotency-Key" to paga["referencia"].asString())
        assertEquals(201, foil("POST", "/cobrancas/${paga["id"]}/estornos", estorno, cabecalhos = chave).status)
        val repetido = foil("POST", "/cobrancas/${paga["id"]}/estornos", estorno, cabecalhos = chave)
        assertEquals("true", repetido.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals("estornada", foil("GET", "/cobrancas/${paga["id"]}").json()["estado"].asString())
        assertEquals(100_000, visitante.get("/contas").json()[0]["saldoCentavos"].asLong())
    }

    @Test
    fun `a chave cadastra o webhook, e o aviso do pagamento leva a referência`() {
        val webhook = foil("PUT", "/webhook", mapOf("url" to "http://localhost:1/foil/fiel/avisos"))
        assertEquals(200, webhook.status, webhook.corpo)
        assertTrue(webhook.json()["segredo"].asString().matches(Regex("[0-9a-f]{64}")))

        val referencia = UUID.randomUUID().toString()
        val qr = cobranca(referencia).json()["qr"].asString()
        Navegador(porta).apply { cadastrarEEntrar("Visitante") }.post("/pagamentos", mapOf("qr" to qr))

        val noAviso =
            jdbc
                .sql(
                    "select dados -> 'cobranca' ->> 'referencia' from aviso where tipo = 'cobranca.paga' " +
                        "and dados -> 'cobranca' ->> 'referencia' = :referencia",
                ).param("referencia", referencia)
                .query(String::class.java)
                .list()
        assertEquals(listOf(referencia), noAviso)
        // Os testes não têm receptor: o aviso não fica rodando na fila dos outros
        jdbc.sql("update aviso set estado = 'falhou', proxima_em = null where estado = 'pendente'").update()
    }

    @Test
    fun `chave curta não sobe, e a comparação não depende do tamanho`() {
        assertThrows<IllegalStateException> { LojaIntegrada.conferir("curta") }
        LojaIntegrada.conferir("")
        LojaIntegrada.conferir("x".repeat(32))
        assertTrue(ChaveDeIntegracao.iguais(CHAVE_DE_TESTE, CHAVE_DE_TESTE))
        assertFalse(ChaveDeIntegracao.iguais(CHAVE_DE_TESTE.dropLast(1), CHAVE_DE_TESTE))
    }
}
