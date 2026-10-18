package io.github.lakes777.fiel.eventos

import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.avisos.Entregador
import io.github.lakes777.fiel.cobrancas.Cobrancas
import io.github.lakes777.fiel.seguranca.RelogioDaSessao
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TesteDeIntegracao
class EventosTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var ouvinte: Ouvinte

    @Autowired
    private lateinit var canais: Canais

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var banco: PostgreSQLContainer

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var cobrancas: Cobrancas

    @Autowired
    private lateinit var relogioDaSessao: RelogioDaSessao

    @Autowired
    private lateinit var entregador: Entregador

    /** O LISTEN sobe depois que a API fica pronta: espera por ele antes de cada teste. */
    @BeforeEach
    fun esperarOuvinte() {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!ouvinte.conectado() && System.nanoTime() < limite) Thread.sleep(50)
        check(ouvinte.conectado()) { "o ouvinte de eventos não conectou" }
    }

    private inner class Cliente(
        nome: String,
    ) {
        val navegador = Navegador(porta)
        val pessoa = navegador.cadastrarEEntrar(nome)
        val chave: String by lazy {
            navegador.post("/chaves", mapOf("tipo" to "aleatoria")).json()["valor"].asString()
        }

        /** Abre o canal e já consome o "pronto". */
        fun ouvir(): Navegador.CanalDeEventos =
            navegador.eventos().also { canal ->
                assertEquals("pronto", canal.proximo()?.nome, "o canal não abriu")
            }
    }

    /** Os nomes que chegarem em [ms], sem esperar mais que isso. */
    private fun Navegador.CanalDeEventos.nomesEm(ms: Long = 1_500): List<String> {
        val nomes = mutableListOf<String>()
        val limite = System.currentTimeMillis() + ms
        while (true) {
            val falta = limite - System.currentTimeMillis()
            if (falta <= 0) return nomes
            nomes += proximo(falta)?.nome ?: return nomes
        }
    }

    @Test
    fun `sem login não abre`() {
        Navegador(porta).eventos().use { canal ->
            val limite = System.currentTimeMillis() + 5_000
            while (canal.status == 0 && System.currentTimeMillis() < limite) Thread.sleep(20)
            assertEquals(401, canal.status)
        }
    }

    @Test
    fun `quem recebe e quem manda uma transferência ficam sabendo, e só eles`() {
        val (ana, bob, eva) = Triple(Cliente("Ana Souza"), Cliente("Bob Lima"), Cliente("Eva Reis"))
        ana.ouvir().use { daAna ->
            bob.ouvir().use { doBob ->
                eva.ouvir().use { daEva ->
                    assertEquals(201, bob.navegador.transferir(ana.chave, 1_000).status)

                    assertEquals("carteira", daAna.proximo()?.nome)
                    assertEquals("carteira", doBob.proximo()?.nome)
                    assertEquals(emptyList(), daEva.nomesEm())
                }
            }
        }
    }

    @Test
    fun `transferência recusada não avisa ninguém`() {
        val (ana, bob) = Cliente("Ana Souza") to Cliente("Bob Lima")
        ana.ouvir().use { daAna ->
            assertEquals(422, bob.navegador.transferir(ana.chave, 100_001).status)

            assertEquals(emptyList(), daAna.nomesEm())
        }
    }

    @Test
    fun `o lojista vê o pagamento da cobrança chegar, com o id dela`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val cobranca = loja.navegador.post("/cobrancas", mapOf("valorCentavos" to 500, "descricao" to "Caneca")).json()
        loja.ouvir().use { doBob ->
            assertEquals(201, ana.navegador.post("/pagamentos", mapOf("qr" to cobranca["qr"].asString())).status)

            val recebidos = List(2) { assertNotNull(doBob.proximo()) }
            assertEquals(setOf("carteira", "cobranca"), recebidos.map { it.nome }.toSet())
            val daCobranca = recebidos.single { it.nome == "cobranca" }
            assertEquals(cobranca["id"].asLong(), Navegador.JSON.readTree(daCobranca.dados)["cobranca"].asLong())
        }
    }

    @Test
    fun `um NOTIFY de outra instância chega a quem está conectado aqui`() {
        val ana = Cliente("Ana Souza")
        ana.ouvir().use { daAna ->
            jdbc
                .sql("select pg_notify('fiel_eventos', json_build_object('usuario', :u, 'tipo', 'avisos')::text)")
                .param("u", ana.pessoa.id)
                .query { _, _ -> }
                .list()

            assertEquals("avisos", daAna.proximo()?.nome)
        }
    }

    @Test
    fun `no máximo 5 conexões por pessoa, e sair de todos os aparelhos fecha as abertas`() {
        val ana = Cliente("Ana Souza")
        val canais = List(6) { ana.ouvir() }
        try {
            assertEquals(Canais.MAX_POR_USUARIO, this.canais.quantos(ana.pessoa.id))
            assertTrue(esperar { canais.first().terminou }, "a mais antiga deveria ter fechado")

            assertEquals(204, ana.navegador.delete("/sessoes").status)

            assertTrue(esperar { canais.drop(1).all { it.terminou } }, "as abertas deveriam ter fechado")
            assertEquals(0, this.canais.quantos(ana.pessoa.id))
        } finally {
            canais.forEach { it.close() }
        }
    }

    @Test
    fun `um NOTIFY estragado é ignorado e o seguinte ainda chega`() {
        val ana = Cliente("Ana Souza")
        ana.ouvir().use { daAna ->
            notificar("{\"sem\": \"usuario\"}")
            notificar("não é json")
            notificar("{\"usuario\": ${ana.pessoa.id}, \"tipo\": \"carteira\"}")

            assertEquals("carteira", daAna.proximo()?.nome)
        }
    }

    @Test
    fun `criar e cancelar cobrança avisa as outras abas do lojista`() {
        val loja = Cliente("Bob Lima")
        loja.ouvir().use { doBob ->
            val id =
                loja.navegador
                    .post(
                        "/cobrancas",
                        mapOf("valorCentavos" to 500, "descricao" to "Caneca"),
                    ).json()["id"]
                    .asLong()
            assertEquals(EventoEsperado("cobranca", id), doBob.proximoEsperado())

            assertEquals(200, loja.navegador.post("/cobrancas/$id/cancelamento").status)
            assertEquals(EventoEsperado("cobranca", id), doBob.proximoEsperado())
        }
    }

    @Test
    fun `estorno avisa a carteira dos dois e a cobrança do lojista`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        val cobranca = loja.navegador.post("/cobrancas", mapOf("valorCentavos" to 500, "descricao" to "Caneca")).json()
        assertEquals(201, ana.navegador.post("/pagamentos", mapOf("qr" to cobranca["qr"].asString())).status)
        loja.ouvir().use { doBob ->
            ana.ouvir().use { daAna ->
                val estorno =
                    loja.navegador.enviar(
                        "POST",
                        "/cobrancas/${cobranca["id"].asLong()}/estornos",
                        mapOf("valorCentavos" to 200),
                        cabecalhos = mapOf("Idempotency-Key" to "e-1"),
                    )
                assertEquals(201, estorno.status)

                assertEquals(setOf("carteira", "cobranca"), List(2) { assertNotNull(doBob.proximo()).nome }.toSet())
                assertEquals("carteira", daAna.proximo()?.nome)
            }
        }
    }

    @Test
    fun `cobrança que expira avisa o lojista`() {
        val loja = Cliente("Bob Lima")
        val id =
            loja.navegador
                .post("/cobrancas", mapOf("valorCentavos" to 500, "descricao" to "Caneca", "validadeMinutos" to 1))
                .json()["id"]
                .asLong()
        loja.ouvir().use { doBob ->
            relogio.parar(Instant.now().plusSeconds(120))
            try {
                cobrancas.expirarVencidas()
            } finally {
                relogio.soltar()
            }

            val recebidos =
                generateSequence {
                    doBob.proximo(
                        1_500,
                    )
                }.map { EventoEsperado(it.nome, cobrancaDe(it)) }.toList()
            assertTrue(EventoEsperado("cobranca", id) in recebidos, recebidos.toString())
        }
    }

    @Test
    fun `o resultado de uma entrega de webhook avisa o painel`() {
        val (loja, ana) = Cliente("Bob Lima") to Cliente("Ana Souza")
        // Nada escuta na porta 9: a entrega falha e o resultado é gravado
        assertEquals(200, loja.navegador.enviar("PUT", "/webhook", mapOf("url" to "http://127.0.0.1:9/avisos")).status)
        val cobranca = loja.navegador.post("/cobrancas", mapOf("valorCentavos" to 500, "descricao" to "Caneca")).json()
        assertEquals(201, ana.navegador.post("/pagamentos", mapOf("qr" to cobranca["qr"].asString())).status)
        // Só o aviso deste teste na fila
        jdbc
            .sql("update aviso set estado = 'falhou', proxima_em = null where estado = 'pendente' and usuario_id <> :u")
            .param("u", loja.pessoa.id)
            .update()
        loja.ouvir().use { doBob ->
            entregador.entregarLote()

            assertEquals("avisos", doBob.proximo()?.nome)
        }
        jdbc
            .sql("update aviso set estado = 'falhou', proxima_em = null where usuario_id = :u and estado = 'pendente'")
            .param("u", loja.pessoa.id)
            .update()
    }

    @Test
    fun `se a conexão do LISTEN cair, ela volta e as telas recebem pronto`() {
        val ana = Cliente("Ana Souza")
        ana.ouvir().use { daAna ->
            val antes = ouvinte.processo()
            // Derrubar a conexão de outro processo é coisa de dono, não do usuário da API: entra como dono
            DriverManager.getConnection(banco.jdbcUrl, banco.username, banco.password).use { dono ->
                dono.prepareStatement("select pg_terminate_backend(?)").use {
                    it.setInt(1, antes)
                    it.executeQuery().close()
                }
            }

            assertEquals("pronto", daAna.proximo(15_000)?.nome)
            assertTrue(ouvinte.processo() != antes)
            notificar("{\"usuario\": ${ana.pessoa.id}, \"tipo\": \"carteira\"}")
            assertEquals("carteira", daAna.proximo()?.nome)
        }
    }

    @Test
    fun `15 min sem usar acaba a sessão, mesmo com a tela ao vivo reconectando`() {
        val ana = Cliente("Ana Souza")
        val inicio = Instant.now()
        val sessao = relogioDaSessao.relogio as RelogioDeTeste
        try {
            sessao.parar(inicio)
            assertEquals(200, ana.navegador.get("/contas").status)
            // A tela ao vivo reconecta aos 10 min: não conta como uso
            sessao.parar(inicio.plus(Duration.ofMinutes(10)))
            ana.ouvir().close()

            sessao.parar(inicio.plus(Duration.ofMinutes(16)))
            ana.navegador.eventos().use { canal ->
                assertTrue(esperar { canal.status != 0 })
                assertEquals(401, canal.status)
            }
            assertEquals(401, ana.navegador.get("/contas").status)
        } finally {
            sessao.soltar()
        }
    }

    @Test
    fun `usar o sistema renova os 15 min`() {
        val ana = Cliente("Ana Souza")
        val inicio = Instant.now()
        val sessao = relogioDaSessao.relogio as RelogioDeTeste
        try {
            for (minuto in listOf(0L, 10, 20, 30)) {
                sessao.parar(inicio.plus(Duration.ofMinutes(minuto)))
                assertEquals(200, ana.navegador.get("/contas").status, "aos $minuto min")
            }
        } finally {
            sessao.soltar()
        }
    }

    private data class EventoEsperado(
        val nome: String,
        val cobranca: Long?,
    )

    private fun cobrancaDe(evento: Navegador.EventoRecebido): Long? =
        Navegador.JSON.readTree(evento.dados)["cobranca"]?.asLong()

    private fun Navegador.CanalDeEventos.proximoEsperado(): EventoEsperado? =
        proximo()?.let { EventoEsperado(it.nome, cobrancaDe(it)) }

    private fun notificar(texto: String) {
        jdbc
            .sql("select pg_notify('fiel_eventos', :texto)")
            .param("texto", texto)
            .query { _, _ -> }
            .list()
    }

    private fun esperar(condicao: () -> Boolean): Boolean {
        val limite = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < limite) {
            if (condicao()) return true
            Thread.sleep(50)
        }
        return condicao()
    }
}
