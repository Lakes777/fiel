package io.github.lakes777.fiel

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Conta quantas vezes cada senha foi conferida pelo bcrypt. A resposta HTTP não basta para provar
 * um limite de tentativas: o que importa num ataque é quantas senhas chegaram a ser testadas.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContadorDeSenhas {
    private class Contando(
        private val real: PasswordEncoder,
    ) : PasswordEncoder by real {
        override fun matches(
            rawPassword: CharSequence?,
            encodedPassword: String?,
        ): Boolean {
            conferidas.computeIfAbsent(rawPassword.toString()) { AtomicInteger() }.incrementAndGet()
            return real.matches(rawPassword, encodedPassword)
        }
    }

    companion object {
        private val conferidas = ConcurrentHashMap<String, AtomicInteger>()

        fun vezesConferida(senha: String) = conferidas[senha]?.get() ?: 0

        @Bean
        @JvmStatic
        fun contarSenhasConferidas() =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any = if (bean is PasswordEncoder) Contando(bean) else bean
            }
    }
}
