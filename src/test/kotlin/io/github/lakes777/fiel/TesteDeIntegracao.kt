package io.github.lakes777.fiel

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * API inteira com um Postgres do Testcontainers. Todas as classes de teste usam a mesma combinação,
 * então o Spring reaproveita o contexto e o contêiner sobe uma vez só.
 *
 * O razão não deixa apagar nada, então os testes não limpam o banco: cada um abre as próprias contas
 * e só olha para elas.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
annotation class TesteDeIntegracao
