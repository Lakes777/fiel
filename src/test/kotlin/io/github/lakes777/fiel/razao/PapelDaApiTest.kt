package io.github.lakes777.fiel.razao

import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.DONO
import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.PAPEL_DA_API
import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.SENHA_DO_PAPEL_DA_API
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.usuarios.Cadastro
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * O usuário da API em produção (V8) entra com o próprio login, sem o "set role" dos outros testes:
 * tudo o que só o dono pode fazer, e que desligaria as regras do razão, tem que ser recusado.
 */
@TesteDeIntegracao
class PapelDaApiTest {
    @Autowired
    private lateinit var banco: PostgreSQLContainer

    @Autowired
    private lateinit var cadastro: Cadastro

    @Autowired
    private lateinit var jdbc: JdbcClient

    private fun <T> comoApi(bloco: (Connection) -> T): T =
        DriverManager.getConnection(banco.jdbcUrl, PAPEL_DA_API, SENHA_DO_PAPEL_DA_API).use(bloco)

    private fun Connection.executar(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.valor(sql: String): Any? =
        createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getObject(1)
            }
        }

    private fun erro(sql: String): String = comoApi { c -> assertFailsWith<SQLException> { c.executar(sql) }.sqlState }

    @Test
    fun `a API entra sem ser dona de nada nem superusuário`() {
        comoApi { c ->
            assertEquals(PAPEL_DA_API, c.valor("select current_user"))
            assertEquals(false, c.valor("select rolsuper from pg_roles where rolname = current_user"))
            val donaDe = "select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user"
            assertEquals(0L, c.valor(donaDe))
            assertFalse(c.valor("select pg_has_role(current_user, '$DONO', 'member')") as Boolean)
        }
    }

    @Test
    fun `a conexão da API nos outros testes também roda como ela`() {
        assertEquals(PAPEL_DA_API, jdbc.sql("select current_user").query(String::class.java).single())
    }

    @Test
    fun `nada do que desligaria as regras do razão passa`() {
        val tentativas =
            listOf(
                "alter table partida disable trigger partida_imutavel",
                "alter table conta drop constraint carteira_nunca_negativa",
                "drop table partida",
                "truncate partida",
                "create or replace function razao_imutavel() returns trigger language plpgsql " +
                    "as 'begin return new; end'",
                "create table qualquer (id int)",
                // A tabela temporária com nome de tabela real enganava os gatilhos (ver V8)
                "create temp table partida (lancamento_id bigint, conta_id bigint, valor bigint)",
                "set session_replication_role = replica",
                "set role $DONO",
                "delete from flyway_schema_history",
            )
        // 42501: permissão negada (ou "precisa ser dono" / "precisa ser superusuário", que o Postgres
        // também devolve como 42501)
        for (sql in tentativas) assertEquals("42501", erro(sql), sql)
    }

    @Test
    fun `toda função do esquema procura primeiro no public`() {
        // Sem isto, uma tabela de mesmo nome num esquema que vem antes na busca (o pg_temp) seria a lida.
        // Vale para as funções de migrações futuras também: cada uma precisa do "set search_path".
        val semCaminhoFixo =
            jdbc
                .sql(
                    """
                    select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = 'public'
                      and not coalesce('search_path=public, pg_temp' = any(p.proconfig), false)
                    """.trimIndent(),
                ).query(String::class.java)
                .list()
        assertEquals(emptyList(), semCaminhoFixo)
    }

    @Test
    fun `os gatilhos continuam valendo para ela`() {
        // O cadastro abre a carteira e lança os R$ 1.000 de boas-vindas: há conta e lançamento para atacar
        val carteira =
            cadastro.cadastrar("Ana Souza", "${UUID.randomUUID()}@teste.fiel", "senha-de-teste-123").carteiraId
        val lancamento = comoApi { c -> c.valor("select max(lancamento_id) from partida where conta_id = $carteira") }
        // Ela pode dar UPDATE em conta (o saldo muda por gatilho), mas mudar o saldo à mão segue barrado
        assertEquals("23001", erro("update conta set saldo = saldo + 1 where id = $carteira"))
        assertEquals("23001", erro("delete from lancamento where id = $lancamento"))
    }
}
