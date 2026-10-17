package io.github.lakes777.fiel.seguranca

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

/**
 * "Sessão parada há 15 min acaba", contando só o uso de verdade. O Spring Session renova a sessão
 * a cada pedido, e a tela ao vivo reconecta GET /eventos sozinha: sem este filtro, uma aba aberta
 * esquecida manteria a sessão viva para sempre.
 *
 * Guarda na sessão a hora do último pedido que não seja /eventos. Passou do limite, a sessão é
 * apagada antes do Spring Security olhar, e o pedido segue como se não houvesse login (401). O
 * /eventos recebe quanto falta ([FALTA]) e fecha a conexão nessa hora.
 */
class InatividadeDaSessao(
    private val limite: Duration,
    private val relogio: RelogioDaSessao,
) : OncePerRequestFilter() {
    override fun shouldNotFilterAsyncDispatch() = true

    override fun doFilterInternal(
        pedido: HttpServletRequest,
        resposta: HttpServletResponse,
        corrente: FilterChain,
    ) {
        val sessao = pedido.getSession(false)
        if (sessao != null) {
            val agora = relogio.relogio.millis()
            val ultimo = sessao.getAttribute(ULTIMO_USO) as Long?
            if (ultimo != null && agora - ultimo >= limite.toMillis()) {
                sessao.invalidate()
            } else {
                // O login já grava a hora (ver SessaoController.entrar). Aqui só se regrava depois de
                // [REGRAVAR_APOS]: atributo novo gravado por vários pedidos ao mesmo tempo esbarra na
                // chave do Spring Session JDBC (a tela abre fazendo seis pedidos de uma vez), e regravar
                // a cada pedido seria uma escrita no banco por pedido, sem ganho
                val renovar = pedido.servletPath != "/eventos" && (ultimo == null || agora - ultimo >= REGRAVAR_APOS_MS)
                val desde = if (renovar) agora else ultimo ?: agora
                if (renovar) sessao.setAttribute(ULTIMO_USO, desde)
                pedido.setAttribute(FALTA, limite.minusMillis(agora - desde))
            }
        }
        corrente.doFilter(pedido, resposta)
    }

    companion object {
        const val ULTIMO_USO = "fiel.ultimoUso"
        private const val REGRAVAR_APOS_MS = 30_000L

        /** Atributo do pedido: a [Duration] que falta para a sessão vencer por falta de uso. */
        const val FALTA = "fiel.faltaParaVencer"
    }
}

/**
 * O relógio de verdade da sessão, separado do relógio da API: os testes param o da API em outra
 * data para conferir limites de dia e de noite, e isso não pode derrubar a sessão por "inatividade".
 */
class RelogioDaSessao(
    val relogio: Clock,
)

@Configuration
class ConfiguracaoDaInatividade {
    @Bean
    fun relogioDaSessao() = RelogioDaSessao(Clock.systemUTC())

    /** Logo antes do Spring Security, e depois do filtro do Spring Session (que vem bem antes). */
    @Bean
    fun inatividadeDaSessao(
        @Value("\${server.servlet.session.timeout:15m}") limite: Duration,
        relogio: RelogioDaSessao,
    ) = FilterRegistrationBean(InatividadeDaSessao(limite, relogio)).apply {
        order = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1
    }
}
