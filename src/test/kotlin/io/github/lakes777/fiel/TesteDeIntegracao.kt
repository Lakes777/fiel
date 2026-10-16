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
 *
 * A API escuta numa porta de verdade, para o [Navegador] testar cookies e CSRF como um navegador.
 * O cookie de sessão perde o "Secure" só aqui: o cliente HTTP do Java não devolve cookie seguro por http.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Todos os testes cadastram do mesmo IP (127.0.0.1): o limite de cadastros por IP fica alto aqui
    properties = ["server.servlet.session.cookie.secure=false", "fiel.limite.cadastros-por-hora=1000000"],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, ContadorDeSenhas::class)
annotation class TesteDeIntegracao
