package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.razao.transferencia
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TesteDeIntegracao
class CobrancasTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var cobrancas: Cobrancas

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var razao: Razao

    @AfterEach
    fun soltarRelogio() = relogio.soltar()

    private inner class Cliente(
        nome: String,
    ) {
        val navegador = Navegador(porta)
        val pessoa = navegador.cadastrarEEntrar(nome)

        fun saldo() = navegador.get("/contas").json()[0]["saldoCentavos"].asLong()

        fun cobrar(
            valor: Long,
            descricao: String = "Camiseta",
            validadeMinutos: Long? = null,
        ): Navegador.Resposta {
            val pedido = mutableMapOf<String, Any>("valorCentavos" to valor, "descricao" to descricao)
            validadeMinutos?.let { pedido["validadeMinutos"] = it }
            return navegador.post("/cobrancas", pedido)
        }

        /** Cria e devolve o texto do QR. */
        fun qr(
            valor: Long,
            validadeMinutos: Long? = null,
        ): String {
            val resposta = cobrar(valor, validadeMinutos = validadeMinutos)
            check(resposta.status == 201) { "cobrança falhou: ${resposta.corpo}" }
            return resposta.json()["qr"].asString()
        }

        fun consultar(qr: String) = navegador.post("/pagamentos/consulta", mapOf("qr" to qr))

        fun pagar(qr: String) = navegador.post("/pagamentos", mapOf("qr" to qr))
    }

    private fun parar(brasilia: String) = relogio.parar(OffsetDateTime.parse("$brasilia-03:00").toInstant())

    private fun rodarJuntos(
        vezes: Int,
        acao: (Int) -> Navegador.Resposta,
    ): List<Navegador.Resposta> {
        val largada = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(vezes)
        try {
            val futuros =
                List(vezes) { i ->
                    executor.submit<Navegador.Resposta> {
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
    fun `Bob cobra, Ana confere pelo QR e paga, e os dois veem`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")

        val criada = loja.cobrar(2_550, "Camiseta preta")
        assertEquals(201, criada.status)
        assertEquals("pendente", criada.json()["estado"].asString())
        val qr = criada.json()["qr"].asString()
        assertTrue(qr.startsWith("0004FIEL"))

        val conferida = ana.consultar(qr).json()
        assertEquals("Bob L.", conferida["recebedor"].asString())
        assertEquals(2_550, conferida["valorCentavos"].asLong())
        assertEquals("Camiseta preta", conferida["descricao"].asString())

        val paga = ana.pagar(qr)
        assertEquals(201, paga.status)
        assertEquals(97_450, ana.saldo())
        assertEquals(102_550, loja.saldo())
        val vista = loja.navegador.get("/cobrancas/${criada.json()["id"].asLong()}").json()
        assertEquals("paga", vista["estado"].asString())
        assertEquals(
            "Pagamento de Ana S. a Bob L.: Camiseta preta",
            ana.navegador
                .get("/contas/${ana.pessoa.carteira}/extrato")
                .json()[0]["descricao"]
                .asString(),
        )
    }

    @Test
    fun `pagar de novo devolve o mesmo pagamento, e outra pessoa leva 409`() {
        val (loja, ana, eva) = Triple(Cliente("Bob Lima"), Cliente("Ana Souza"), Cliente("Eva Rocha"))
        val qr = loja.qr(1_000)

        val primeiro = ana.pagar(qr)
        val repetido = ana.pagar(qr)

        assertEquals(201, repetido.status)
        assertEquals("true", repetido.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertNull(primeiro.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(primeiro.json()["pagaEm"], repetido.json()["pagaEm"])
        assertEquals(99_000, ana.saldo())
        val daEva = eva.pagar(qr)
        assertEquals(409, daEva.status)
        assertEquals("esta cobrança está paga", daEva.json()["detail"].asString())
        assertEquals(100_000, eva.saldo())
    }

    @Test
    fun `20 pessoas pagando a mesma cobrança ao mesmo tempo, só uma paga`() {
        val loja = Cliente("Bob Lima")
        val pagadores = List(20) { Cliente("Pessoa $it") }
        val qr = loja.qr(5_000)

        val respostas = rodarJuntos(20) { pagadores[it].pagar(qr) }

        assertEquals(mapOf(201 to 1, 409 to 19), respostas.groupingBy { it.status }.eachCount())
        assertEquals(105_000, loja.saldo())
        assertEquals(20 * 100_000 - 5_000, pagadores.sumOf { it.saldo() })
    }

    @Test
    fun `a mesma pessoa mandando 20 vezes ao mesmo tempo paga uma vez só`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val qr = loja.qr(5_000)

        val respostas = rodarJuntos(20) { ana.pagar(qr) }

        assertEquals(List(20) { 201 }, respostas.map { it.status })
        assertEquals(19, respostas.count { it.cabecalhos.firstValue("Idempotent-Replayed").isPresent })
        assertEquals(95_000, ana.saldo())
    }

    @Test
    fun `cobrança vence na hora, mesmo antes da tarefa de expiração passar`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        parar("2026-03-10T12:00:00")
        val criada = loja.cobrar(1_000, validadeMinutos = 30).json()
        val qr = criada["qr"].asString()

        parar("2026-03-10T12:29:59")
        assertEquals("pendente", ana.consultar(qr).json()["estado"].asString())
        parar("2026-03-10T12:30:00")
        assertEquals("expirada", ana.consultar(qr).json()["estado"].asString())
        assertEquals(409, ana.pagar(qr).status)
        assertEquals(100_000, ana.saldo())

        // A tarefa só arruma o estado guardado
        assertEquals("pendente", estadoNoBanco(criada["id"].asLong()))
        assertTrue(cobrancas.expirarVencidas() >= 1)
        assertEquals("expirada", estadoNoBanco(criada["id"].asLong()))
        assertEquals(
            "expirada",
            loja.navegador
                .get("/cobrancas/${criada["id"].asLong()}")
                .json()["estado"]
                .asString(),
        )
    }

    @Test
    fun `o lojista cancela a pendente, e cobrança de outro lojista é 404`() {
        val (loja, ana, eva) = Triple(Cliente("Bob Lima"), Cliente("Ana Souza"), Cliente("Eva Rocha"))
        val criada = loja.cobrar(1_000).json()
        val id = criada["id"].asLong()

        assertEquals(404, eva.navegador.post("/cobrancas/$id/cancelamento").status)
        assertEquals(404, eva.navegador.get("/cobrancas/$id").status)
        assertEquals(
            0,
            eva.navegador
                .get("/cobrancas")
                .json()
                .size(),
        )
        val cancelada = loja.navegador.post("/cobrancas/$id/cancelamento")
        assertEquals(200, cancelada.status)
        assertEquals("cancelada", cancelada.json()["estado"].asString())
        assertEquals(409, loja.navegador.post("/cobrancas/$id/cancelamento").status)
        assertEquals(409, ana.pagar(criada["qr"].asString()).status)
        assertEquals(
            1,
            loja.navegador
                .get("/cobrancas")
                .json()
                .size(),
        )
    }

    @Test
    fun `pedidos errados são recusados sem mexer em nada`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val qr = loja.qr(2_550)
        val semCrc = qr.dropLast(8)
        val comOutroValor = semCrc.replace("54042550", "540210") + "6304"

        assertEquals(400, ana.pagar("qualquer coisa").status)
        val ultimoTrocado = qr.dropLast(1) + if (qr.last() == '0') '1' else '0'
        assertEquals(400, ana.pagar(ultimoTrocado).status)
        assertEquals(422, ana.pagar(comOutroValor + Qr.crc16(comOutroValor)).status)
        assertEquals(404, ana.pagar(Qr(UUID.randomUUID(), 2_550, "Bob L.").texto()).status)
        assertEquals(400, loja.pagar(qr).status)
        assertEquals(400, loja.cobrar(0).status)
        assertEquals(400, loja.cobrar(500_001).status)
        assertEquals(400, loja.cobrar(1_000, validadeMinutos = 0).status)
        assertEquals(400, loja.cobrar(1_000, validadeMinutos = 1_441).status)
        assertEquals(400, loja.cobrar(1_000, descricao = " ").status)
        val semSaldo = ana.pagar(loja.qr(100_001))
        assertEquals(422, semSaldo.status)
        assertEquals(100_000, ana.saldo())
        assertEquals(100_000, loja.saldo())
    }

    @Test
    fun `pagamento e transferência contam juntos no limite noturno`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val chaveDaLoja =
            loja.navegador
                .post("/chaves", mapOf("tipo" to "aleatoria"))
                .json()["valor"]
                .asString()
        // Saldo de sobra, para quem barrar ser o limite
        val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()
        razao.registrar(transferencia(emissao, ana.pessoa.carteira, 1_000_000, "depósito de teste"))
        parar("2026-03-10T21:00:00")

        assertEquals(201, ana.navegador.transferir(chaveDaLoja, 80_000).status)
        val passou = ana.pagar(loja.qr(30_000))
        assertEquals(422, passou.status)
        assertEquals("passa do limite noturno; ainda dá para mandar R$ 200,00", passou.json()["detail"].asString())
        assertEquals(201, ana.pagar(loja.qr(20_000)).status)
        // E o contrário: o pagamento conta para a transferência
        assertEquals(422, ana.navegador.transferir(chaveDaLoja, 1).status)
    }

    private fun estadoNoBanco(id: Long) =
        jdbc
            .sql("select estado from cobranca where id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()
}
