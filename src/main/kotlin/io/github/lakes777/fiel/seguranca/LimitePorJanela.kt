package io.github.lakes777.fiel.seguranca

import io.github.lakes777.fiel.erros.MuitasTentativas
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.Inet6Address
import java.net.InetAddress
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

    /** Se a [chave] está sendo contada (para os testes conferirem qual IP a API enxergou). */
    fun contando(chave: String) = contagens.containsKey(chave)

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
    @Value("\${fiel.limite.transferencias-por-minuto:30}") transferenciasPorMinuto: Int,
    @Value("\${fiel.limite.cobrancas-por-minuto:30}") cobrancasPorMinuto: Int,
    @Value("\${fiel.limite.pagamentos-por-minuto:30}") pagamentosPorMinuto: Int,
    @Value("\${fiel.limite.estornos-por-minuto:30}") estornosPorMinuto: Int,
    @Value("\${fiel.limite.visitantes-por-hora:300}") visitantesPorHora: Int,
) {
    /**
     * Tentativas de login por e-mail, contadas ANTES de conferir a senha: 200 pedidos ao mesmo tempo
     * ainda testam só 5 senhas. Bloquear o e-mail (e não o IP) protege a senha mesmo de quem troca
     * de IP; o preço é que alguém pode travar o login de outra pessoa por 15 min errando de propósito.
     */
    val login = LimitePorJanela(maximo = 5, janela = Duration.ofMinutes(15))

    /**
     * Cadastros por IP. Sem ele, criar contas seria de graça: cada uma ganha o próprio limite de
     * consultas (e o próprio bônus para juntar: risco aceito, ver README), e o 409 do cadastro diria quem tem conta.
     */
    val cadastro = LimitePorJanela(maximo = cadastrosPorHora, janela = Duration.ofHours(1))

    /** Consultas de chave por usuário: impede varrer chaves para descobrir quem tem conta. */
    val consultaDeChave = LimitePorJanela(maximo = 30, janela = Duration.ofMinutes(1))

    /**
     * Pedidos de transferência por usuário. Não é o limite de dinheiro (esse é Limites, por valor):
     * é o que impede usar transferências de 1 centavo para varrer chaves.
     */
    val transferencia = LimitePorJanela(maximo = transferenciasPorMinuto, janela = Duration.ofMinutes(1))

    /** Cobranças criadas por usuário: cada uma é uma linha no banco, de graça para quem cria. */
    val cobranca = LimitePorJanela(maximo = cobrancasPorMinuto, janela = Duration.ofMinutes(1))

    /** Consultas e pagamentos de QR por usuário. */
    val pagamento = LimitePorJanela(maximo = pagamentosPorMinuto, janela = Duration.ofMinutes(1))

    /** Pedidos de estorno por usuário: cada um trava a cobrança e as duas carteiras. */
    val estorno = LimitePorJanela(maximo = estornosPorMinuto, janela = Duration.ofMinutes(1))

    /**
     * Visitantes da demonstração criados por hora, somando todo mundo (uma chave só). O limite por
     * IP para um curioso; este, quem tem muitos IPs: cada visitante custa dois bcrypt e uma dúzia de
     * linhas num Postgres pequeno e compartilhado.
     */
    val visitantes = LimitePorJanela(maximo = visitantesPorHora, janela = Duration.ofHours(1))
}

/**
 * A chave de um IP nos limites por IP. IPv6 conta pelo prefixo /64: uma casa recebe um /64 inteiro
 * (bilhões de endereços), e contar cada um à parte deixaria qualquer um escapar do limite.
 */
fun chaveDoIp(ip: String): String {
    // Só texto com cara de IP literal: assim o getByName nunca vira uma consulta de DNS
    val endereco = if (IP_LITERAL.matches(ip)) runCatching { InetAddress.getByName(ip) }.getOrNull() else null
    return when (endereco) {
        null -> ip
        is Inet6Address -> prefixo64(endereco.address)
        else -> endereco.hostAddress
    }
}

/** Os primeiros 8 bytes do IPv6, em 4 grupos "xxxx": "2001:db8:1:2::/64". */
private fun prefixo64(bytes: ByteArray): String =
    bytes
        .take(BYTES_DO_PREFIXO)
        .chunked(2) { (alto, baixo) ->
            "%x".format(
                (alto.toInt() and UM_BYTE) shl BITS_DE_UM_BYTE or (baixo.toInt() and UM_BYTE),
            )
        }.joinToString(":", postfix = "::/64")

private const val BYTES_DO_PREFIXO = 8
private const val UM_BYTE = 0xff
private const val BITS_DE_UM_BYTE = 8

// IPv4 com quatro números, ou IPv6 (tem ":", que o Java nunca trata como nome a resolver)
private val IP_LITERAL = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]*)$")
