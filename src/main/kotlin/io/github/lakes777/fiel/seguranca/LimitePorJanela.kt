package io.github.lakes777.fiel.seguranca

import io.github.lakes777.fiel.erros.MuitasTentativas
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Até [maximo] eventos por [janela] para cada chave (um e-mail, um usuário), em janela fixa: a
 * contagem começa no primeiro evento e zera quando a janela passa.
 *
 * Fica na memória da API, o que basta para uma instância só; com várias, a contagem iria para o
 * Postgres ou um Redis, senão cada instância daria o limite inteiro.
 */
class LimitePorJanela(
    private val maximo: Int,
    private val janela: Duration,
    private val relogio: Clock = Clock.systemUTC(),
) {
    private data class Contagem(
        val inicio: Instant,
        val quantos: Int,
    )

    private val contagens = ConcurrentHashMap<String, Contagem>()

    @Volatile
    private var ultimaLimpeza: Instant = Instant.EPOCH

    /** Conta um evento; lança [MuitasTentativas] se ele passou do limite. */
    fun registrar(chave: String) {
        val agora = relogio.instant()
        limparVencidas(agora)
        val atual =
            contagens.compute(chave) { _, antes ->
                antes?.takeUnless { venceu(it, agora) }?.let { it.copy(quantos = it.quantos + 1) } ?: Contagem(agora, 1)
            }
        if (atual != null && atual.quantos > maximo) throw MuitasTentativas(segundosAteOFim(atual, agora))
    }

    /** Quantas chaves estão sendo contadas agora (para os testes conferirem a limpeza). */
    val tamanho get() = contagens.size

    fun esquecer(chave: String) {
        contagens.remove(chave)
    }

    /**
     * No máximo uma limpeza por janela, e não a cada pedido: com muitas chaves ainda dentro da
     * janela (alguém testando milhares de e-mails), varrer o mapa todo a cada pedido pesaria.
     * O que segura o tamanho do mapa é o custo de cada evento (no login, um bcrypt de ~100 ms).
     */
    private fun limparVencidas(agora: Instant) {
        if (Duration.between(ultimaLimpeza, agora) < janela) return
        ultimaLimpeza = agora
        contagens.entries.removeIf { venceu(it.value, agora) }
    }

    private fun venceu(
        contagem: Contagem,
        agora: Instant,
    ) = !agora.isBefore(contagem.inicio.plus(janela))

    // Arredonda para cima: "tente em 0 s" faria o cliente tentar cedo demais
    private fun segundosAteOFim(
        contagem: Contagem,
        agora: Instant,
    ): Long {
        val falta = Duration.between(agora, contagem.inicio.plus(janela))
        return falta.toSeconds() + if (falta.toNanosPart() > 0) 1 else 0
    }
}

/** Os limites da API. Um ataque de força bruta a uma senha ou a varredura das chaves para quando eles acabam. */
@Component
class LimitesDeUso(
    @Value("\${fiel.limite.cadastros-por-hora:10}") cadastrosPorHora: Int,
) {
    /**
     * Tentativas de login por e-mail, contadas ANTES de conferir a senha: 200 pedidos ao mesmo tempo
     * ainda testam só 5 senhas. Bloquear o e-mail (e não o IP) protege a senha mesmo de quem troca
     * de IP; o preço é que alguém pode travar o login de outra pessoa por 15 min errando de propósito.
     */
    val login = LimitePorJanela(maximo = 5, janela = Duration.ofMinutes(15))

    /**
     * Cadastros por IP. Sem ele, criar contas seria de graça: cada uma ganha o próprio limite de
     * consultas (e, na fase 4, o próprio bônus para juntar), e o 409 do cadastro diria quem tem conta.
     */
    val cadastro = LimitePorJanela(maximo = cadastrosPorHora, janela = Duration.ofHours(1))

    /** Consultas de chave por usuário: impede varrer chaves para descobrir quem tem conta. */
    val consultaDeChave = LimitePorJanela(maximo = 30, janela = Duration.ofMinutes(1))
}
