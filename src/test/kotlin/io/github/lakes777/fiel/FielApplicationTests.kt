package io.github.lakes777.fiel

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FielApplicationTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `saude responde ok com o banco de pé e não deixa guardar em cache`() {
        mvc
            .perform(get("/saude"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.banco").value("ok"))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `rota que não existe devolve 404 no formato padrão de problema`() {
        mvc
            .perform(get("/nao-existe"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
    }

    @Test
    fun `os testes rodam no mesmo Postgres da produção`() {
        // Produção é o Postgres 17 da VM ARM; testar em outra versão esconderia diferenças
        val versao = jdbc.sql("show server_version_num").query(String::class.java).single()
        assertEquals("17", versao.take(2))
    }
}
