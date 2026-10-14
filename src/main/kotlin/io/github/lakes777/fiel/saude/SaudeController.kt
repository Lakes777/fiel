package io.github.lakes777.fiel.saude

import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Quanto o /saude espera uma conexão já aberta responder. Com o banco inalcançável, quem limita
 * é a espera do pool por uma conexão (spring.datasource.hikari.connection-timeout, 5 s).
 */
const val LIMITE_DO_BANCO_S = 2

data class Saude(
    val status: String,
    val banco: String,
)

/**
 * Diz se a API está de pé e se alcança o banco. É o endereço que o Vigil consulta a cada minuto.
 * Com o banco fora, responde 503 sem mostrar o erro (que pode ter endereço interno); o motivo vai para o log.
 */
@RestController
class SaudeController(
    private val banco: DataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/saude")
    fun verificar(): ResponseEntity<Saude> {
        val motivo = problemaNoBanco()
        val (codigo, corpo) =
            if (motivo == null) {
                HttpStatus.OK to Saude(status = "ok", banco = "ok")
            } else {
                log.warn("Saúde: banco fora do ar ({})", motivo)
                HttpStatus.SERVICE_UNAVAILABLE to Saude(status = "erro", banco = "fora do ar")
            }
        return ResponseEntity.status(codigo).cacheControl(CacheControl.noStore()).body(corpo)
    }

    /** null quando o banco respondeu a tempo; senão, o motivo (só para o log). */
    private fun problemaNoBanco(): String? =
        try {
            banco.connection.use { conexao ->
                if (conexao.isValid(LIMITE_DO_BANCO_S)) null else "não respondeu em $LIMITE_DO_BANCO_S s"
            }
        } catch (e: SQLException) {
            e.message ?: e.javaClass.simpleName
        }
}
