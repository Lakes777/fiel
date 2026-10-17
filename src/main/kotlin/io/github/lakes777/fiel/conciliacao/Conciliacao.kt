package io.github.lakes777.fiel.conciliacao

import io.github.lakes777.fiel.razao.Centavos
import io.github.lakes777.fiel.transferencias.Limites
import io.github.lakes777.fiel.transferencias.Saidas
import io.github.lakes777.fiel.usuarios.Cadastro
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** O que a conciliação achou: [divergencias] vazia quer dizer que tudo fecha. */
data class ResultadoDaConciliacao(
    val dia: LocalDate,
    val divergencias: List<String>,
    val totais: Map<String, Long>,
) {
    val fecha get() = divergencias.isEmpty()
}

/**
 * A conferência noturna: refaz, com SQL, as contas que os gatilhos já garantem uma a uma, e olha o
 * sistema inteiro de uma vez. Os gatilhos impedem o erro na entrada; a conciliação acha o que
 * entrou por fora deles (o dono das tabelas pode desligar gatilhos, um backup restaurado pela
 * metade, uma migração com bug) antes que alguém perceba pelo saldo.
 *
 * Tudo numa transação REPEATABLE READ e só de leitura: as consultas veem a mesma foto do banco,
 * então um pagamento feito no meio da conferência não aparece numa soma e falta na outra.
 */
