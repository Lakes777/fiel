package io.github.lakes777.fiel.seguranca

import io.github.lakes777.fiel.ContadorDeSenhas
import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.Navegador.Companion.SENHA
import io.github.lakes777.fiel.TesteDeIntegracao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TesteDeIntegracao
class SessaoTest {
    @LocalServerPort
    private var porta = 0

    @Autowired
    private lateinit var jdbc: JdbcClient

    private fun navegador() = Navegador(porta)

    private fun emailNovo() = "${UUID.randomUUID()}@teste.fiel"

    private fun cadastrar(
        n: Navegador,
        email: String,
        senha: String = SENHA,
        nome: String = "Ana Souza",
    ) = n.post("/cadastro", mapOf("nome" to nome, "email" to email, "senha" to senha))

    private fun entrar(
        n: Navegador,
        email: String,
        senha: String = SENHA,
    ) = n.post("/sessao", mapOf("email" to email, "senha" to senha))

    @Test
    fun `cadastro cria a carteira com o bônus de R$ 1000 e o extrato mostra de onde ele veio`() {
        val n = navegador()
        val ana = n.cadastrarEEntrar()

        val contas = n.get("/contas").json()
        assertEquals(1, contas.size())
        assertEquals(100_000, contas[0]["saldoCentavos"].asLong())
        val extrato = n.get("/contas/${ana.carteira}/extrato").json()
        assertEquals("Bônus de boas-vindas", extrato[0]["descricao"].asString())
        assertEquals(100_000, extrato[0]["valorCentavos"].asLong())
    }

    @Test
    fun `o dinheiro de todo o sistema soma zero, a Emissão fica com o negativo dos bônus`() {
        navegador().cadastrarEEntrar()
        val soma = jdbc.sql("select coalesce(sum(valor), 0) from partida").query(Long::class.java).single()
        val emissao =
            jdbc
                .sql("select sum(p.valor) from partida p join conta c on c.id = p.conta_id where c.tipo = 'emissao'")
                .query(Long::class.java)
                .single()
        assertEquals(0, soma)
        assertTrue(emissao <= -100_000)
    }

    @Test
    fun `o mesmo e-mail não se cadastra duas vezes, nem com maiúsculas`() {
        val email = emailNovo()
        assertEquals(201, cadastrar(navegador(), email).status)

        val repetido = cadastrar(navegador(), email.uppercase())

        assertEquals(409, repetido.status)
        assertEquals(
            1,
            jdbc
                .sql("select count(*) from usuario where email = :e")
                .param("e", email)
                .query(Long::class.java)
                .single(),
        )
    }

    @Test
    fun `senha curta, senha acima de 72 bytes e e-mail inválido são recusados`() {
        assertEquals(400, cadastrar(navegador(), emailNovo(), senha = "curta").status)
        assertEquals(400, cadastrar(navegador(), emailNovo(), senha = "á".repeat(37)).status)
        assertEquals(400, cadastrar(navegador(), "sem-arroba").status)
        assertEquals(400, cadastrar(navegador(), emailNovo(), nome = " ").status)
    }

    @Test
    fun `a senha fica guardada só como hash bcrypt`() {
        val email = emailNovo()
        cadastrar(navegador(), email)
        val guardada =
            jdbc
                .sql(
                    "select senha from usuario where email = :e",
                ).param("e", email)
                .query(String::class.java)
                .single()
        assertTrue(guardada.startsWith("{bcrypt}$2"))
        assertFalse(guardada.contains(SENHA))
    }

    @Test
    fun `login errado responde igual para senha errada e e-mail sem cadastro`() {
        val email = emailNovo()
        cadastrar(navegador(), email)

        val senhaErrada = entrar(navegador(), email, "outra-senha-qualquer")
        val semCadastro = entrar(navegador(), emailNovo())

        assertEquals(401, senhaErrada.status)
        assertEquals(401, semCadastro.status)
        assertEquals(senhaErrada.json()["detail"], semCadastro.json()["detail"])
    }

    @Test
    fun `depois de 5 senhas erradas o e-mail fica bloqueado, mesmo com a senha certa`() {
        val email = emailNovo()
        cadastrar(navegador(), email)
        repeat(5) { assertEquals(401, entrar(navegador(), email, "errada-errada").status) }

        val certa = entrar(navegador(), email)

        assertEquals(429, certa.status)
        assertNotNull(certa.cabecalhos.firstValue("Retry-After").orElse(null))
    }

    @Test
    fun `entrar com a senha certa zera a contagem de erros`() {
        val email = emailNovo()
        cadastrar(navegador(), email)
        repeat(4) { entrar(navegador(), email, "errada-errada") }
        assertEquals(200, entrar(navegador(), email).status)

        repeat(4) { assertEquals(401, entrar(navegador(), email, "errada-errada").status) }
    }

