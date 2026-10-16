package io.github.lakes777.fiel.usuarios

import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Quem está logado. Vai para a sessão, que fica serializada no Postgres, então leva só o
 * necessário: o hash da senha é apagado logo depois do login ([eraseCredentials]).
 *
 * O "username" para o Spring Security é o id, não o e-mail: é ele que a tabela de sessões guarda
 * para achar as sessões de alguém ("sair de todos os aparelhos").
 */
class UsuarioLogado(
    val id: Long,
    val nome: String,
    val email: String,
    private var senha: String?,
) : UserDetails,
    CredentialsContainer {
    override fun getUsername(): String = id.toString()

    override fun getPassword(): String? = senha

    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun eraseCredentials() {
        senha = null
    }

    private companion object {
        private const val serialVersionUID: Long = 1
    }
}
