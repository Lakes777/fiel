package io.github.lakes777.fiel.contas

import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ContaDoUsuario(
    val id: Long,
    val nome: String,
    val saldoCentavos: Long,
)

data class LinhaDoExtrato(
    val lancamentoId: Long,
    val descricao: String,
    val em: Instant,
    val valorCentavos: Long,
    val saldoDepoisCentavos: Long,
)

/**
 * Autorização por objeto: estar logado não basta, a conta precisa ser sua. Toda rota que recebe o
 * id de uma conta passa por [exigirDono]; a conta de outra pessoa responde igual a uma que não existe.
 */
@Service
class Contas(
    private val jdbc: JdbcClient,
) {
    fun doUsuario(usuarioId: Long): List<Pair<Long, String>> =
        jdbc
            .sql("select id, nome from conta where dono_id = :dono order by id")
            .param("dono", usuarioId)
            .query { rs, _ -> rs.getLong("id") to rs.getString("nome") }
            .list()

    fun exigirDono(
        contaId: Long,
        usuarioId: Long,
    ) {
        val dele =
            jdbc
                .sql("select exists (select 1 from conta where id = :conta and dono_id = :dono)")
                .param("conta", contaId)
                .param("dono", usuarioId)
                .query(Boolean::class.java)
                .single()
        if (!dele) throw NaoEncontrado("conta não encontrada")
    }
}

@RestController
class ContasController(
    private val contas: Contas,
    private val razao: Razao,
) {
    @GetMapping("/contas")
    fun minhas(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = contas.doUsuario(usuario.id).map { (id, nome) -> ContaDoUsuario(id, nome, razao.saldo(id).valor) }

    @GetMapping("/contas/{id}/extrato")
    fun extrato(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "50") limite: Int,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): List<LinhaDoExtrato> {
        contas.exigirDono(id, usuario.id)
        if (limite !in 1..Razao.MAX_EXTRATO) throw PedidoInvalido("o limite vai de 1 a ${Razao.MAX_EXTRATO}")
        return razao.extrato(id, limite).map {
            LinhaDoExtrato(it.lancamentoId, it.descricao, it.em, it.valor.valor, it.saldoDepois.valor)
        }
    }
}
