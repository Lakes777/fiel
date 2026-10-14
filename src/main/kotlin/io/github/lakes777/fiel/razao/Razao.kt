package io.github.lakes777.fiel.razao

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime

/** O livro-razão: abre contas, grava lançamentos e responde saldos e extratos. */
@Service
class Razao(
    private val jdbc: JdbcClient,
) {
    fun abrirConta(nome: String): Conta {
        require(nome.isNotBlank() && nome.length <= MAX_NOME) { "o nome precisa ter de 1 a $MAX_NOME caracteres" }
        return jdbc
            .sql("insert into conta (nome) values (:nome) returning id, nome, criada_em")
            .param("nome", nome)
            .query { rs, _ -> Conta(rs.getLong("id"), rs.getString("nome"), rs.instante("criada_em")) }
            .single()
    }

    /**
     * Grava o lançamento e as partidas numa transação só. O banco confere a soma zero no commit:
     * se ela não fechar, nada fica gravado.
     */
    @Transactional
    fun registrar(novo: NovoLancamento): Lancamento {
        exigirContas(novo.partidas.map { it.contaId })
        val (id, criadoEm) =
            jdbc
                .sql("insert into lancamento (descricao) values (:descricao) returning id, criado_em")
                .param("descricao", novo.descricao)
                .query { rs, _ -> rs.getLong("id") to rs.instante("criado_em") }
                .single()
        // Todas as partidas num INSERT só, a partir de duas listas paralelas
        val partidas =
            jdbc
                .sql(
                    """
                    insert into partida (lancamento_id, conta_id, valor)
                    select :lancamento, conta, valor
                    from unnest(:contas::bigint[], :valores::bigint[]) as p (conta, valor)
                    returning id, conta_id, valor
                    """.trimIndent(),
                ).param("lancamento", id)
                .param("contas", novo.partidas.map { it.contaId }.toTypedArray())
                .param("valores", novo.partidas.map { it.valor.valor }.toTypedArray())
                .query { rs, _ -> Partida(rs.getLong("id"), rs.getLong("conta_id"), Centavos(rs.getLong("valor"))) }
                .list()
        return Lancamento(id, novo.descricao, criadoEm, partidas.sortedBy { it.id })
    }

    fun saldo(contaId: Long): Centavos {
        exigirContas(listOf(contaId))
        val soma =
            jdbc
                .sql("select coalesce(sum(valor), 0) from partida where conta_id = :conta")
                .param("conta", contaId)
                .query(BigDecimal::class.java)
                .single()
        return Centavos(soma.longValueExact())
    }

    /** Os movimentos mais recentes primeiro, cada um com o saldo da conta logo depois dele. */
    fun extrato(
        contaId: Long,
        limite: Int = 50,
    ): List<Movimento> {
        require(limite in 1..MAX_EXTRATO) { "o limite do extrato vai de 1 a $MAX_EXTRATO" }
        exigirContas(listOf(contaId))
        return jdbc
            .sql(
                """
                select p.lancamento_id, l.descricao, l.criado_em, p.valor,
                       sum(p.valor) over (order by p.id) as saldo_depois
                from partida p
                join lancamento l on l.id = p.lancamento_id
                where p.conta_id = :conta
                order by p.id desc
                limit :limite
                """.trimIndent(),
            ).param("conta", contaId)
            .param("limite", limite)
            .query { rs, _ ->
                Movimento(
                    lancamentoId = rs.getLong("lancamento_id"),
                    descricao = rs.getString("descricao"),
                    em = rs.instante("criado_em"),
                    valor = Centavos(rs.getLong("valor")),
                    saldoDepois = Centavos(rs.getBigDecimal("saldo_depois").longValueExact()),
                )
            }.list()
    }

    private fun exigirContas(ids: Collection<Long>) {
        val existentes =
            jdbc
                .sql("select id from conta where id = any(:ids::bigint[])")
                .param("ids", ids.toTypedArray())
                .query { rs, _ -> rs.getLong("id") }
                .set()
        val faltando = ids.toSet() - existentes
        if (faltando.isNotEmpty()) throw ContaNaoEncontrada(faltando)
    }

    private fun ResultSet.instante(coluna: String) = getObject(coluna, OffsetDateTime::class.java).toInstant()

    companion object {
        const val MAX_NOME = 100
        const val MAX_EXTRATO = 500
    }
}
