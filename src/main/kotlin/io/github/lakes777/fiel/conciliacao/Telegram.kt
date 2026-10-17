package io.github.lakes777.fiel.conciliacao

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Manda uma mensagem para um chat do Telegram pela API de bots. Sem token ou sem chat configurado,
 * só registra no log: a conciliação roda igual em desenvolvimento.
 *
 * O token vai na URL (é assim que a API do Telegram funciona), então nada aqui loga a URL nem a
 * mensagem da exceção, que poderia trazê-la.
 */
@Component
class Telegram(
    @Value("\${fiel.telegram.token:}") private val token: String,
    @Value("\${fiel.telegram.chat:}") private val chat: String,
    @Value("\${fiel.telegram.url:https://api.telegram.org}") private val base: String,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(SEGUNDOS)).build()

    val configurado get() = token.isNotBlank() && chat.isNotBlank()

    /** true se o Telegram aceitou a mensagem. Nunca lança: o aviso não pode derrubar a conciliação. */
    fun enviar(texto: String): Boolean {
        if (!configurado) {
            log.info("Telegram não configurado (FIEL_TELEGRAM_TOKEN e FIEL_TELEGRAM_CHAT); a mensagem ficou só no log")
            return false
        }
        val pedido =
            HttpRequest
                .newBuilder(URI.create("$base/bot$token/sendMessage"))
                .timeout(Duration.ofSeconds(SEGUNDOS))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            mapOf(
                                "chat_id" to chat,
                                "text" to texto,
                            ),
                        ),
                    ),
                ).build()
        return try {
            val status = http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status !in SUCESSO) log.warn("o Telegram recusou a mensagem: HTTP {}", status)
            status in SUCESSO
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("envio ao Telegram interrompido")
            false
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("falha ao falar com o Telegram: {}", e.javaClass.simpleName)
            false
        }
    }

    private companion object {
        const val SEGUNDOS = 10L
        val SUCESSO = 200..299
    }
}
