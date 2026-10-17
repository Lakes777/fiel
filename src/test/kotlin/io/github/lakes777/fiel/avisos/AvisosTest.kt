package io.github.lakes777.fiel.avisos

import com.sun.net.httpserver.HttpServer
import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.cobrancas.Cobrancas
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TesteDeIntegracao
class AvisosTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var entregador: Entregador

    @Autowired
    private lateinit var cobrancas: Cobrancas

    @Autowired
    private lateinit var jdbc: JdbcClient

    private val receptores = mutableListOf<Receptor>()

    /** O sistema de um lojista: guarda o que recebe e responde com o status que o teste mandar. */
    private class Receptor : AutoCloseable {
        data class Recebido(
            val cabecalhos: Map<String, String>,
            val corpo: String,
        )

        val recebidos: MutableList<Recebido> = Collections.synchronizedList(mutableListOf())
        val status = AtomicInteger(200)

        /** Manda os cabeçalhos e depois o corpo 1 byte por segundo, sem nunca terminar. */
        @Volatile
        var lento = false
        private val servidor =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/avisos") { troca ->
                    val corpo = troca.requestBody.readAllBytes().decodeToString()
                    val cabecalhos = troca.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
                    recebidos += Recebido(cabecalhos, corpo)
                    if (status.get() in 300..399) troca.responseHeaders.add("Location", "http://127.0.0.1:1/")
                    if (lento) {
                        troca.sendResponseHeaders(200, 1_000_000)
                        runCatching {
                            repeat(60) {
                                troca.responseBody.write('x'.code)
                                troca.responseBody.flush()
                                Thread.sleep(1_000)
                            }
                        }
                    } else {
                        troca.sendResponseHeaders(status.get(), -1)
                    }
                    troca.close()
                }
                executor = Executors.newFixedThreadPool(8)
                start()
            }
        val url = "http://127.0.0.1:${servidor.address.port}/avisos"

        override fun close() = servidor.stop(0)
    }

    private fun receptor() = Receptor().also { receptores += it }

    /** Avisos pendentes de outros testes não entram na conta deste. */
    @BeforeEach
    @AfterEach
    fun limparFila() {
        relogio.soltar()
        jdbc.sql("update aviso set estado = 'falhou', proxima_em = null where estado = 'pendente'").update()
        receptores.forEach(Receptor::close)
        receptores.clear()
    }

    private inner class Cliente(
        nome: String,
    ) {
        val navegador = Navegador(porta)
        val pessoa = navegador.cadastrarEEntrar(nome)

        /** Cadastra o webhook e devolve o segredo. */
        fun ouvir(receptor: Receptor): String {
            val resposta = navegador.enviar("PUT", "/webhook", mapOf("url" to receptor.url))
            check(resposta.status == 200) { "webhook falhou: ${resposta.corpo}" }
            return resposta.json()["segredo"].asString()
        }

        fun avisos(estado: String? = null): JsonNode =
            navegador.get("/avisos" + (estado?.let { "?estado=$it" } ?: "")).json()

        fun qr(valor: Long) =
            navegador.post("/cobrancas", mapOf("valorCentavos" to valor, "descricao" to "Camiseta")).json()
    }

    private fun parar(instante: Instant) = relogio.parar(instante)

    /** O que um lojista faria, sem usar o código do Fiel: HMAC-SHA256 de "t.corpo". */
    private fun assinaturaConfere(
        segredo: String,
        recebido: Receptor.Recebido,
    ): Boolean {
        val (t, v1) =
            recebido.cabecalhos
                .getValue("fiel-assinatura")
                .split(",")
                .map { it.substringAfter("=") }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(segredo.toByteArray(), "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal("$t.${recebido.corpo}".toByteArray())) == v1
    }

    @Test
    fun `Ana paga a cobrança do Bob e o sistema do Bob recebe o aviso assinado`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val receptor = receptor()
        val segredo = loja.ouvir(receptor)
        assertTrue(segredo.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            receptor.url,
            loja.navegador
                .get("/webhook")
                .json()["url"]
                .asString(),
        )
        assertTrue(loja.navegador.get("/webhook").json()["segredo"] == null)

        val cobranca = loja.qr(2_550)
        assertEquals(201, ana.navegador.post("/pagamentos", mapOf("qr" to cobranca["qr"].asString())).status)
        assertEquals("pendente", loja.avisos()[0]["estado"].asString())
        entregador.entregarLote()

        val recebido = receptor.recebidos.single()
        assertEquals("cobranca.paga", recebido.cabecalhos["fiel-evento"])
        assertTrue(assinaturaConfere(segredo, recebido))
        val corpo = Navegador.JSON.readTree(recebido.corpo)
        assertEquals(recebido.cabecalhos["fiel-evento-id"], corpo["id"].asString())
        assertEquals(cobranca["id"].asLong(), corpo["dados"]["cobranca"]["id"].asLong())
        assertEquals(2_550, corpo["dados"]["cobranca"]["valorCentavos"].asLong())
        assertEquals("Ana S.", corpo["dados"]["pagador"].asString())
        val aviso = loja.avisos()[0]
        assertEquals("entregue", aviso["estado"].asString())
        assertEquals(1, aviso["tentativas"].asInt())
    }

    @Test
    fun `transferência recebida e cobrança expirada também avisam`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val receptor = receptor()
        loja.ouvir(receptor)
        val chave =
            loja.navegador
                .post("/chaves", mapOf("tipo" to "aleatoria"))
                .json()["valor"]
                .asString()
        parar(Instant.parse("2026-03-10T15:00:00Z"))

        assertEquals(201, ana.navegador.transferir(chave, 1_000).status)
        loja.navegador.post("/cobrancas", mapOf("valorCentavos" to 500, "descricao" to "Boné", "validadeMinutos" to 1))
        parar(Instant.parse("2026-03-10T15:01:00Z"))
        assertTrue(cobrancas.expirarVencidas() >= 1)
        entregador.entregarLote()

        val tipos = receptor.recebidos.map { it.cabecalhos["fiel-evento"] }
        assertEquals(listOf("transferencia.recebida", "cobranca.expirada"), tipos)
        val transferencia = Navegador.JSON.readTree(receptor.recebidos[0].corpo)["dados"]
        assertEquals(1_000, transferencia["transferencia"]["valorCentavos"].asLong())
        assertEquals("Ana S.", transferencia["de"].asString())
        assertEquals(
            "Boné",
            Navegador.JSON.readTree(receptor.recebidos[1].corpo)["dados"]["cobranca"]["descricao"].asString(),
        )
    }

    @Test
    fun `sem webhook ou com o pagamento desfeito, não nasce aviso`() {
        val (semWebhook, comWebhook, ana) = Triple(Cliente("Eva Rocha"), Cliente("Bob Lima"), Cliente("Ana Souza"))
        comWebhook.ouvir(receptor())

        assertEquals(201, ana.navegador.post("/pagamentos", mapOf("qr" to semWebhook.qr(100)["qr"].asString())).status)
        // Saldo insuficiente: a transação do pagamento desfaz, e o aviso (gravado nela) some junto
        assertEquals(
            422,
            ana.navegador.post("/pagamentos", mapOf("qr" to comWebhook.qr(500_000)["qr"].asString())).status,
        )

        assertEquals(0, semWebhook.avisos().size())
        assertEquals(0, comWebhook.avisos().size())
    }

    @Test
    fun `lojista fora do ar, tenta de novo com espera crescente e, depois de 8, vai para a fila de falhas`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val receptor = receptor().apply { status.set(503) }
        loja.ouvir(receptor)
        var agora = Instant.parse("2026-03-10T15:00:00Z")
        parar(agora)
        ana.navegador.post("/pagamentos", mapOf("qr" to loja.qr(1_000)["qr"].asString()))

        entregador.entregarLote()
        val depoisDaPrimeira = loja.avisos()[0]
        assertEquals("pendente", depoisDaPrimeira["estado"].asString())
        assertEquals("HTTP 503", depoisDaPrimeira["ultimaResposta"].asString())
        val proxima = Instant.parse(depoisDaPrimeira["proximaTentativaEm"].asString())
        assertTrue(Duration.between(agora, proxima) in Duration.ofSeconds(30)..Duration.ofSeconds(36), "$proxima")
        // Antes da hora, ninguém tenta
        entregador.entregarLote()
        assertEquals(1, receptor.recebidos.size)

        repeat(Entregador.MAX_TENTATIVAS - 1) {
            agora = agora.plus(Duration.ofHours(2))
            parar(agora)
            entregador.entregarLote()
        }
        assertEquals(Entregador.MAX_TENTATIVAS, receptor.recebidos.size)
        val falhou = loja.avisos("falhou")[0]
        assertEquals(Entregador.MAX_TENTATIVAS, falhou["tentativas"].asInt())
        assertEquals(
            1,
            receptor.recebidos
                .map { it.cabecalhos["fiel-evento-id"] }
                .toSet()
                .size,
        )

        // O lojista voltou: reenvia da fila de falhas, com o mesmo id de evento
        receptor.status.set(204)
        val reenvio = loja.navegador.post("/avisos/${falhou["id"].asLong()}/reenvio")
        assertEquals(200, reenvio.status)
        assertEquals("pendente", reenvio.json()["estado"].asString())
        assertEquals(409, loja.navegador.post("/avisos/${falhou["id"].asLong()}/reenvio").status)
        entregador.entregarLote()
        assertEquals("entregue", loja.avisos()[0]["estado"].asString())
        assertEquals(
            1,
            receptor.recebidos
                .map { it.cabecalhos["fiel-evento-id"] }
                .toSet()
                .size,
        )
    }

    @Test
    fun `redirecionamento e servidor fechado contam como falha, sem seguir para outro endereço`() {
        val (loja, eva, ana) = Triple(Cliente("Bob Lima"), Cliente("Eva Rocha"), Cliente("Ana Souza"))
        loja.ouvir(receptor().apply { status.set(302) })
        val fechado = receptor()
        eva.ouvir(fechado)
        fechado.close()

        ana.navegador.post("/pagamentos", mapOf("qr" to loja.qr(100)["qr"].asString()))
        ana.navegador.post("/pagamentos", mapOf("qr" to eva.qr(100)["qr"].asString()))
        entregador.entregarLote()

        assertEquals("HTTP 302", loja.avisos()[0]["ultimaResposta"].asString())
        assertEquals("pendente", loja.avisos()[0]["estado"].asString())
        assertTrue(eva.avisos()[0]["ultimaResposta"].asString().startsWith("falha de rede"))
    }

    @Test
    fun `lojista que responde 1 byte por segundo não prende a entrega além do prazo`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        loja.ouvir(receptor().apply { lento = true })
        ana.navegador.post("/pagamentos", mapOf("qr" to loja.qr(100)["qr"].asString()))

        val inicio = System.nanoTime()
        entregador.entregarLote()
        val levou = Duration.ofNanos(System.nanoTime() - inicio)

        // Prazo de 3 s nos testes; sem ele, este envio levaria 60 s (ou para sempre)
        assertTrue(levou < Duration.ofSeconds(8), "levou $levou")
        assertEquals("sem resposta em 3 s", loja.avisos()[0]["ultimaResposta"].asString())
        assertEquals("pendente", loja.avisos()[0]["estado"].asString())
    }

    @Test
    fun `aviso pendente sem endereço vai para a fila de falhas e não atrasa os outros`() {
        val (semEndereco, loja, ana) = Triple(Cliente("Eva Rocha"), Cliente("Bob Lima"), Cliente("Ana Souza"))
        val receptor = receptor()
        loja.ouvir(receptor)
        // A corrida rara: o aviso nasceu, e o endereço sumiu antes da entrega. Muitos, na frente da fila
        repeat(Entregador.LOTE * 2) {
            jdbc
                .sql(
                    """
                    insert into aviso (codigo, usuario_id, tipo, dados, criado_em, proxima_em)
                    values (gen_random_uuid(), :u, 'cobranca.paga', '{}', now() - interval '1 hour', now() - interval '1 hour')
                    """.trimIndent(),
                ).param("u", semEndereco.pessoa.id)
                .update()
        }
        ana.navegador.post("/pagamentos", mapOf("qr" to loja.qr(100)["qr"].asString()))

        entregador.entregarLote()

        assertEquals(1, receptor.recebidos.size)
        val semDestino = semEndereco.avisos()
        assertEquals(Entregador.LOTE * 2, semDestino.size())
        assertTrue(
            semDestino.all {
                it["estado"].asString() == "falhou" &&
                    it["ultimaResposta"].asString() == "sem endereço de aviso"
            },
        )
    }

    @Test
    fun `cinco entregadores ao mesmo tempo mandam cada aviso uma vez só`() {
        val loja = Cliente("Bob Lima")
        val receptor = receptor()
        loja.ouvir(receptor)
        val pagadores = List(30) { Cliente("Pessoa $it") }
        pagadores.forEach { it.navegador.post("/pagamentos", mapOf("qr" to loja.qr(100)["qr"].asString())) }

        val largada = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(5)
        val tarefas =
            List(5) {
                executor.submit {
                    largada.await()
                    while (entregador.entregarLote(tamanho = 4) > 0) {
                        // até a fila esvaziar
                    }
                }
            }
        largada.countDown()
        tarefas.forEach { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()

        val ids = receptor.recebidos.map { it.cabecalhos.getValue("fiel-evento-id") }
        assertEquals(30, ids.size)
        assertEquals(30, ids.toSet().size)
        assertEquals(30, loja.avisos("entregue").size())
    }

    @Test
    fun `apagar o endereço manda os pendentes para a fila de falhas, e ninguém mexe nos avisos dos outros`() {
        val (loja, eva, ana) = Triple(Cliente("Bob Lima"), Cliente("Eva Rocha"), Cliente("Ana Souza"))
        loja.ouvir(receptor())
        ana.navegador.post("/pagamentos", mapOf("qr" to loja.qr(100)["qr"].asString()))
        val id = loja.avisos()[0]["id"].asLong()

        assertEquals(404, eva.navegador.post("/avisos/$id/reenvio").status)
        assertEquals(0, eva.avisos().size())
        assertEquals(204, loja.navegador.delete("/webhook").status)
        val aviso = loja.avisos()[0]
        assertEquals("falhou", aviso["estado"].asString())
        assertEquals("endereço de aviso removido", aviso["ultimaResposta"].asString())
        assertEquals(409, loja.navegador.post("/avisos/$id/reenvio").status)
        assertEquals(404, loja.navegador.get("/webhook").status)
        assertEquals(400, loja.navegador.enviar("PUT", "/webhook", mapOf("url" to "ftp://exemplo.com/")).status)
        assertEquals(400, loja.navegador.get("/avisos?estado=qualquer").status)
    }
}
