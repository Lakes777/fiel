package io.github.lakes777.fiel.seguranca

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.stereotype.Component

/**
 * Abre a sessão de quem acabou de se autenticar: pelo login (POST /sessao) ou como visitante da
 * demonstração. Os dois caminhos precisam das mesmas proteções, então elas moram num lugar só.
 */
@Component
class AberturaDeSessao(
    private val contexto: SecurityContextRepository,
    private val tokensCsrf: CsrfTokenRepository,
    private val relogioDaSessao: RelogioDaSessao,
) {
    fun abrir(
        autenticado: Authentication,
        requisicao: HttpServletRequest,
        resposta: HttpServletResponse,
    ) {
        // Fixação de sessão: quem plantou um id de sessão antes do login não herda a sessão logada
        if (requisicao.getSession(false) != null) requisicao.changeSessionId()
        val estrategia = SecurityContextHolder.getContextHolderStrategy()
        val novoContexto = estrategia.createEmptyContext().apply { authentication = autenticado }
        estrategia.context = novoContexto
        contexto.saveContext(novoContexto, requisicao, resposta)
        // A hora do último uso nasce com a sessão: os pedidos que a tela faz juntos logo depois só a leem
        requisicao.getSession().setAttribute(InatividadeDaSessao.ULTIMO_USO, relogioDaSessao.relogio.millis())
        // Token CSRF novo a cada login: um token visto antes de entrar não serve depois
        tokensCsrf.saveToken(tokensCsrf.generateToken(requisicao), requisicao, resposta)
    }
}
