package io.github.lakes777.fiel.seguranca

import io.github.lakes777.fiel.demo.DOMINIOS_DA_DEMO
import io.github.lakes777.fiel.erros.NaoAutenticado
import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.eventos.Canais
import io.github.lakes777.fiel.usuarios.Cadastro
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import io.github.lakes777.fiel.usuarios.normalizarEmail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class PedidoDeCadastro(
    @NotBlank @Size(max = 100) val nome: String,
    @NotBlank @Email @Size(max = 254) val email: String,
    val senha: String,
)

data class PedidoDeLogin(
    @NotBlank val email: String,
    @NotBlank val senha: String,
)

data class Eu(
    val id: Long,
    val nome: String,
    val email: String,
)

data class Csrf(
    val cabecalho: String,
    val token: String,
)

@RestController
class SessaoController(
    private val cadastro: Cadastro,
    private val autenticacao: AuthenticationManager,
    private val sessoes: FindByIndexNameSessionRepository<*>,
    private val limites: LimitesDeUso,
    private val canais: Canais,
    private val aberturaDeSessao: AberturaDeSessao,
) {
    /** Entrega o token CSRF (e grava o cookie XSRF-TOKEN): o cliente chama antes do primeiro POST. */
    @GetMapping("/csrf")
    fun csrf(token: CsrfToken) = Csrf(token.headerName, token.token)

    @PostMapping("/cadastro")
    @ResponseStatus(HttpStatus.CREATED)
    fun cadastrar(
        @Valid @RequestBody pedido: PedidoDeCadastro,
        requisicao: HttpServletRequest,
    ): Eu {
        limites.cadastro.registrar(chaveDoIp(requisicao.remoteAddr))
        // As contas da demonstração (Loja Exemplo, Ana, visitantes e Bobs) são achadas pelo e-mail:
        // quem se cadastrasse com um deles faria o papel da Loja e receberia o dinheiro dos visitantes
        if (DOMINIOS_DA_DEMO.any { normalizarEmail(pedido.email).endsWith("@$it") }) {
            throw RegraDeNegocio("este domínio de e-mail é reservado à demonstração")
        }
        val novo = cadastro.cadastrar(pedido.nome, pedido.email, pedido.senha)
        return Eu(novo.id, novo.nome, novo.email)
    }

    @PostMapping("/sessao")
    fun entrar(
        @Valid @RequestBody pedido: PedidoDeLogin,
        requisicao: HttpServletRequest,
        resposta: HttpServletResponse,
    ): Eu {
        val email = normalizarEmail(pedido.email)
        // Conta a tentativa antes do bcrypt: pedidos simultâneos não passam todos pela conferência
        limites.login.registrar(email)
        val autenticado =
            try {
                autenticacao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, pedido.senha))
            } catch (_: AuthenticationException) {
                // A mesma resposta para e-mail sem cadastro e senha errada
                throw NaoAutenticado("e-mail ou senha incorretos")
            }
        limites.login.esquecer(email)
        aberturaDeSessao.abrir(autenticado, requisicao, resposta)
        val usuario = autenticado.principal as UsuarioLogado
        return Eu(usuario.id, usuario.nome, usuario.email)
    }

    @GetMapping("/eu")
    fun eu(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = Eu(usuario.id, usuario.nome, usuario.email)

    /** Sai: a sessão é apagada do banco na hora, e o cookie antigo deixa de valer. */
    @DeleteMapping("/sessao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun sair(
        @AuthenticationPrincipal usuario: UsuarioLogado,
        requisicao: HttpServletRequest,
    ) {
        requisicao.getSession(false)?.invalidate()
        // As telas ao vivo fecham junto (as de outros aparelhos reconectam; as desta sessão, não)
        canais.fecharDe(usuario.id)
        SecurityContextHolder.getContextHolderStrategy().clearContext()
    }

    /** Sai de todos os aparelhos: apaga todas as sessões deste usuário, inclusive esta. */
    @DeleteMapping("/sessoes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun sairDeTodos(
        @AuthenticationPrincipal usuario: UsuarioLogado,
        requisicao: HttpServletRequest,
    ) {
        // A desta requisição é invalidada pelo próprio pedido: apagada só no banco, o Spring Session
        // poderia gravá-la de volta ao fim da resposta
        requisicao.getSession(false)?.invalidate()
        sessoes.findByPrincipalName(usuario.username).keys.forEach(sessoes::deleteById)
        // As telas ao vivo deste usuário nesta instância também fecham; nas outras, fecham em até 15 min
        // (a reconexão já não passa, sem sessão)
        canais.fecharDe(usuario.id)
        SecurityContextHolder.getContextHolderStrategy().clearContext()
    }
}