    @Test
    fun `20 tentativas ao mesmo tempo ainda testam no máximo 5 senhas`() {
        val email = emailNovo()
        cadastrar(navegador(), email)
        // Uma senha só deste teste, para contar quantas vezes o bcrypt chegou a conferi-la
        val chute = "chute-${UUID.randomUUID()}"
        val tarefas = Executors.newFixedThreadPool(20)
        val largada = CountDownLatch(1)
        val respostas =
            List(20) {
                tarefas.submit<Int> {
                    largada.await()
                    entrar(navegador(), email, chute).status
                }
            }
        largada.countDown()
        val status = respostas.map { it.get(TEMPO_LIMITE_S, TimeUnit.SECONDS) }
        tarefas.shutdown()

        assertEquals(5, ContadorDeSenhas.vezesConferida(chute))
        assertEquals(5, status.count { it == 401 })
        assertEquals(15, status.count { it == 429 })
    }

    @Test
    fun `o token CSRF muda no login`() {
        val n = navegador()
        val email = emailNovo()
        cadastrar(n, email)
        val antes = n.cookie("XSRF-TOKEN")

        entrar(n, email)

        assertNotNull(antes)
        assertNotEquals(antes, n.cookie("XSRF-TOKEN"))
        assertEquals(201, n.post("/chaves", mapOf("tipo" to "aleatoria")).status)
    }

    @Test
    fun `sem login as rotas respondem 401 em problem+json`() {
        for (rota in listOf("/eu", "/contas", "/chaves")) {
            val resposta = navegador().get(rota)
            assertEquals(401, resposta.status, rota)
            assertTrue(
                resposta.cabecalhos
                    .firstValue("Content-Type")
                    .get()
                    .startsWith("application/problem+json"),
            )
        }
    }

    @Test
    fun `pedido que muda algo sem o token CSRF é recusado, mesmo logado`() {
        val n = navegador()
        n.cadastrarEEntrar()

        val semToken = n.enviar("POST", "/chaves", mapOf("tipo" to "aleatoria"), comCsrf = false)
        val semTokenNoLogin =
            navegador().enviar(
                "POST",
                "/sessao",
                mapOf("email" to "x@y.z", "senha" to SENHA),
                comCsrf = false,
            )

        assertEquals(403, semToken.status)
        assertEquals(403, semTokenNoLogin.status)
        assertEquals(0, n.get("/chaves").json().size())
    }

    @Test
    fun `o cookie da sessão é HttpOnly e SameSite Strict`() {
        val n = navegador()
        val email = emailNovo()
        cadastrar(n, email)

        val login = entrar(n, email)

        val cookie = login.cabecalhos.allValues("Set-Cookie").single { it.startsWith("FIEL_SESSAO=") }
        assertContains(cookie, "HttpOnly")
        assertContains(cookie, "SameSite=Strict")
    }

    @Test
    fun `entrar de novo troca o id da sessão`() {
        val n = navegador()
        val ana = n.cadastrarEEntrar()
        val antes = n.cookie("FIEL_SESSAO")

        entrar(n, ana.email)

        assertNotNull(antes)
        assertNotEquals(antes, n.cookie("FIEL_SESSAO"))
    }

    @Test
    fun `sair apaga a sessão no banco e o cookie antigo deixa de valer`() {
        val n = navegador()
        val ana = n.cadastrarEEntrar()
        assertEquals(200, n.get("/eu").status)

        assertEquals(204, n.delete("/sessao").status)

        assertEquals(401, n.get("/eu").status)
        assertEquals(0, sessoesDe(ana.id))
    }

    @Test
    fun `sair de todos os aparelhos derruba as outras sessões também`() {
        val celular = navegador()
        val ana = celular.cadastrarEEntrar()
        val computador = navegador()
        assertEquals(200, entrar(computador, ana.email).status)
        assertEquals(2, sessoesDe(ana.id))

        assertEquals(204, computador.delete("/sessoes").status)

        assertEquals(401, celular.get("/eu").status)
        assertEquals(401, computador.get("/eu").status)
        assertEquals(0, sessoesDe(ana.id))
    }

    @Test
    fun `a sessão no banco não guarda a senha nem o hash`() {
        val n = navegador()
        val ana = n.cadastrarEEntrar()
        val hash =
            jdbc
                .sql(
                    "select senha from usuario where id = :id",
                ).param("id", ana.id)
                .query(String::class.java)
                .single()
        val bytes =
            jdbc
                .sql(
                    """
                    select a.attribute_bytes from spring_session_attributes a
                    join spring_session s on s.primary_id = a.session_primary_id
                    where s.principal_name = :id
                    """.trimIndent(),
                ).param("id", ana.id.toString())
                .query(ByteArray::class.java)
                .list()
        assertTrue(bytes.isNotEmpty())
        assertNull(
            bytes.filterNotNull().map { String(it, Charsets.ISO_8859_1) }.firstOrNull {
                it.contains(hash) ||
                    it.contains(SENHA)
            },
        )
    }

    private companion object {
        const val TEMPO_LIMITE_S = 30L
    }

    private fun sessoesDe(usuario: Long) =
        jdbc
            .sql("select count(*) from spring_session where principal_name = :id")
            .param("id", usuario.toString())
            .query(Long::class.java)
            .single()
}
