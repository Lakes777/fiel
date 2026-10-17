package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TesteDeIntegracao
class EstornosTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var jdbc: JdbcClient

    @AfterEach
    fun soltarRelogio() = relogio.soltar()

    private inner class Cliente(
        nome: String,
    ) {
        val navegador = Navegador(porta)
        val pessoa = navegador.cadastrarEEntrar(nome)
        val chave: String by lazy {
            navegador.post("/chaves", mapOf("tipo" to "aleatoria")).json()["valor"].asString()
        }

        fun saldo() = navegador.get("/contas").json()[0]["saldoCentavos"].asLong()

        /** Cria uma cobrança e devolve o id e o QR. */
        fun cobrar(valor: Long): Pair<Long, String> {
            val resposta = navegador.post("/cobrancas", mapOf("valorCentavos" to valor, "descricao" to "Camiseta"))
            check(resposta.status == 201) { "cobrança falhou: ${resposta.corpo}" }
            return resposta.json()["id"].asLong() to resposta.json()["qr"].asString()
        }

        fun pagar(qr: String) = navegador.post("/pagamentos", mapOf("qr" to qr))

        fun estornar(
            cobranca: Long,
            valor: Long,
            motivo: String? = null,
            idempotencia: String? = UUID.randomUUID().toString(),
        ) = navegador.enviar(
            "POST",
            "/cobrancas/$cobranca/estornos",
            mapOf("valorCentavos" to valor, "motivo" to motivo),
            cabecalhos = idempotencia?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(),
        )

        fun cobranca(id: Long) = navegador.get("/cobrancas/$id").json()
    }

    /** Bob cobra [valor] e Ana paga; devolve os dois e o id da cobrança. */
    private fun paga(valor: Long): Triple<Cliente, Cliente, Long> {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val (id, qr) = loja.cobrar(valor)
        check(ana.pagar(qr).status == 201)
        return Triple(loja, ana, id)
    }

    private fun parar(brasilia: String) = relogio.parar(OffsetDateTime.parse("$brasilia-03:00").toInstant())

    private fun <T> rodarJuntos(
        vezes: Int,
        acao: (Int) -> T,
    ): List<T> {
        val largada = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(vezes)
        try {
            val futuros =
                List(vezes) { i ->
                    executor.submit<T> {
                        largada.await()
                        acao(i)
                    }
                }
            largada.countDown()
            return futuros.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Bob devolve em duas partes e a cobrança vai de paga a estornada`() {
        val (loja, ana, id) = paga(2_550)

        val primeira = loja.estornar(id, 1_000, "Tamanho errado")
        assertEquals(201, primeira.status)
        assertEquals(1_000, primeira.json()["valorCentavos"].asLong())
        assertEquals("Tamanho errado", primeira.json()["motivo"].asString())
        assertEquals("parcialmente_estornada", loja.cobranca(id)["estado"].asString())
        assertEquals(1_000, loja.cobranca(id)["estornadoCentavos"].asLong())
        assertEquals(98_450, ana.saldo())
        assertEquals(101_550, loja.saldo())

        assertEquals(201, loja.estornar(id, 1_550).status)
        assertEquals("estornada", loja.cobranca(id)["estado"].asString())
        assertEquals(100_000, ana.saldo())
        assertEquals(100_000, loja.saldo())
        assertEquals(
            "Estorno de Bob L. a Ana S.: Camiseta",
            ana.navegador
                .get("/contas/${ana.pessoa.carteira}/extrato")
                .json()[0]["descricao"]
                .asString(),
        )
        val lista = loja.navegador.get("/cobrancas/$id/estornos").json()
        assertEquals(listOf(1_000L, 1_550L), (0 until lista.size()).map { lista[it]["valorCentavos"].asLong() })

        val maisUm = loja.estornar(id, 1)
        assertEquals(409, maisUm.status)
        assertTrue(maisUm.corpo.contains("estornada"))
    }

    @Test
    fun `não devolve mais do que foi pago`() {
        val (loja, _, id) = paga(1_000)
        assertEquals(201, loja.estornar(id, 600).status)

        val demais = loja.estornar(id, 401)
        assertEquals(422, demais.status)
        assertTrue(demais.corpo.contains("R$ 4,00"), demais.corpo)
        assertEquals(201, loja.estornar(id, 400).status)
    }

    @Test
    fun `só o lojista estorna, e só cobrança paga`() {
        val (loja, ana, id) = paga(1_000)
        val (pendente, _) = loja.cobrar(500)
        val (cancelada, _) = loja.cobrar(500)
        loja.navegador.post("/cobrancas/$cancelada/cancelamento")

        assertEquals(404, ana.estornar(id, 100).status)
        assertEquals(404, ana.navegador.get("/cobrancas/$id/estornos").status)
        assertEquals(404, Cliente("Eva Reis").estornar(id, 100).status)
        assertEquals(409, loja.estornar(pendente, 100).status)
        assertEquals(409, loja.estornar(cancelada, 100).status)
        assertEquals(400, loja.estornar(id, 0).status)
        assertEquals(400, loja.estornar(id, 100, motivo = "x".repeat(141)).status)
        assertEquals(100_000, ana.saldo() + 1_000)
    }

    @Test
    fun `sem saldo, o lojista não devolve`() {
        val (loja, ana, id) = paga(1_000)
        // Bob manda quase tudo para a Ana: sobra R$ 5
        assertEquals(201, loja.navegador.transferir(ana.chave, 100_500).status)

        val semSaldo = loja.estornar(id, 1_000)
        assertEquals(422, semSaldo.status)
        assertTrue(semSaldo.corpo.contains("saldo insuficiente"), semSaldo.corpo)
        assertEquals("paga", loja.cobranca(id)["estado"].asString())
    }

    @Test
    fun `Idempotency-Key obrigatória, e o mesmo pedido repetido devolve uma vez só`() {
        val (loja, ana, id) = paga(1_000)
        assertEquals(400, loja.estornar(id, 300, idempotencia = null).status)

        val chave = UUID.randomUUID().toString()
        val primeiro = loja.estornar(id, 300, "Defeito", chave)
        val repetido = loja.estornar(id, 300, "Defeito", chave)
        assertEquals(201, repetido.status)
        assertEquals("true", repetido.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(primeiro.json()["id"].asLong(), repetido.json()["id"].asLong())
        assertEquals(99_300, ana.saldo())

        assertEquals(422, loja.estornar(id, 400, "Defeito", chave).status)
        assertEquals(422, loja.estornar(id, 300, "Outro motivo", chave).status)
    }

    @Test
    fun `20 cópias do mesmo estorno ao mesmo tempo devolvem uma vez`() {
        val (loja, ana, id) = paga(1_000)
        val chave = UUID.randomUUID().toString()

        val respostas = rodarJuntos(20) { loja.estornar(id, 300, idempotencia = chave) }

        assertTrue(respostas.all { it.status == 201 }, respostas.map { it.status }.toString())
        assertEquals(1, respostas.map { it.json()["id"].asLong() }.toSet().size)
        assertEquals(99_300, ana.saldo())
    }

    @Test
    fun `20 estornos diferentes de R$ 2 ao mesmo tempo numa cobrança de R$ 10 passam só 5`() {
        val (loja, ana, id) = paga(1_000)

        val respostas = rodarJuntos(20) { loja.estornar(id, 200) }

        assertEquals(5, respostas.count { it.status == 201 })
        assertEquals(15, respostas.count { it.status == 409 || it.status == 422 })
        assertEquals("estornada", loja.cobranca(id)["estado"].asString())
        assertEquals(100_000, ana.saldo())
    }

    @Test
    fun `estornos simultâneos que não fecham a cobrança somam certo, sem 500`() {
        val (loja, ana, id) = paga(1_000)
        loja.navegador.enviar("PUT", "/webhook", mapOf("url" to "http://127.0.0.1:9/avisos"))

        val dois = rodarJuntos(2) { loja.estornar(id, 300) }
        assertEquals(listOf(201, 201), dois.map { it.status })
        val avisados =
            jdbc
                .sql(
                    """
                    select (dados -> 'cobranca' ->> 'estornadoCentavos')::bigint from aviso
                    where usuario_id = :u and tipo = 'cobranca.estornada' order by id
                    """.trimIndent(),
                ).param("u", loja.pessoa.id)
                .query(Long::class.java)
                .list()
        assertEquals(listOf(300L, 600L), avisados)

        // Faltam R$ 4: dois de R$ 4 ao mesmo tempo, passa um e o outro é recusado (nunca 500)
        val disputa = rodarJuntos(2) { loja.estornar(id, 400) }.map { it.status }.sorted()
        assertEquals(201, disputa[0], disputa.toString())
        assertTrue(disputa[1] == 409 || disputa[1] == 422, disputa.toString())
        assertEquals(100_000, ana.saldo())
        jdbc
            .sql("update aviso set estado = 'falhou', proxima_em = null where usuario_id = :u and estado = 'pendente'")
            .param("u", loja.pessoa.id)
            .update()
    }

    @Test
    fun `o estorno não conta no limite noturno de quem devolve`() {
        parar("2026-10-16T23:00:00")
        val (loja, ana, id) = paga(1_000)
        // Bob gasta o limite noturno inteiro (R$ 1.000) e ainda consegue devolver
        assertEquals(201, loja.navegador.transferir(ana.chave, 100_000).status)
        assertEquals(422, loja.navegador.transferir(ana.chave, 1).status)

        assertEquals(201, loja.estornar(id, 1_000).status)
    }

    @Test
    fun `o estorno não devolve o limite de quem pagou`() {
        parar("2026-10-16T23:00:00")
        val (loja, ana, _) = paga(1)
        val (cara, qr) = loja.cobrar(99_999)
        assertEquals(201, ana.pagar(qr).status)
        // Ana usou o limite noturno inteiro; Bob devolve a cobrança grande
        assertEquals(201, loja.estornar(cara, 99_999).status)

        val (_, outra) = loja.cobrar(1)
        val depois = ana.pagar(outra)
        assertEquals(422, depois.status)
        assertTrue(depois.corpo.contains("noturno"), depois.corpo)
    }

    @Test
    fun `quem pagou e repete o pagamento depois do estorno recebe o mesmo pagamento`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val (id, qr) = loja.cobrar(1_000)
        assertEquals(201, ana.pagar(qr).status)
        assertEquals(201, loja.estornar(id, 1_000).status)

        val repetido = ana.pagar(qr)
        assertEquals(201, repetido.status)
        assertEquals("true", repetido.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(100_000, ana.saldo())
        assertEquals(409, Cliente("Eva Reis").pagar(qr).status)
    }

    @Test
    fun `o estorno gera aviso ao lojista na mesma transação`() {
        val (loja, _, id) = paga(1_000)
        loja.navegador.enviar("PUT", "/webhook", mapOf("url" to "http://127.0.0.1:9/avisos"))

        loja.estornar(id, 400, "Defeito")

        val dados =
            jdbc
                .sql("select dados::text from aviso where usuario_id = :u and tipo = 'cobranca.estornada'")
                .param("u", loja.pessoa.id)
                .query(String::class.java)
                .single()
        val json = Navegador.JSON.readTree(dados)
        assertEquals(id, json["cobranca"]["id"].asLong())
        assertEquals("parcialmente_estornada", json["cobranca"]["estado"].asString())
        assertEquals(400, json["cobranca"]["estornadoCentavos"].asLong())
        assertEquals(400, json["estorno"]["valorCentavos"].asLong())
        // Os pendentes deste teste não ficam na fila dos outros
        jdbc
            .sql("update aviso set estado = 'falhou', proxima_em = null where usuario_id = :u and estado = 'pendente'")
            .param("u", loja.pessoa.id)
            .update()
    }
}
