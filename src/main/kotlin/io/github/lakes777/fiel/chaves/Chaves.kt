package io.github.lakes777.fiel.chaves

import io.github.lakes777.fiel.erros.Conflito
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class Chave(
    val id: Long,
    val tipo: String,
    val valor: String,
    val criadaEm: Instant,
)

data class PedidoDeChave(
    @Pattern(regexp = "email|aleatoria", message = "o tipo é email ou aleatoria") val tipo: String,
)

data class PedidoDeConsulta(
    @NotBlank @Size(max = 254) val valor: String,
)

/** O que quem vai transferir vê antes de confirmar: o suficiente para conferir, não para identificar. */
data class DonoDaChave(
    val nome: String,
    val tipo: String,
)

/** "Ana Maria Souza" vira "Ana S.": dá para conferir se é a pessoa certa sem expor o nome todo. */
fun mascararNome(nome: String): String {
    val partes = nome.trim().split(Regex("\\s+"))
    return if (partes.size == 1) partes.single() else "${partes.first()} ${partes.last().first()}."
}

/**
 * Chaves de transferência: um apelido para a carteira, para quem paga não precisar do número da conta.
 * Dois tipos, nenhum com dado de documento: o e-mail do próprio dono e uma aleatória (UUID).
 */
@Service
class Chaves(
    private val jdbc: JdbcClient,
) {
    fun doUsuario(usuarioId: Long): List<Chave> =
        jdbc
            .sql(
                """
                select k.id, k.tipo, k.valor, k.criada_em
                from chave k join conta c on c.id = k.conta_id
                where c.dono_id = :dono
                order by k.id
                """.trimIndent(),
            ).param("dono", usuarioId)
            .query { rs, _ -> rs.chave() }
            .list()

    fun criar(
        usuario: UsuarioLogado,
        tipo: String,
    ): Chave {
        val carteira =
            jdbc
                .sql("select id from conta where dono_id = :dono and tipo = 'carteira'")
                .param("dono", usuario.id)
                .query(Long::class.java)
                .single()
        val valor = if (tipo == "email") usuario.email else UUID.randomUUID().toString()
        if (doUsuario(usuario.id).size >= MAX_CHAVES) throw limiteDeChaves()
        return try {
            jdbc
                .sql(
                    """
                    insert into chave (conta_id, tipo, valor) values (:conta, :tipo, :valor)
                    returning id, tipo, valor, criada_em
                    """.trimIndent(),
                ).param("conta", carteira)
                .param("tipo", tipo)
                .param("valor", valor)
                .query { rs, _ -> rs.chave() }
                .single()
        } catch (erro: DataIntegrityViolationException) {
            // Fora a chave repetida, só sobra o limite: duas chaves criadas ao mesmo tempo passaram
            // juntas pela conferência acima, e o contador da conta no banco barrou uma
            throw if (erro is DuplicateKeyException) Conflito("esta chave já está cadastrada") else limiteDeChaves()
        }
    }

    private fun limiteDeChaves() = Conflito("a conta já tem $MAX_CHAVES chaves")

    fun apagar(
        usuarioId: Long,
        chaveId: Long,
    ) {
        val apagadas =
            jdbc
                .sql("delete from chave where id = :id and conta_id in (select id from conta where dono_id = :dono)")
                .param("id", chaveId)
                .param("dono", usuarioId)
                .update()
        if (apagadas == 0) throw NaoEncontrado("chave não encontrada")
    }

    fun consultar(valor: String): DonoDaChave =
        jdbc
            .sql(
                """
                select u.nome, k.tipo
                from chave k join conta c on c.id = k.conta_id join usuario u on u.id = c.dono_id
                where k.valor = :valor
                """.trimIndent(),
            ).param("valor", valor.trim().lowercase())
            .query { rs, _ -> DonoDaChave(mascararNome(rs.getString("nome")), rs.getString("tipo")) }
            .optional()
            .orElseThrow { NaoEncontrado("chave não encontrada") }

    private fun ResultSet.chave() =
        Chave(
            getLong("id"),
            getString("tipo"),
            getString("valor"),
            getObject("criada_em", OffsetDateTime::class.java).toInstant(),
        )

    companion object {
        const val MAX_CHAVES = 5
    }
}

@RestController
@RequestMapping("/chaves")
class ChavesController(
    private val chaves: Chaves,
    private val limites: LimitesDeUso,
) {
    @GetMapping
    fun minhas(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = chaves.doUsuario(usuario.id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun criar(
        @Valid @RequestBody pedido: PedidoDeChave,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = chaves.criar(usuario, pedido.tipo)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun apagar(
        @PathVariable id: Long,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = chaves.apagar(usuario.id, id)

    /** POST e não GET: a chave (um e-mail) iria parar na URL, e a URL vai para logs e histórico. */
    @PostMapping("/consulta")
    fun consultar(
        @Valid @RequestBody pedido: PedidoDeConsulta,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): DonoDaChave {
        limites.consultaDeChave.registrar(usuario.username)
        return chaves.consultar(pedido.valor)
    }
}
