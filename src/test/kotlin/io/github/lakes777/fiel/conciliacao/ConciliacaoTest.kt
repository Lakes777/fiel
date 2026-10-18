package io.github.lakes777.fiel.conciliacao

import com.sun.net.httpserver.HttpServer
import io.github.lakes777.fiel.Navegador
import io.github.lakes777.fiel.RelogioDeTeste
import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.COMO_DONO
import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.PAPEL_DA_API
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.razao.transferencia
import io.github.lakes777.fiel.usuarios.Cadastro
import io.github.lakes777.fiel.usuarios.NovoUsuario
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A conciliação precisa achar o que os gatilhos não deixariam entrar. Para provar, cada teste
 * desliga os gatilhos (session_replication_role = replica, só para superusuário), estraga o banco
 * dentro de uma transação, confere e desfaz: o banco compartilhado pelos testes continua inteiro.
 * O estrago roda como dono ([COMO_DONO]): o usuário da API não consegue nada disso; a conferência
 * volta a rodar com ele.
 */
@TesteDeIntegracao
class ConciliacaoTest {
    @Autowired
    private lateinit var conciliacao: Conciliacao

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacoes: PlatformTransactionManager

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Autowired
    private lateinit var cadastro: Cadastro

    @Autowired
    private lateinit var razao: Razao

    @Autowired
    private lateinit var relogio: RelogioDeTeste

    @Autowired
    private lateinit var json: JsonMapper

    @AfterEach
    fun soltarRelogio() = relogio.soltar()

    private fun pessoa(): NovoUsuario =
        cadastro.cadastrar("Ana Souza", "${UUID.randomUUID()}@teste.fiel", "senha-de-teste-123")

    private fun sql(
        comando: String,
        vararg parametros: Pair<String, Any>,
    ) = parametros.fold(jdbc.sql(comando)) { s, (nome, valor) -> s.param(nome, valor) }.update()

    /**
     * Estraga o banco ([semGatilhos]: com os gatilhos desligados), confere e desfaz tudo. Devolve só
     * o que o estrago trouxe: o banco dos testes já tem os depósitos de teste (ver abaixo).
     */
    private fun conferirDepoisDe(
        semGatilhos: Boolean = true,
        estrago: () -> Unit,
    ): List<String> =
        checkNotNull(
            transacao.execute { status ->
                val antes = conciliacao.conferir().divergencias.toSet()
                sql(COMO_DONO)
                if (semGatilhos) sql("set local session_replication_role = replica")
                estrago()
                sql("set local session_replication_role = origin")
                sql("set local role $PAPEL_DA_API")
                status.setRollbackOnly()
                conciliacao.conferir().divergencias.filterNot { it in antes }
            },
        )

