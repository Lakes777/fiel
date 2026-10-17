package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.razao.NovaPartida
import io.github.lakes777.fiel.razao.NovoLancamento
import io.github.lakes777.fiel.razao.Razao
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
class TransferenciasTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var razao: Razao

    @Autowired
    private lateinit var jdbc: JdbcClient

    @AfterEach
    fun soltarRelogio() = relogio.soltar()

    /** Uma pessoa logada com a chave aleatória dela. */
    private inner class Cliente(
        nome: String,
    ) {
        val navegador = Navegador(porta)
        val pessoa = navegador.cadastrarEEntrar(nome)
        val chave: String = navegador.post("/chaves", mapOf("tipo" to "aleatoria")).json()["valor"].asString()

        fun saldo() = navegador.get("/contas").json()[0]["saldoCentavos"].asLong()

        fun transferir(
            para: Cliente,
            valor: Long,
            idempotencia: String? = UUID.randomUUID().toString(),
        ) = navegador.transferir(para.chave, valor, idempotencia)
    }

    private fun parar(brasilia: String) = relogio.parar(OffsetDateTime.parse("$brasilia-03:00").toInstant())

    /** Dinheiro além do bônus, direto da emissão (os limites diários passam do bônus). */
    private fun depositar(
        cliente: Cliente,
        valor: Long,
    ) {
        val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()
        razao.registrar(
            NovoLancamento(
                "depósito de teste",
                listOf(NovaPartida(emissao, Centavos(-valor)), NovaPartida(cliente.pessoa.carteira, Centavos(valor))),
            ),
        )
    }

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
    fun `Ana manda R$ 100 para o Bob pela chave e os dois veem no extrato`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")

        val resposta = ana.transferir(bob, 10_000)

        assertEquals(201, resposta.status)
        assertEquals("Bob L.", resposta.json()["para"].asString())
        assertEquals(10_000, resposta.json()["valorCentavos"].asLong())
        assertEquals(90_000, ana.saldo())
        assertEquals(110_000, bob.saldo())
        val noExtratoDoBob = bob.navegador.get("/contas/${bob.pessoa.carteira}/extrato").json()[0]
        assertEquals("Transferência de Ana S. para Bob L.", noExtratoDoBob["descricao"].asString())
        assertEquals(10_000, noExtratoDoBob["valorCentavos"].asLong())
        val noExtratoDaAna = ana.navegador.get("/contas/${ana.pessoa.carteira}/extrato").json()[0]
        assertEquals(-10_000, noExtratoDaAna["valorCentavos"].asLong())
    }

    @Test
    fun `o mesmo pedido mandado de novo devolve a mesma transferência e não cobra duas vezes`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        val chave = UUID.randomUUID().toString()

        val primeira = ana.transferir(bob, 2_500, chave)
        val repetida = ana.transferir(bob, 2_500, chave)

        assertEquals(201, repetida.status)
        assertEquals(primeira.json()["id"], repetida.json()["id"])
        assertEquals("true", repetida.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertNull(primeira.cabecalhos.firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(97_500, ana.saldo())
        // A mesma chave com outro valor não é o mesmo pedido
        assertEquals(422, ana.transferir(bob, 3_000, chave).status)
        // A chave é por usuário: o Bob pode usar a mesma
        assertEquals(201, bob.transferir(ana, 1_000, chave).status)
        assertEquals(98_500, ana.saldo())
    }

    @Test
    fun `20 cópias do mesmo pedido ao mesmo tempo fazem uma transferência só`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        val chave = UUID.randomUUID().toString()

        val respostas = rodarJuntos(20) { ana.transferir(bob, 1_000, chave) }

        assertEquals(List(20) { 201 }, respostas.map { it.status })
        assertEquals(1, respostas.map { it.json()["id"].asLong() }.toSet().size)
        assertEquals(99_000, ana.saldo())
        assertEquals(101_000, bob.saldo())
    }

    @Test
    fun `pedidos errados são recusados sem mexer em nada`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")

        assertEquals(400, ana.transferir(bob, 1_000, idempotencia = null).status)
        assertEquals(400, ana.transferir(bob, 1_000, idempotencia = "com espaço").status)
        assertEquals(400, ana.transferir(bob, 1_000, idempotencia = "x".repeat(101)).status)
        assertEquals(400, ana.transferir(bob, 0).status)
        assertEquals(400, ana.transferir(bob, -500).status)
        assertEquals(400, ana.transferir(ana, 1_000).status)
        assertEquals(404, ana.navegador.transferir(UUID.randomUUID().toString(), 1_000).status)
        val semSaldo = ana.transferir(bob, 100_001)
        assertEquals(422, semSaldo.status)
        assertEquals("saldo insuficiente: você tem R$ 1.000,00", semSaldo.json()["detail"].asString())
        // Sem o token CSRF (um site de fora tentando), nem chega a transferir
        val deFora =
            ana.navegador.enviar(
                "POST",
                "/transferencias",
                mapOf("chave" to bob.chave, "valorCentavos" to 1_000),
                comCsrf = false,
                cabecalhos = mapOf("Idempotency-Key" to "x"),
            )
        assertEquals(403, deFora.status)
        assertEquals(100_000, ana.saldo())
        assertEquals(100_000, bob.saldo())
    }

    @Test
    fun `GET limites mostra o que já saiu e o que ainda dá para mandar, de dia e de noite`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        parar("2026-03-10T14:00:00")
        ana.transferir(bob, 30_000)

        val deDia = ana.navegador.get("/limites").json()
        assertEquals(500_000, deDia["diaLimiteCentavos"].asLong())
        assertEquals(30_000, deDia["diaUsadoCentavos"].asLong())
        assertEquals(false, deDia["ehNoite"].asBoolean())
        assertEquals(0, deDia["noiteUsadoCentavos"].asLong())
        assertEquals(470_000, deDia["disponivelCentavos"].asLong())

        parar("2026-03-10T21:00:00")
        ana.transferir(bob, 20_000)
        val deNoite = ana.navegador.get("/limites").json()
        assertEquals(true, deNoite["ehNoite"].asBoolean())
        assertEquals(20_000, deNoite["noiteUsadoCentavos"].asLong())
        assertEquals(50_000, deNoite["diaUsadoCentavos"].asLong())
        assertEquals(80_000, deNoite["disponivelCentavos"].asLong())
        assertEquals(401, Navegador(porta).get("/limites").status)
    }

    @Test
    fun `pode mandar o saldo inteiro e nem um centavo a mais`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")

        assertEquals(201, ana.transferir(bob, 100_000).status)
        assertEquals(422, ana.transferir(bob, 1).status)
        assertEquals(0, ana.saldo())
    }

    @Test
    fun `limite diário de R$ 5 mil, que zera à meia-noite de Brasília`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        depositar(ana, 1_000_000)
        parar("2026-03-10T12:00:00")

        assertEquals(201, ana.transferir(bob, 400_000).status)
        val passou = ana.transferir(bob, 100_001)
        assertEquals(422, passou.status)
        assertEquals("passa do limite diário; ainda dá para mandar R$ 1.000,00", passou.json()["detail"].asString())
        assertEquals(201, ana.transferir(bob, 100_000).status)
        assertEquals(422, ana.transferir(bob, 1).status)

        parar("2026-03-11T06:00:00")
        assertEquals(201, ana.transferir(bob, 1).status)
    }

    @Test
    fun `limite noturno de R$ 1 mil, contado das 20h às 6h atravessando a meia-noite`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        depositar(ana, 1_000_000)
        // De dia, o que sai não conta para a noite
        parar("2026-03-10T19:00:00")
        assertEquals(201, ana.transferir(bob, 300_000).status)

        parar("2026-03-10T23:00:00")
        assertEquals(201, ana.transferir(bob, 80_000).status)
        parar("2026-03-11T01:00:00")
        val passou = ana.transferir(bob, 20_001)
        assertEquals(422, passou.status)
        assertEquals("passa do limite noturno; ainda dá para mandar R$ 200,00", passou.json()["detail"].asString())
        assertEquals(201, ana.transferir(bob, 20_000).status)

        parar("2026-03-11T06:00:00")
        assertEquals(201, ana.transferir(bob, 150_000).status)
    }

    @Test
    fun `de noite o limite diário também vale quando ele é o menor`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        depositar(ana, 1_000_000)
        parar("2026-03-10T19:00:00")
        assertEquals(201, ana.transferir(bob, 450_000).status)

        parar("2026-03-10T21:00:00")
        val passou = ana.transferir(bob, 50_001)
        assertEquals("passa do limite diário; ainda dá para mandar R$ 500,00", passou.json()["detail"].asString())
    }

    @Test
    fun `100 transferências ao mesmo tempo não gastam o mesmo dinheiro duas vezes`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        parar("2026-03-10T12:00:00")

        // R$ 1.000 de saldo e 100 pedidos de R$ 15: cabem 66
        val respostas = rodarJuntos(100) { ana.transferir(bob, 1_500) }

        assertEquals(mapOf(201 to 66, 422 to 34), respostas.groupingBy { it.status }.eachCount())
        assertEquals(1_000, ana.saldo())
        assertEquals(199_000, bob.saldo())
        assertEquals(66, transferenciasDe(ana))
    }

    @Test
    fun `Ana e Bob mandando um para o outro ao mesmo tempo não travam para sempre`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        parar("2026-03-10T12:00:00")

        val respostas = rodarJuntos(60) { i -> if (i % 2 == 0) ana.transferir(bob, 100) else bob.transferir(ana, 100) }

        assertTrue(respostas.all { it.status == 201 }, respostas.map { it.status }.toString())
        assertEquals(100_000, ana.saldo())
        assertEquals(100_000, bob.saldo())
    }

    private fun transferenciasDe(cliente: Cliente) =
        jdbc
            .sql("select count(*) from transferencia where de_conta = :c")
            .param("c", cliente.pessoa.carteira)
            .query(Int::class.java)
            .single()
}
