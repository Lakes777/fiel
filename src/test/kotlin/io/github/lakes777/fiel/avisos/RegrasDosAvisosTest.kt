package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.TestcontainersConfiguration.Companion.COMO_DONO
import io.github.lakes777.fiel.TesteDeIntegracao
import io.github.lakes777.fiel.usuarios.Cadastro
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Avisos atacados direto com SQL: o que o aviso diz não muda, e ele não some. */
@TesteDeIntegracao
class RegrasDosAvisosTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transacao: TransactionTemplate

    @Autowired
    private lateinit var cadastro: Cadastro

    private fun erroDoBanco(bloco: () -> Unit): SQLException {
        val erro = assertFailsWith<DataAccessException> { transacao.executeWithoutResult { bloco() } }
        return assertNotNull(
            generateSequence<Throwable>(erro) { it.cause }.filterIsInstance<SQLException>().lastOrNull(),
        )
    }

    private fun sql(
        comando: String,
        vararg parametros: Pair<String, Any>,
    ) = parametros.fold(jdbc.sql(comando)) { s, (nome, valor) -> s.param(nome, valor) }.update()

    private fun aviso(): Long {
        val usuario = cadastro.cadastrar("Bob Lima", "${UUID.randomUUID()}@teste.fiel", "senha-de-teste-123").id
        return jdbc
            .sql(
                """
                insert into aviso (codigo, usuario_id, tipo, dados, criado_em, proxima_em)
                values (gen_random_uuid(), :u, 'cobranca.paga', '{"valorCentavos": 100}', now(), now())
                returning id
                """.trimIndent(),
            ).param("u", usuario)
            .query(Long::class.java)
            .single()
    }

    @Test
    fun `o que o aviso diz não muda, e aviso não é apagado`() {
        val id = aviso()
        val tentativas =
            listOf(
                "update aviso set dados = '{\"valorCentavos\": 999999}' where id = :id",
                "update aviso set tipo = 'transferencia.recebida' where id = :id",
                "update aviso set codigo = gen_random_uuid() where id = :id",
                "delete from aviso where id = :id",
            )
        for (comando in tentativas) assertEquals("23001", erroDoBanco { sql(comando, "id" to id) }.sqlState, comando)
        // TRUNCATE só o dono alcança (a API nem tem a permissão, ver PapelDaApiTest): o gatilho vale para ele também
        assertEquals(
            "23001",
            erroDoBanco {
                sql(COMO_DONO)
                sql("truncate aviso")
            }.sqlState,
        )
        // O estado da entrega muda (é para isso que ele existe)
        sql("update aviso set estado = 'entregue', proxima_em = null, entregue_em = now() where id = :id", "id" to id)
    }

    @Test
    fun `pendente tem hora da próxima tentativa, entregue tem hora da entrega`() {
        val id = aviso()

        val pendenteSemHora = erroDoBanco { sql("update aviso set proxima_em = null where id = :id", "id" to id) }
        val entregueSemHora =
            erroDoBanco { sql("update aviso set estado = 'entregue', proxima_em = null where id = :id", "id" to id) }
        val tipoInventado =
            erroDoBanco {
                sql(
                    """
                    insert into aviso (codigo, usuario_id, tipo, dados, criado_em, proxima_em)
                    select gen_random_uuid(), usuario_id, 'saque.feito', '{}', now(), now() from aviso where id = :id
                    """.trimIndent(),
                    "id" to id,
                )
            }

        assertEquals("23514", pendenteSemHora.sqlState)
        assertEquals("23514", entregueSemHora.sqlState)
        assertEquals("23514", tipoInventado.sqlState)
    }
}
