package io.github.lakes777.fiel.transferencias

import io.github.lakes777.fiel.usuarios.UsuarioLogado
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
class LimitesController(
    private val saidas: Saidas,
    private val jdbc: JdbcClient,
    private val relogio: Clock,
) {
    /** Os limites da carteira de quem está logado, agora. */
    @GetMapping("/limites")
    fun limites(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): SituacaoDosLimites {
        val carteira =
            jdbc
                .sql("select id from conta where dono_id = :dono and tipo = 'carteira'")
                .param("dono", usuario.id)
                .query(Long::class.java)
                .single()
        return saidas.situacao(carteira, relogio.instant())
    }
}
