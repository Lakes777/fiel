package io.github.lakes777.fiel

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * O Postgres dos testes, com os dois usuários de produção: as migrações rodam com o dono (o usuário
 * do contêiner) e a API com o [PAPEL_DA_API], sem poder de dono (ver V8). Assim todos os testes
 * provam que a API funciona sem esse poder.
 *
 * A conexão da API entra como dono e logo assume o papel restrito (`set role`): os poucos testes que
 * precisam estragar o banco de propósito voltam a ser dono só dentro da transação deles
 * (`set local role`, ver [COMO_DONO]). Em produção a API entra direto com o usuário restrito, que não
 * tem para onde voltar; o PapelDaApiTest confere esse login.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withInitScript("papel-da-api.sql")

    @Bean
    fun usuariosDoBanco(banco: PostgreSQLContainer) =
        DynamicPropertyRegistrar { registro ->
            // Com usuário próprio, o Flyway abre a conexão dele, sem o "set role" da API
            registro.add("spring.flyway.url", banco::getJdbcUrl)
            registro.add("spring.flyway.user", banco::getUsername)
            registro.add("spring.flyway.password", banco::getPassword)
            registro.add("spring.flyway.placeholders.papel_api") { PAPEL_DA_API }
            registro.add("spring.datasource.hikari.connection-init-sql") { "set role $PAPEL_DA_API" }
        }

    companion object {
        const val PAPEL_DA_API = "fiel_api"
        const val SENHA_DO_PAPEL_DA_API = "fiel_api"

        /** O dono das tabelas nos testes: o usuário padrão do contêiner do Testcontainers. */
        const val DONO = "test"

        /**
         * SQL que, dentro de uma transação, volta a ser o dono até o fim dela. Só para teste que
         * estraga o banco de propósito ou confere um gatilho que o usuário da API nem alcança.
         */
        const val COMO_DONO = "set local role $DONO"
    }
}
