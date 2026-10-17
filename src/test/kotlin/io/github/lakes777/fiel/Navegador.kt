package io.github.lakes777.fiel

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Um cliente HTTP que se comporta como o navegador de uma pessoa: guarda os cookies (a sessão e o
 * XSRF-TOKEN) e devolve o token CSRF no cabeçalho, como o front-end vai fazer. Cada instância é um
 * aparelho diferente.
 */
class Navegador(
    porta: Int,
) {
    private val base = "http://localhost:$porta"
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http = HttpClient.newBuilder().cookieHandler(cookies).build()

    data class Resposta(
        val status: Int,
        val corpo: String,
        val cabecalhos: HttpHeaders,
    ) {
        fun json(): JsonNode = JSON.readTree(corpo)
    }

    fun get(caminho: String) = enviar("GET", caminho)

    fun post(
        caminho: String,
        corpo: Any? = null,
    ) = enviar("POST", caminho, corpo)

    fun delete(caminho: String) = enviar("DELETE", caminho)

    /** Transfere pela chave, com uma Idempotency-Key nova a cada chamada se nenhuma for dada. */
    fun transferir(
        chave: String,
        valorCentavos: Long,
        idempotencia: String? = UUID.randomUUID().toString(),
    ) = enviar(
        "POST",
        "/transferencias",
        mapOf("chave" to chave, "valorCentavos" to valorCentavos),
        cabecalhos = idempotencia?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(),
    )

    /** [comCsrf] = false simula um site de fora: o cookie vai, mas ele não consegue ler o token. */
    fun enviar(
        metodo: String,
        caminho: String,
        corpo: Any? = null,
        comCsrf: Boolean = true,
        cabecalhos: Map<String, String> = emptyMap(),
    ): Resposta {
        if (metodo != "GET" && comCsrf && cookie("XSRF-TOKEN") == null) get("/csrf")
        val pedido =
            HttpRequest
                .newBuilder(URI.create(base + caminho))
                .method(
                    metodo,
                    if (corpo == null) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo))
                    },
                ).header("Content-Type", "application/json")
        if (comCsrf) cookie("XSRF-TOKEN")?.let { pedido.header("X-XSRF-TOKEN", it) }
        cabecalhos.forEach(pedido::header)
        val resposta = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString())
        return Resposta(resposta.statusCode(), resposta.body(), resposta.headers())
    }

    fun cookie(nome: String): String? =
        cookies.cookieStore.cookies
            .firstOrNull { it.name == nome }
            ?.value

    /** Cria uma pessoa nova (e-mail único) e entra com ela. */
    fun cadastrarEEntrar(
        nome: String = "Ana Souza",
        senha: String = SENHA,
    ): Pessoa {
        val email = "${UUID.randomUUID()}@teste.fiel"
        val cadastro = post("/cadastro", mapOf("nome" to nome, "email" to email, "senha" to senha))
        check(cadastro.status == 201) { "cadastro falhou: ${cadastro.status} ${cadastro.corpo}" }
        val login = post("/sessao", mapOf("email" to email, "senha" to senha))
        check(login.status == 200) { "login falhou: ${login.status} ${login.corpo}" }
        val conta = get("/contas").json()[0]["id"].asLong()
        return Pessoa(cadastro.json()["id"].asLong(), email, conta)
    }

    data class Pessoa(
        val id: Long,
        val email: String,
        val carteira: Long,
    )

    companion object {
        const val SENHA = "senha-de-teste-123"
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
