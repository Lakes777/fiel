package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class PedidoDeWebhook(
    @NotBlank @Size(max = 500) val url: String,
)

@Configuration
class ConfiguracaoDosAvisos {
    /** fiel.avisos.permitir-rede-local=true só em teste e desenvolvimento (ver [Destino]). */
    @Bean
    fun destino(
        @Value("\${fiel.avisos.permitir-rede-local:false}") permitirRedeLocal: Boolean,
    ) = Destino(permitirRedeLocal)
}

@RestController
class AvisosController(
    private val avisos: Avisos,
) {
    /** Cadastra ou troca o endereço. A resposta traz o segredo, que não aparece de novo. */
    @PutMapping("/webhook")
    fun definir(
        @Valid @RequestBody pedido: PedidoDeWebhook,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = avisos.definirWebhook(usuario.id, pedido.url)

    @GetMapping("/webhook")
    fun ver(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = avisos.webhook(usuario.id)

    @DeleteMapping("/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remover(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = avisos.removerWebhook(usuario.id)

    /** O histórico de entregas; ?estado=falhou é a fila de falhas. */
    @GetMapping("/avisos")
    fun meus(
        @RequestParam(required = false) estado: String?,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = avisos.doUsuario(usuario.id, estado)

    @PostMapping("/avisos/{id}/reenvio")
    fun reenviar(
        @PathVariable id: Long,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = avisos.reenviar(usuario.id, id)
}
