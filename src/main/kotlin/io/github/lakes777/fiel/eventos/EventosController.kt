package io.github.lakes777.fiel.eventos

import io.github.lakes777.fiel.seguranca.InatividadeDaSessao
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration

@RestController
class EventosController(
    private val canais: Canais,
) {
    /**
     * Server-Sent Events: a tela abre com `new EventSource("/eventos")` e recebe "pronto",
     * "carteira", "cobranca" e "avisos". Só leitura (GET), então não precisa de CSRF; o cookie da
     * sessão diz de quem são os eventos.
     */
    @GetMapping("/eventos", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun eventos(
        @AuthenticationPrincipal usuario: UsuarioLogado,
        pedido: HttpServletRequest,
        resposta: HttpServletResponse,
    ): SseEmitter {
        resposta.setHeader("Cache-Control", "no-store")
        // Proxies como o nginx guardam a resposta antes de repassar; com isto o evento sai na hora
        resposta.setHeader("X-Accel-Buffering", "no")
        // Fecha quando a sessão vencer por falta de uso; a reconexão, aí, já não passa
        val falta = pedido.getAttribute(InatividadeDaSessao.FALTA) as Duration? ?: Duration.ofMinutes(1)
        return canais.abrir(usuario.id, falta)
    }
}