    /** Uma cobrança de R$ 5 da loja paga pela Ana, com o id do lançamento do pagamento. */
    private fun cobrancaPaga(
        loja: NovoUsuario,
        ana: NovoUsuario,
    ): Pair<Long, Long> =
        checkNotNull(
            transacao.execute {
                val id =
                    jdbc
                        .sql(
                            """
                            insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                            values (gen_random_uuid(), :c, 500, 'teste', now(), now() + interval '30 minutes')
                            returning id
                            """.trimIndent(),
                        ).param("c", loja.carteiraId)
                        .query(Long::class.java)
                        .single()
                val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 500))
                sql(
                    "update cobranca set estado = 'paga', pagador_conta = :p, lancamento_id = :l, paga_em = now() " +
                        "where id = :id",
                    "p" to ana.carteiraId,
                    "l" to l.id,
                    "id" to id,
                )
                id to l.id
            },
        )

    @Test
    fun `o banco dos testes fecha, menos os depósitos de teste`() {
        val (loja, ana) = pessoa() to pessoa()
        cobrancaPaga(loja, ana)

        val resultado = checkNotNull(transacao.execute { conciliacao.conferir() })

        // Alguns testes põem dinheiro numa carteira direto da emissão, sem transferência: é o
        // lançamento sem fato que a conciliação existe para acusar. Fora eles, nada
        val semFato =
            Regex("o lançamento (\\d+) mexe numa carteira e não é transferência, pagamento, estorno nem bônus")
        for (divergencia in resultado.divergencias) {
            val id =
                semFato
                    .matchEntire(divergencia)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            val descricao =
                id?.let {
                    jdbc
                        .sql("select descricao from lancamento where id = :id")
                        .param("id", it)
                        .query(String::class.java)
                        .single()
                }
            assertEquals("depósito de teste", descricao, divergencia)
        }
        assertTrue(resultado.totais.getValue("lancamentos") > 0)
        assertTrue(resultado.totais.getValue("pagamentos") >= 1)
    }

    @Test
    fun `acha partida sem par, e o razão inteiro e o lançamento deixam de fechar`() {
        val ana = pessoa()

        val achados =
            conferirDepoisDe {
                val l = razao.registrar(transferencia(ana.carteiraId, pessoa().carteiraId, 100))
                sql(
                    "insert into partida (lancamento_id, conta_id, valor) values (:l, :c, 999)",
                    "l" to l.id,
                    "c" to pessoa().carteiraId,
                )
            }

        assertTrue(achados.any { it.startsWith("o razão inteiro soma 999 centavos") }, achados.toString())
        assertTrue(
            achados.any { it.matches(Regex("o lançamento \\d+ não fecha: soma 999 centavos.*")) },
            achados.toString(),
        )
    }

    @Test
    fun `acha saldo guardado que não bate com as partidas`() {
        val ana = pessoa()

        val achados =
            conferirDepoisDe { sql("update conta set saldo = saldo + 1 where id = :id", "id" to ana.carteiraId) }

        assertEquals(
            listOf("o saldo guardado da conta ${ana.carteiraId} difere da soma das partidas em 1 centavos"),
            achados,
        )
    }

    @Test
    fun `acha carteira negativa`() {
        val ana = pessoa()

        val achados =
            // Com os gatilhos ligados, para o saldo guardado acompanhar; só o check sai (e volta no rollback)
            conferirDepoisDe(semGatilhos = false) {
                sql("alter table conta drop constraint carteira_nunca_negativa")
                razao.registrar(transferencia(ana.carteiraId, pessoa().carteiraId, 100_001))
            }

        // O lançamento feito à mão também não tem fato, e aparece como tal
        assertTrue(achados.contains("a carteira ${ana.carteiraId} está negativa: -1 centavos"), achados.toString())
        assertEquals(2, achados.size, achados.toString())
    }

    @Test
    fun `acha transferência, pagamento e estorno que não batem com o lançamento`() {
        val (loja, ana) = pessoa() to pessoa()
        val (cobranca, _) = cobrancaPaga(loja, ana)
        val (paraEstornar, _) = cobrancaPaga(loja, ana)
        val transferida =
            checkNotNull(
                transacao.execute {
                    val l = razao.registrar(transferencia(ana.carteiraId, loja.carteiraId, 300))
                    jdbc
                        .sql(
                            """
                            insert into transferencia (usuario_id, chave_idempotencia, resumo_do_pedido,
                                                       de_conta, para_conta, valor, lancamento_id, criada_em)
                            values (:u, :k, :r, :de, :para, 300, :l, now()) returning id
                            """.trimIndent(),
                        ).param("u", ana.id)
                        .param("k", UUID.randomUUID().toString())
                        .param("r", "0".repeat(64))
                        .param("de", ana.carteiraId)
                        .param("para", loja.carteiraId)
                        .param("l", l.id)
                        .query(Long::class.java)
                        .single()
                },
            )
        val estorno =
            checkNotNull(
                transacao.execute {
                    val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 200))
                    jdbc
                        .sql(
                            """
                            insert into estorno (cobranca_id, chave_idempotencia, resumo_do_pedido, valor, lancamento_id, criado_em)
                            values (:c, :k, :r, 200, :l, now()) returning id
                            """.trimIndent(),
                        ).param("c", paraEstornar)
                        .param("k", UUID.randomUUID().toString())
                        .param("r", "0".repeat(64))
                        .param("l", l.id)
                        .query(Long::class.java)
                        .single()
                },
            )

        val achados =
            conferirDepoisDe {
                sql("update transferencia set valor = 301 where id = :id", "id" to transferida)
                sql("update cobranca set valor = 499 where id = :id", "id" to cobranca)
                sql("update estorno set valor = 201 where id = :id", "id" to estorno)
            }

        assertTrue(achados.any { it.startsWith("a transferência $transferida não bate") }, achados.toString())
        assertTrue(achados.any { it.startsWith("o pagamento da cobrança $cobranca não bate") }, achados.toString())
        assertTrue(achados.any { it.startsWith("o estorno $estorno não bate") }, achados.toString())
        assertEquals(3, achados.size, achados.toString())
    }

    @Test
    fun `acha cobrança com estado que não combina com os estornos`() {
        val (loja, ana) = pessoa() to pessoa()
        val (cobranca, _) = cobrancaPaga(loja, ana)

        val achados =
            conferirDepoisDe { sql("update cobranca set estado = 'estornada' where id = :id", "id" to cobranca) }

        assertEquals(listOf("a cobrança $cobranca tem 0 centavos estornados e um estado que não combina"), achados)
    }

    @Test
    fun `acha lançamento usado por dois fatos`() {
        val (loja, ana) = pessoa() to pessoa()
        val (_, lancamento) = cobrancaPaga(loja, ana)

        val achados =
            conferirDepoisDe {
                sql(
                    """
                    insert into transferencia (usuario_id, chave_idempotencia, resumo_do_pedido,
                                               de_conta, para_conta, valor, lancamento_id, criada_em)
                    values (:u, :k, :r, :de, :para, 500, :l, now())
                    """.trimIndent(),
                    "u" to ana.id,
                    "k" to UUID.randomUUID().toString(),
                    "r" to "0".repeat(64),
                    "de" to ana.carteiraId,
                    "para" to loja.carteiraId,
                    "l" to lancamento,
                )
            }

        assertEquals(listOf("o lançamento $lancamento é de 2 fatos ao mesmo tempo"), achados)
    }

    /** Um Telegram de mentira: guarda o caminho e o corpo de cada mensagem. */
    private class TelegramDeTeste : AutoCloseable {
        val recebidas: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val status = AtomicInteger(200)
        private val servidor =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { troca ->
                    recebidas += troca.requestURI.path to troca.requestBody.readAllBytes().decodeToString()
                    troca.sendResponseHeaders(status.get(), -1)
                    troca.close()
                }
                start()
            }
        val url = "http://127.0.0.1:${servidor.address.port}"

        override fun close() = servidor.stop(0)
    }

    @Test
    fun `concilia uma vez por dia e manda o resultado ao Telegram`() {
        TelegramDeTeste().use { falso ->
            val telegram = Telegram("123:abc", "42", falso.url, json)
            val comTelegram = Conciliacao(jdbc, telegram, json, relogio, transacoes)
            // Um dia só deste teste: a tabela guarda um resultado por dia
            relogio.parar(Instant.parse("2031-03-15T06:00:00Z"))

            val primeira = comTelegram.conciliar()
            val segunda = comTelegram.conciliar()

            assertEquals(LocalDate.of(2031, 3, 15), primeira?.dia)
            assertNull(segunda)
            assertEquals(1, falso.recebidas.size)
            val (caminho, corpo) = falso.recebidas.single()
            assertEquals("/bot123:abc/sendMessage", caminho)
            val mensagem = Navegador.JSON.readTree(corpo)
            assertEquals("42", mensagem["chat_id"].asString())
            assertTrue(mensagem["text"].asString().startsWith("Fiel · conciliação de 15/03/2031\n"))
            val gravadas =
                jdbc
                    .sql("select count(*) from conciliacao where dia = '2031-03-15'")
                    .query(Long::class.java)
                    .single()
            assertEquals(1, gravadas)
        }
    }

    @Test
    fun `com o Telegram fora do ar, a mensagem fica pendente e vai depois`() {
        TelegramDeTeste().use { falso ->
            val comTelegram = Conciliacao(jdbc, Telegram("123:abc", "42", falso.url, json), json, relogio, transacoes)
            relogio.parar(Instant.parse("2031-04-20T06:00:00Z"))
            falso.status.set(502)

            comTelegram.conciliar()
            assertEquals(1, falso.recebidas.size)
            falso.status.set(200)
            comTelegram.avisarPendentes()
            comTelegram.avisarPendentes()

            assertEquals(2, falso.recebidas.size)
            val avisada =
                jdbc
                    .sql("select avisada_em is not null from conciliacao where dia = '2031-04-20'")
                    .query(Boolean::class.java)
                    .single()
            assertTrue(avisada)
        }
    }

    @Test
    fun `acha lançamento sem fato, que passa pelos gatilhos`() {
        val ana = pessoa()

        val achados =
            conferirDepoisDe(semGatilhos = false) {
                val emissao = jdbc.sql("select id from conta where tipo = 'emissao'").query(Long::class.java).single()
                val presente = razao.registrar(transferencia(emissao, ana.carteiraId, 50_000))
                val outroBonus = razao.registrar(transferencia(emissao, ana.carteiraId, 100_000))
                check(presente.id != outroBonus.id)
            }

        assertEquals(2, achados.size, achados.toString())
        assertTrue(achados.any { it.endsWith("não é transferência, pagamento, estorno nem bônus") }, achados.toString())
        assertTrue(achados.contains("a carteira ${ana.carteiraId} recebeu 2 bônus de cadastro"), achados.toString())
    }

    @Test
    fun `acha estorno em cobrança que não foi paga`() {
        val (loja, ana) = pessoa() to pessoa()
        val cancelada =
            jdbc
                .sql(
                    """
                    insert into cobranca (codigo, recebedor_conta, valor, descricao, criada_em, expira_em)
                    values (gen_random_uuid(), :c, 500, 'teste', now(), now() + interval '30 minutes') returning id
                    """.trimIndent(),
                ).param("c", loja.carteiraId)
                .query(Long::class.java)
                .single()
        sql("update cobranca set estado = 'cancelada' where id = :id", "id" to cancelada)

        val achados =
            conferirDepoisDe {
                val l = razao.registrar(transferencia(loja.carteiraId, ana.carteiraId, 100))
                sql(
                    """
                    insert into estorno (cobranca_id, chave_idempotencia, resumo_do_pedido, valor, lancamento_id, criado_em)
                    values (:c, 'k', :r, 100, :l, now())
                    """.trimIndent(),
                    "c" to cancelada,
                    "r" to "0".repeat(64),
                    "l" to l.id,
                )
                // Sem os gatilhos o saldo guardado não acompanha: acerta à mão, para sobrar só a divergência do estorno
                sql("update conta set saldo = saldo - 100 where id = :id", "id" to loja.carteiraId)
                sql("update conta set saldo = saldo + 100 where id = :id", "id" to ana.carteiraId)
            }

        assertTrue(
            achados.contains("a cobrança $cancelada tem 100 centavos estornados e um estado que não combina"),
            achados.toString(),
        )
    }

    @Test
    fun `sem Telegram configurado, ou fora do ar, a conciliação segue`() {
        assertFalse(Telegram("", "", "http://127.0.0.1:1", json).enviar("oi"))
        assertFalse(Telegram("123:abc", "42", "http://127.0.0.1:1", json).enviar("oi"))
    }

    @Test
    fun `a mensagem mostra as 10 primeiras divergências e quantas faltam`() {
        val totais =
            listOf(
                "lancamentos",
                "em_carteiras",
                "transferencias",
                "transferido",
                "pagamentos",
                "pago",
                "estornos",
                "estornado",
            ).associateWith { 0L } + ("estornado" to 1_050L)
        val resultado = ResultadoDaConciliacao(LocalDate.of(2026, 10, 17), List(12) { "problema $it" }, totais)

        val texto = Conciliacao.mensagem(resultado)

        assertTrue(texto.contains("ATENÇÃO: 12 divergência(s)"))
        assertTrue(texto.contains("- problema 9\n... e mais 2"))
        assertFalse(texto.contains("problema 10"))
        assertTrue(texto.contains("0 estorno(s) (R$ 10,50)"), texto)
    }
}