@Service
class Conciliacao(
    private val jdbc: JdbcClient,
    private val telegram: Telegram,
    private val json: JsonMapper,
    private val relogio: Clock,
    transacoes: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val foto =
        TransactionTemplate(transacoes).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }
    private val transacao = TransactionTemplate(transacoes)

    /**
     * Confere, grava o resultado do dia e manda pelo Telegram. Se outra instância já conciliou
     * hoje, não grava nem manda de novo; devolve null. Se o Telegram estiver fora do ar, a mensagem
     * fica pendente e [avisarPendentes] tenta de novo.
     */
    fun conciliar(): ResultadoDaConciliacao? {
        val resultado = checkNotNull(foto.execute { conferir() })
        val gravou =
            checkNotNull(
                transacao.execute {
                    jdbc
                        .sql(
                            """
                            insert into conciliacao (dia, feita_em, divergencias, totais)
                            values (:dia, :em, cast(:divergencias as jsonb), cast(:totais as jsonb))
                            on conflict (dia) do nothing
                            """.trimIndent(),
                        ).param("dia", resultado.dia)
                        .param("em", OffsetDateTime.ofInstant(relogio.instant(), ZoneOffset.UTC))
                        .param("divergencias", json.writeValueAsString(resultado.divergencias))
                        .param("totais", json.writeValueAsString(resultado.totais))
                        .update() == 1
                },
            )
        if (!gravou) {
            log.info("conciliação de {} já feita por outra instância", resultado.dia)
            return null
        }
        if (resultado.fecha) {
            log.info("conciliação de {}: tudo fecha", resultado.dia)
        } else {
            log.error(
                "conciliação de {}: {} divergência(s): {}",
                resultado.dia,
                resultado.divergencias.size,
                resultado.divergencias,
            )
        }
        avisarPendentes()
        return resultado
    }

    /**
     * Manda ao Telegram as conciliações dos últimos [DIAS_PARA_AVISAR] dias que ainda não chegaram.
     * Cada uma é travada com SKIP LOCKED enquanto vai: outra instância pula, e ninguém manda duas
     * vezes. A trava segura uma linha desta tabela pequena durante o envio (até uns 20 s), o que
     * não atrapalha mais nada.
     */
    fun avisarPendentes() {
        if (!telegram.configurado) return
        val desde = LocalDate.ofInstant(relogio.instant(), Limites.FUSO).minusDays(DIAS_PARA_AVISAR)
        while (checkNotNull(transacao.execute { avisarUma(desde) })) {
            // continua enquanto houver pendente que chegou
        }
    }

    /** true se mandou uma; false se não havia pendente ou se o Telegram não aceitou (tenta na próxima vez). */
    private fun avisarUma(desde: LocalDate): Boolean {
        val pendente =
            jdbc
                .sql(
                    """
                    select dia, divergencias::text, totais::text from conciliacao
                    where avisada_em is null and dia >= :desde
                    order by dia limit 1 for update skip locked
                    """.trimIndent(),
                ).param("desde", desde)
                .query { rs, _ -> lido(rs.getObject(1, LocalDate::class.java), rs.getString(2), rs.getString(3)) }
                .optional()
                .orElse(null)
        val chegou = pendente != null && telegram.enviar(mensagem(pendente))
        if (chegou) {
            jdbc
                .sql("update conciliacao set avisada_em = :em where dia = :dia")
                .param("em", OffsetDateTime.ofInstant(relogio.instant(), ZoneOffset.UTC))
                .param("dia", pendente.dia)
                .update()
        } else if (pendente != null && !pendente.fecha) {
            log.error("a conciliação de {} tem divergência e o aviso não chegou ao Telegram", pendente.dia)
        }
        return chegou
    }

    private fun lido(
        dia: LocalDate,
        divergencias: String,
        totais: String,
    ) = ResultadoDaConciliacao(
        dia,
        json.readValue(divergencias, Array<String>::class.java).toList(),
        json.readTree(totais).properties().associate { (chave, valor) -> chave to valor.asLong() },
    )

    /**
     * Só confere, sem gravar nem avisar. Chamar dentro de uma transação (a [conciliar] usa uma
     * REPEATABLE READ); os testes chamam dentro de uma que estragam de propósito e desfazem.
     */
    fun conferir(): ResultadoDaConciliacao {
        val agora = relogio.instant()
        val divergencias =
            CONFERENCIAS.flatMap { (consulta, frase) ->
                jdbc
                    .sql(consulta)
                    .query { rs, _ -> frase(rs.getLong(1), rs.getLong(2)) }
                    .list()
            }
        return ResultadoDaConciliacao(LocalDate.ofInstant(agora, Limites.FUSO), divergencias, totais(agora))
    }

    private fun totais(agora: Instant): Map<String, Long> {
        val desde = OffsetDateTime.ofInstant(agora.minus(java.time.Duration.ofDays(1)), ZoneOffset.UTC)
        return jdbc
            .sql(
                """
                select (select count(*) from lancamento) as lancamentos,
                       (select coalesce(sum(saldo), 0) from conta where tipo = 'carteira') as em_carteiras,
                       (select count(*) from transferencia where criada_em >= :desde) as transferencias,
                       (select coalesce(sum(valor), 0) from transferencia where criada_em >= :desde) as transferido,
                       (select count(*) from cobranca where paga_em >= :desde) as pagamentos,
                       (select coalesce(sum(valor), 0) from cobranca where paga_em >= :desde) as pago,
                       (select count(*) from estorno where criado_em >= :desde) as estornos,
                       (select coalesce(sum(valor), 0) from estorno where criado_em >= :desde) as estornado
                """.trimIndent(),
            ).param("desde", desde)
            .query { rs, _ ->
                TOTAIS.associateWith { rs.getLong(it) }
            }.single()
    }

    companion object {
        private const val DIAS_PARA_AVISAR = 7L

        private val TOTAIS =
            listOf(
                "lancamentos",
                "em_carteiras",
                "transferencias",
                "transferido",
                "pagamentos",
                "pago",
                "estornos",
                "estornado",
            )

        /** Até quantas linhas cada conferência traz, as mais novas primeiro: o bastante para investigar. */
        private const val ATE = 20

        private const val BONUS = Cadastro.BONUS_CENTAVOS

        /** O mesmo "partidas do lançamento" x "o que o fato diz" dos gatilhos de V4, V5 e V7. */
        private fun lancamentoDiferente(
            lancamento: String,
            de: String,
            para: String,
            valor: String,
        ) = """
            (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = $lancamento)
            is distinct from (select array_agg(c || ':' || v order by c)
                              from (values ($de, -$valor), ($para, $valor)) as p (c, v))
        """

        /**
         * Cada conferência: um SELECT que devolve as linhas com problema (duas colunas numéricas) e
         * a frase que descreve cada uma. Texto fixo, sem nada vindo de fora.
         */
        private val CONFERENCIAS: List<Pair<String, (Long, Long) -> String>> =
            listOf(
                "select sum(valor), count(*) from partida having coalesce(sum(valor), 0) <> 0" to
                    { soma, _ -> "o razão inteiro soma $soma centavos, não zero: dinheiro nasceu ou sumiu" },
                """
                select l.id, coalesce(sum(p.valor), 0) from lancamento l left join partida p on p.lancamento_id = l.id
                group by l.id having count(p.id) < 2 or coalesce(sum(p.valor), 0) <> 0 order by l.id desc limit $ATE
                """ to {
                    id,
                    soma,
                    ->
                    "o lançamento $id não fecha: soma $soma centavos (ou tem menos de duas partidas)"
                },
                """
                select c.id, c.saldo - coalesce(sum(p.valor), 0) from conta c left join partida p on p.conta_id = c.id
                group by c.id having c.saldo <> coalesce(sum(p.valor), 0) order by c.id desc limit $ATE
                """ to
                    {
                        id,
                        diferenca,
                        ->
                        "o saldo guardado da conta $id difere da soma das partidas em $diferenca centavos"
                    },
                "select id, saldo from conta where tipo = 'carteira' and saldo < 0 order by id limit $ATE" to
                    { id, saldo -> "a carteira $id está negativa: $saldo centavos" },
                """
                select t.id, t.lancamento_id from transferencia t
                where ${lancamentoDiferente("t.lancamento_id", "t.de_conta", "t.para_conta", "t.valor")}
                order by t.id desc limit $ATE
                """ to { id, l -> "a transferência $id não bate com o lançamento $l" },
                """
                select c.id, c.lancamento_id from cobranca c where c.lancamento_id is not null
                and ${lancamentoDiferente("c.lancamento_id", "c.pagador_conta", "c.recebedor_conta", "c.valor")}
                order by c.id desc limit $ATE
                """ to { id, l -> "o pagamento da cobrança $id não bate com o lançamento $l" },
                """
                select e.id, e.lancamento_id from estorno e join cobranca c on c.id = e.cobranca_id
                where ${lancamentoDiferente("e.lancamento_id", "c.recebedor_conta", "c.pagador_conta", "e.valor")}
                order by e.id desc limit $ATE
                """ to { id, l -> "o estorno $id não bate com o lançamento $l" },
                """
                select c.id, coalesce(sum(e.valor), 0) from cobranca c left join estorno e on e.cobranca_id = c.id
                group by c.id
                having (c.estado not in ('paga', 'parcialmente_estornada', 'estornada') and count(e.id) > 0)
                    or (c.estado in ('paga', 'parcialmente_estornada', 'estornada')
                        and (coalesce(sum(e.valor), 0) > c.valor
                             or c.estado <> case when coalesce(sum(e.valor), 0) = 0 then 'paga'
                                                 when sum(e.valor) = c.valor then 'estornada'
                                                 else 'parcialmente_estornada' end))
                order by c.id desc limit $ATE
                """ to { id, soma -> "a cobrança $id tem $soma centavos estornados e um estado que não combina" },
                """
                select lancamento_id, count(*) from (
                    select lancamento_id from transferencia
                    union all select lancamento_id from cobranca where lancamento_id is not null
                    union all select lancamento_id from estorno
                ) as fatos group by lancamento_id having count(*) > 1 order by lancamento_id desc limit $ATE
                """ to { l, quantos -> "o lançamento $l é de $quantos fatos ao mesmo tempo" },
                // Dinheiro só entra numa carteira por um fato (transferência, pagamento, estorno) ou
                // pelo bônus do cadastro. Lançamentos só entre contas internas (os testes do razão)
                // não mexem em dinheiro de ninguém e ficam de fora.
                """
                select l.id, count(*) from lancamento l join partida p on p.lancamento_id = l.id
                join conta c on c.id = p.conta_id
                where not exists (select 1 from transferencia t where t.lancamento_id = l.id)
                  and not exists (select 1 from cobranca b where b.lancamento_id = l.id)
                  and not exists (select 1 from estorno e where e.lancamento_id = l.id)
                group by l.id
                having bool_or(c.tipo = 'carteira')
                   and not (count(*) = 2 and bool_or(c.tipo = 'emissao' and p.valor = -$BONUS)
                            and bool_or(c.tipo = 'carteira' and p.valor = $BONUS))
                order by l.id desc limit $ATE
                """ to
                    {
                        l,
                        _,
                        ->
                        "o lançamento $l mexe numa carteira e não é transferência, pagamento, estorno nem bônus"
                    },
                """
                select p.conta_id, count(*) from partida p
                join conta c on c.id = p.conta_id and c.tipo = 'carteira'
                join partida e on e.lancamento_id = p.lancamento_id and e.valor = -$BONUS
                join conta ce on ce.id = e.conta_id and ce.tipo = 'emissao'
                where p.valor = $BONUS
                group by p.conta_id having count(*) > 1 order by p.conta_id desc limit $ATE
                """ to { conta, quantos -> "a carteira $conta recebeu $quantos bônus de cadastro" },
            )

        private const val LINHAS_NA_MENSAGEM = 10
        private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** O texto do Telegram: curto quando tudo fecha, com as primeiras divergências quando não. */
        fun mensagem(resultado: ResultadoDaConciliacao): String {
            val t = resultado.totais

            fun reais(chave: String) = Saidas.reais(Centavos(t.getValue(chave)))
            val dia = resultado.dia.format(DATA)
            val movimento =
                "Últimas 24 h: ${t["transferencias"]} transferência(s) (${reais("transferido")}), " +
                    "${t["pagamentos"]} pagamento(s) (${reais(
                        "pago",
                    )}), ${t["estornos"]} estorno(s) (${reais("estornado")})."
            if (resultado.fecha) {
                return "Fiel · conciliação de $dia\nTudo fecha: ${t["lancamentos"]} lançamentos, " +
                    "${reais("em_carteiras")} em carteiras.\n$movimento"
            }
            val primeiras = resultado.divergencias.take(LINHAS_NA_MENSAGEM).joinToString("\n") { "- $it" }
            val resto = resultado.divergencias.size - LINHAS_NA_MENSAGEM
            return "Fiel · conciliação de $dia\nATENÇÃO: ${resultado.divergencias.size} divergência(s)\n$primeiras" +
                (if (resto > 0) "\n... e mais $resto" else "") + "\n$movimento"
        }
    }
}
