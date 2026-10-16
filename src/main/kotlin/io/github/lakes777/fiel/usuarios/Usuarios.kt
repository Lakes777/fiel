package io.github.lakes777.fiel.usuarios

import io.github.lakes777.fiel.erros.Conflito
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.razao.NovaPartida
import io.github.lakes777.fiel.razao.NovoLancamento
import io.github.lakes777.fiel.razao.Razao
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** "Ana@X.com " e "ana@x.com" são o mesmo e-mail. */
fun normalizarEmail(email: String): String = email.trim().lowercase()

data class NovoUsuario(
    val id: Long,
    val nome: String,
    val email: String,
    val carteiraId: Long,
)

/** Quem pode entrar: o Spring Security procura o usuário aqui pelo e-mail digitado no login. */
@Service
class Usuarios(
    private val jdbc: JdbcClient,
) : UserDetailsService {
    override fun loadUserByUsername(email: String): UserDetails =
        jdbc
            .sql("select id, nome, email, senha from usuario where email = :email")
            .param("email", normalizarEmail(email))
            .query {
                rs,
                _,
                ->
                UsuarioLogado(rs.getLong("id"), rs.getString("nome"), rs.getString("email"), rs.getString("senha"))
            }.optional()
            .orElseThrow { UsernameNotFoundException("e-mail sem cadastro") }
}

/**
 * Cria o usuário, a carteira dele e o bônus de boas-vindas numa transação só: ninguém fica com
 * usuário sem carteira, nem com carteira sem o bônus.
 */
@Service
class Cadastro(
    private val jdbc: JdbcClient,
    private val razao: Razao,
    private val senhas: PasswordEncoder,
    transacoes: PlatformTransactionManager,
) {
    private val transacao = TransactionTemplate(transacoes)

    fun cadastrar(
        nome: String,
        email: String,
        senha: String,
    ): NovoUsuario {
        exigirSenhaValida(senha)
        // O bcrypt leva ~100 ms de propósito: calcula antes, para não segurar a transação aberta
        val hash = checkNotNull(senhas.encode(senha))
        return try {
            checkNotNull(transacao.execute { gravar(nome.trim(), normalizarEmail(email), hash) })
        } catch (_: DuplicateKeyException) {
            throw Conflito("este e-mail já tem cadastro")
        }
    }

    private fun gravar(
        nome: String,
        email: String,
        hash: String,
    ): NovoUsuario {
        val id =
            jdbc
                .sql("insert into usuario (nome, email, senha) values (:nome, :email, :senha) returning id")
                .param("nome", nome)
                .param("email", email)
                .param("senha", hash)
                .query(Long::class.java)
                .single()
        val carteira =
            jdbc
                .sql("insert into conta (nome, tipo, dono_id) values (:nome, 'carteira', :dono) returning id")
                .param("nome", nome)
                .param("dono", id)
                .query(Long::class.java)
                .single()
        val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()
        razao.registrar(
            NovoLancamento(
                "Bônus de boas-vindas",
                listOf(
                    NovaPartida(emissao, Centavos(-BONUS_CENTAVOS)),
                    NovaPartida(carteira, Centavos(BONUS_CENTAVOS)),
                ),
            ),
        )
        return NovoUsuario(id, nome, email, carteira)
    }

    companion object {
        /** R$ 1.000,00 de mentira para cada conta nova. */
        const val BONUS_CENTAVOS = 100_000L
        const val MIN_SENHA = 10

        /** O bcrypt só usa os primeiros 72 bytes; senha maior seria cortada sem aviso. */
        const val MAX_SENHA_BYTES = 72

        fun exigirSenhaValida(senha: String) {
            if (senha.length < MIN_SENHA) throw PedidoInvalido("a senha precisa ter pelo menos $MIN_SENHA caracteres")
            if (senha.toByteArray().size > MAX_SENHA_BYTES) {
                throw PedidoInvalido(
                    "a senha pode ter no máximo $MAX_SENHA_BYTES bytes (cerca de 72 letras sem acento)",
                )
            }
        }
    }
}
