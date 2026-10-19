package io.github.lakes777.fiel.integracao

import io.github.lakes777.fiel.seguranca.escreverProblema
import io.github.lakes777.fiel.usuarios.Cadastro
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import io.github.lakes777.fiel.usuarios.Usuarios
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.UUID

/**
 * A loja de outro sistema (o Foil) que usa o Fiel por programa, sem login nem cookie: uma conta fixa,
 * a "Loja Foil", garantida na partida. Ela sobrevive à recriação diária da demonstração do mesmo jeito
 * que a Loja Exemplo: o banco é recriado, a API parte de novo e a conta volta.
 *
 * Só existe com FIEL_INTEGRACAO_CHAVE definida; sem ela, nada aqui roda e o Fiel é o de sempre.
 */
@Service
class LojaIntegrada(
    @Value("\${fiel.integracao.chave:}") chave: String,
    private val usuarios: Usuarios,
    private val cadastro: Cadastro,
) {
    /** Vazia = integração desligada. */
    val chave: String = chave.trim()

    init {
        conferir(this.chave)
    }

    @Volatile
    private var loja: UsuarioLogado? = null

    @EventListener(ApplicationReadyEvent::class)
    fun semear() {
        if (chave.isNotEmpty()) usuario()
    }

    /**
     * A Loja Foil, criada na primeira vez. A senha é aleatória e ninguém a conhece: só a chave entra.
     * Sincronizado: o Tomcat já aceita pedidos antes do ApplicationReadyEvent, e dois pedidos logo depois
     * da recriação do banco tentariam cadastrar a loja juntos (o segundo daria 500).
     */
    @Synchronized
    fun usuario(): UsuarioLogado =
        loja ?: (logado() ?: cadastrar()).also {
            it.eraseCredentials()
            loja = it
        }

    private fun cadastrar(): UsuarioLogado {
        cadastro.cadastrar(NOME, EMAIL, "${UUID.randomUUID()}${UUID.randomUUID()}")
        return checkNotNull(logado())
    }

    private fun logado(): UsuarioLogado? =
        try {
            usuarios.loadUserByUsername(EMAIL) as UsuarioLogado
        } catch (_: UsernameNotFoundException) {
            null
        }

    companion object {
        const val NOME = "Loja Foil"

        /** No domínio das contas da demonstração: o cadastro público recusa este e-mail. */
        const val EMAIL = "foil@exemplo.fiel"
        const val TAMANHO_MINIMO = 32

        /** Uma chave curta poderia ser adivinhada: melhor não subir do que subir assim. */
        fun conferir(chave: String) =
            check(chave.isEmpty() || chave.length >= TAMANHO_MINIMO) {
                "FIEL_INTEGRACAO_CHAVE precisa de pelo menos $TAMANHO_MINIMO caracteres (gere com openssl rand -hex 32)"
            }
    }
}

/**
 * "Authorization: Bearer <chave>" entra como a [LojaIntegrada], só nos caminhos de [PERMITIDOS]: criar,
 * ler, cancelar e estornar as cobranças dela e cadastrar o endereço dos avisos. O resto (carteira,
 * transferências, chaves) fica só com quem entra pela tela, então uma chave vazada não move dinheiro
 * para fora da loja: no pior caso devolve o que os clientes pagaram.
 *
 * Fica antes do filtro do CSRF. Um pedido com Bearer não usa cookie, então o CSRF não protege nada
 * nele; e um site de fora não consegue mandar o cabeçalho Authorization sem uma permissão de CORS que
 * o Fiel não dá. Chave errada ou caminho fora da lista param aqui, antes de qualquer outra coisa.
 */
class ChaveDeIntegracao(
    private val loja: LojaIntegrada,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        pedido: HttpServletRequest,
        resposta: HttpServletResponse,
        corrente: FilterChain,
    ) {
        val recebida = chaveDe(pedido)
        if (recebida == null) {
            corrente.doFilter(pedido, resposta)
            return
        }
        when {
            loja.chave.isEmpty() || !iguais(recebida, loja.chave) -> {
                resposta.setHeader("WWW-Authenticate", "Bearer")
                escreverProblema(resposta, HttpStatus.UNAUTHORIZED, "chave de integração inválida")
            }
            !permitido(pedido) ->
                escreverProblema(resposta, HttpStatus.FORBIDDEN, "a chave de integração não pode usar este caminho")
            else -> {
                val usuario = loja.usuario()
                val estrategia = SecurityContextHolder.getContextHolderStrategy()
                // Só para este pedido: nada vai para a sessão (nem sessão é criada)
                estrategia.context =
                    estrategia.createEmptyContext().apply {
                        authentication = UsernamePasswordAuthenticationToken.authenticated(usuario, null, emptyList())
                    }
                try {
                    corrente.doFilter(pedido, resposta)
                } finally {
                    estrategia.clearContext()
                }
            }
        }
    }

    companion object {
        private val PERMITIDOS =
            listOf(
                "POST" to Regex("/cobrancas"),
                "GET" to Regex("/cobrancas/\\d+"),
                "POST" to Regex("/cobrancas/\\d+/cancelamento"),
                "POST" to Regex("/cobrancas/\\d+/estornos"),
                "PUT" to Regex("/webhook"),
            )

        /** O texto depois de "Bearer ", ou null se o pedido não traz chave (então é com o cookie). */
        fun chaveDe(pedido: HttpServletRequest): String? =
            pedido
                .getHeader("Authorization")
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring("Bearer ".length)
                ?.trim()

        fun permitido(pedido: HttpServletRequest): Boolean =
            PERMITIDOS.any { (metodo, caminho) -> pedido.method == metodo && caminho.matches(pedido.servletPath) }

        /**
         * Em tempo constante: um "==" para no primeiro caractere diferente, e medir quanto a resposta demora
         * revelaria a chave aos poucos. Os hashes têm sempre o mesmo tamanho, então nem o tamanho vaza.
         */
        fun iguais(
            recebida: String,
            esperada: String,
        ): Boolean = MessageDigest.isEqual(sha256(recebida), sha256(esperada))

        private fun sha256(texto: String) = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
    }
}
