package io.github.lakes777.fiel.demo

import io.github.lakes777.fiel.cobrancas.PagamentoFeito
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.seguranca.AberturaDeSessao
import io.github.lakes777.fiel.seguranca.Eu
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import io.github.lakes777.fiel.seguranca.chaveDoIp
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class EstadoDaDemo(
    val ativa: Boolean,
)

data class VisitanteCriado(
    val eu: Eu,
    val roteiro: Roteiro,
)

/** Rotas da demonstração. Com ela desligada (o padrão), só o GET /demo responde, com ativa = false. */
@RestController
class DemoController(
    private val demo: Demonstracao,
    private val limites: LimitesDeUso,
    private val aberturaDeSessao: AberturaDeSessao,
) {
    /** A tela pergunta antes de mostrar o botão de visitante. */
    @GetMapping("/demo")
    fun estado() = EstadoDaDemo(demo.ativa)

    /** Cria o visitante e já entra com ele. Conta no mesmo limite por IP do cadastro. */
    @PostMapping("/demo/visitante")
    @ResponseStatus(HttpStatus.CREATED)
    fun visitante(
        requisicao: HttpServletRequest,
        resposta: HttpServletResponse,
    ): VisitanteCriado {
        exigirDemo()
        limites.cadastro.registrar(chaveDoIp(requisicao.remoteAddr))
        limites.visitantes.registrar("todos")
        val novo = demo.novoVisitante()
        val usuario = novo.usuario
        aberturaDeSessao.abrir(
            UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.authorities),
            requisicao,
            resposta,
        )
        return VisitanteCriado(Eu(usuario.id, usuario.nome, usuario.email), novo.roteiro)
    }

    @PostMapping("/demo/cobrancas/{id}/bob-paga")
    fun bobPaga(
        @AuthenticationPrincipal usuario: UsuarioLogado,
        @PathVariable id: Long,
    ): PagamentoFeito {
        exigirDemo()
        // O mesmo limite de qualquer pagamento: cada pedido trava a cobrança e as duas carteiras
        limites.pagamento.registrar(usuario.username)
        return demo.bobPaga(usuario, id)
    }

    private fun exigirDemo() {
        if (!demo.ativa) throw NaoEncontrado("não há demonstração neste servidor")
    }
}
