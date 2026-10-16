package io.github.lakes777.fiel.seguranca

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRepository

/**
 * Quem entra e como. Login por sessão: um cookie HttpOnly (o JavaScript não lê) com o id de uma
 * sessão guardada no Postgres (Spring Session JDBC), que dá para encerrar na hora. Como o navegador
 * manda o cookie sozinho, todo pedido que muda algo precisa também do token CSRF, que um site de
 * fora não consegue ler.
 */
@Configuration
class SegurancaConfig {
    /**
     * bcrypt, com o nome do algoritmo no hash ({bcrypt}...): dá para trocar de algoritmo depois sem
     * perder as senhas.
     */
    @Bean
    fun senhas(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun contextoNaSessao(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    /** O token CSRF vive no cookie XSRF-TOKEN, que o front-end lê para mandar de volta no cabeçalho. */
    @Bean
    fun tokensCsrf(): CsrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()

    /**
     * Confere e-mail e senha. Para e-mail sem cadastro ele também calcula um bcrypt, então a resposta
     * demora o mesmo: o tempo não denuncia quem tem conta.
     */
    @Bean
    fun autenticacao(
        usuarios: UserDetailsService,
        senhas: PasswordEncoder,
    ): AuthenticationManager = ProviderManager(DaoAuthenticationProvider(usuarios).apply { setPasswordEncoder(senhas) })

    @Bean
    fun cadeia(
        http: HttpSecurity,
        contexto: SecurityContextRepository,
        tokensCsrf: CsrfTokenRepository,
    ): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.GET, "/saude", "/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/cadastro", "/sessao")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }
            // Token no cookie XSRF-TOKEN; o cliente devolve no cabeçalho X-XSRF-TOKEN. Vale também
            // para o login e o cadastro (sem ele, um site de fora poderia logar você na conta dele)
            .csrf { it.spa().csrfTokenRepository(tokensCsrf) }
            .securityContext { it.securityContextRepository(contexto) }
            .exceptionHandling {
                it
                    .authenticationEntryPoint { _, resposta, _ ->
                        problema(resposta, HttpStatus.UNAUTHORIZED, "faça login para continuar")
                    }.accessDeniedHandler { _: HttpServletRequest, resposta, _ ->
                        problema(resposta, HttpStatus.FORBIDDEN, "pedido recusado: falta o token CSRF ou ele não vale")
                    }
            }.formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .build()

    // Os filtros de segurança respondem antes do Spring MVC, então escrevem o problem+json à mão
    private fun problema(
        resposta: HttpServletResponse,
        status: HttpStatus,
        detalhe: String,
    ) {
        resposta.status = status.value()
        resposta.contentType = "application/problem+json"
        resposta.characterEncoding = Charsets.UTF_8.name()
        val titulo = status.reasonPhrase
        resposta.writer.write(
            """{"type":"about:blank","title":"$titulo","status":${status.value()},"detail":"$detalhe"}""",
        )
    }
}
