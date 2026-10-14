package io.github.lakes777.fiel.saude

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.datasource.AbstractDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals

class SaudeControllerTest {
    /** Banco de mentira: entrega uma conexão que responde (ou não) ao isValid, ou falha ao conectar. */
    private class BancoFalso(
        private val responde: Boolean = true,
        private val erroAoConectar: SQLException? = null,
    ) : AbstractDataSource() {
        var conexoesFechadas = 0

        override fun getConnection(): Connection {
            erroAoConectar?.let { throw it }
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, metodo, _ ->
                when (metodo.name) {
                    "isValid" -> responde
                    "close" -> {
                        conexoesFechadas++
                        null
                    }
                    else -> throw UnsupportedOperationException(metodo.name)
                }
            } as Connection
        }

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connection
    }

    @Test
    fun `responde 200 e devolve a conexão ao pool`() {
        val banco = BancoFalso()
        val resposta = SaudeController(banco).verificar()

        assertEquals(HttpStatus.OK, resposta.statusCode)
        assertEquals(Saude(status = "ok", banco = "ok"), resposta.body)
        assertEquals(1, banco.conexoesFechadas)
    }

    @Test
    fun `responde 503 sem vazar o erro quando não consegue conectar`() {
        val banco = BancoFalso(erroAoConectar = SQLException("Connection to 10.0.0.5:5432 refused"))
        val resposta = SaudeController(banco).verificar()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resposta.statusCode)
        assertEquals(Saude(status = "erro", banco = "fora do ar"), resposta.body)
    }

    @Test
    fun `responde 503 quando o banco não responde a tempo e ainda fecha a conexão`() {
        val banco = BancoFalso(responde = false)
        val resposta = SaudeController(banco).verificar()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resposta.statusCode)
        assertEquals(1, banco.conexoesFechadas)
    }

    @Test
    fun `a resposta de erro também não pode ficar em cache`() {
        val resposta = SaudeController(BancoFalso(responde = false)).verificar()

        assertEquals("no-store", resposta.headers.cacheControl)
    }
}
