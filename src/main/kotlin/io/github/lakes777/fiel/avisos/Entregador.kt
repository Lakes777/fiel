package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.erros.PedidoInvalido
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Manda os avisos pendentes pela rede. Entrega "pelo menos uma vez": se o lojista recebeu mas a
 * resposta se perdeu, o aviso vai de novo (com o mesmo Fiel-Evento-Id, para ele descartar).
 *
 * Em três passos, para nunca segurar uma transação aberta esperando a rede:
 *  1. reserva um lote: `FOR UPDATE SKIP LOCKED` pula os que outra instância está pegando, e a
 *     próxima tentativa é empurrada para depois de [reserva]. Se esta instância morrer no meio do
 *     envio, o aviso volta sozinho quando a reserva vence;
 *  2. envia cada um, fora de transação, com tempo máximo e sem seguir redirecionamento;
 *  3. grava o resultado: entregue (2xx), ou nova tentativa com espera crescente, ou, depois de
 *     [MAX_TENTATIVAS], a fila de falhas.
 */
@Component
class Entregador(
    private val jdbc: JdbcClient,
    private val destino: Destino,
    private val relogio: Clock,
    @Value("\${fiel.avisos.prazo-segundos:15}") prazoSegundos: Long,
) {
    /** Tempo máximo de um envio inteiro: conectar, mandar, receber cabeçalhos E corpo da resposta. */
    private val prazo: Duration = Duration.ofSeconds(prazoSegundos)

    /**
     * Quanto a reserva segura cada aviso: o pior caso do lote inteiro (todos esgotando o prazo,
     * um depois do outro) e mais uma folga. Menor que isso, outra instância pegaria os últimos do
     * lote enquanto esta ainda nem chegou neles, e mandaria em dobro.
     */
    private val reserva: Duration = prazo.multipliedBy(LOTE.toLong()).plus(FOLGA_DA_RESERVA)

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(SEGUNDOS_PARA_CONECTAR))
            // Um 3xx seria um jeito de levar o pedido a um endereço que o Destino não conferiu
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private data class Reservado(
        val id: Long,
        val codigo: UUID,
        val tipo: String,
        val dados: String,
        val criadoEm: Instant,
        val tentativas: Int,
        val url: String,
        val segredo: String,
    )

    /** Uma rodada: reserva até [tamanho] avisos e manda. Devolve quantos tentou. */
    fun entregarLote(tamanho: Int = LOTE): Int {
        val lote = reservar(tamanho)
        lote.forEach(::entregar)
        return lote.size
    }

    private fun reservar(tamanho: Int): List<Reservado> {
        val agora = agora()
        // Um pendente sem endereço (o lojista apagou o webhook enquanto um pagamento gravava o
        // aviso) não tem para onde ir: vai para a fila de falhas, em vez de ficar na fila para sempre
        jdbc
            .sql(
                """
                update aviso a set estado = 'falhou', proxima_em = null, ultima_resposta = 'sem endereço de aviso'
                where estado = 'pendente' and proxima_em <= :agora
                  and not exists (select 1 from webhook w where w.usuario_id = a.usuario_id)
                """.trimIndent(),
            ).param("agora", utc(agora))
            .update()
        // Um comando só: o UPDATE com a subconsulta travada é atômico, sem precisar de transação aqui.
        // O join fica DENTRO da subconsulta: só entra no lote (e no LIMIT) quem tem para onde ir
        return jdbc
            .sql(
                """
                update aviso a set proxima_em = :reserva
                from webhook w
                where w.usuario_id = a.usuario_id
                  and a.id in (
                      select p.id from aviso p join webhook pw on pw.usuario_id = p.usuario_id
                      where p.estado = 'pendente' and p.proxima_em <= :agora
                      order by p.proxima_em
                      limit :tamanho
                      for update of p skip locked
                  )
                returning a.id, a.codigo, a.tipo, a.dados::text as dados, a.criado_em, a.tentativas, w.url, w.segredo
                """.trimIndent(),
            ).param("reserva", utc(agora.plus(reserva)))
            .param("agora", utc(agora))
            .param("tamanho", tamanho)
            .query { rs, _ ->
                Reservado(
                    rs.getLong("id"),
                    rs.getObject("codigo", UUID::class.java),
                    rs.getString("tipo"),
                    rs.getString("dados"),
                    rs.getObject("criado_em", OffsetDateTime::class.java).toInstant(),
                    rs.getInt("tentativas"),
                    rs.getString("url"),
                    rs.getString("segredo"),
                )
            }.list()
            // O RETURNING não sai na ordem da subconsulta: os mais antigos primeiro, aqui
            .sortedWith(compareBy(Reservado::criadoEm, Reservado::id))
    }

    // Cada exceção vira o texto da tentativa que falhou (fica no histórico do aviso): é de propósito
    // que ela não sobe, senão o aviso reservado não passaria pelo gravar
    @Suppress("SwallowedException")
    private fun entregar(aviso: Reservado) {
        // Montado à mão para o corpo assinado ser exatamente o enviado; dados já é JSON válido (jsonb)
        val corpo =
            """{"id":"${aviso.codigo}","tipo":"${aviso.tipo}","criadoEm":"${aviso.criadoEm}","dados":${aviso.dados}}"""
        val resposta =
            try {
                val uri = destino.conferir(aviso.url)
                val pedido =
                    HttpRequest
                        .newBuilder(uri)
                        .timeout(prazo)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Fiel-Avisos/1")
                        .header("Fiel-Evento", aviso.tipo)
                        .header("Fiel-Evento-Id", aviso.codigo.toString())
                        .header("Fiel-Assinatura", Assinatura.assinar(aviso.segredo, agora().epochSecond, corpo))
                        .POST(HttpRequest.BodyPublishers.ofString(corpo))
                        .build()
                // O timeout do pedido só vale até os cabeçalhos: um lojista que mandasse o corpo 1 byte
                // por vez prenderia a thread de entrega (e a de expiração) para sempre. O prazo vale
                // para o envio inteiro, e o corpo é descartado sem guardar (nem memória, nem rede interna
                // lida por tabela, se um dia algo passar pelo Destino): fica só o status
                val envio = http.sendAsync(pedido, HttpResponse.BodyHandlers.discarding())
                val status =
                    try {
                        envio.get(prazo.toMillis(), TimeUnit.MILLISECONDS).statusCode()
                    } catch (erro: TimeoutException) {
                        envio.cancel(true)
                        throw erro
                    }
                Resultado(status in SUCESSO, "HTTP $status")
            } catch (erro: InterruptedException) {
                Thread.currentThread().interrupt()
                Resultado(false, "envio interrompido")
            } catch (erro: TimeoutException) {
                Resultado(false, "sem resposta em ${prazo.toSeconds()} s")
            } catch (erro: ExecutionException) {
                Resultado(false, "falha de rede: ${(erro.cause ?: erro).javaClass.simpleName}")
            } catch (erro: PedidoInvalido) {
                Resultado(false, "endereço recusado: ${erro.message}")
            } catch (
                @Suppress("TooGenericExceptionCaught") erro: RuntimeException,
            ) {
                // Qualquer outra coisa (uma URL que o HttpClient recusa, por exemplo) também é uma
                // tentativa que falhou: todo aviso reservado precisa passar pelo gravar, senão fica
                // pendente para sempre e atrasa o lote a cada rodada
                Resultado(false, "erro ao enviar: ${erro.javaClass.simpleName}")
            }
        gravar(aviso, resposta)
    }

    private data class Resultado(
        val entregue: Boolean,
        val resposta: String,
    )

    private fun gravar(
        aviso: Reservado,
        resultado: Resultado,
    ) {
        val agora = agora()
        val tentativas = aviso.tentativas + 1
        val (estado, proxima) =
            when {
                resultado.entregue -> "entregue" to null
                tentativas >= MAX_TENTATIVAS -> "falhou" to null
                else -> "pendente" to agora.plus(espera(tentativas))
            }
        if (estado == "falhou") log.warn("aviso {} foi para a fila de falhas: {}", aviso.id, resultado.resposta)
        // estado = 'pendente': se o lojista apagou o endereço no meio do envio, o aviso já está em falhou.
        // tentativas = :antes: um resultado atrasado não passa por cima de um mais novo (reenvio, outra instância)
        jdbc
            .sql(
                """
                update aviso set estado = :estado, tentativas = :tentativas, proxima_em = :proxima,
                       ultima_resposta = :resposta, entregue_em = :entregue
                where id = :id and estado = 'pendente' and tentativas = :antes
                """.trimIndent(),
            ).param("antes", aviso.tentativas)
            .param("estado", estado)
            .param("tentativas", tentativas)
            .param("proxima", proxima?.let(::utc))
            .param("resposta", resultado.resposta.take(MAX_RESPOSTA))
            .param("entregue", if (resultado.entregue) utc(agora) else null)
            .param("id", aviso.id)
            .update()
    }

    private fun agora() = relogio.instant().truncatedTo(ChronoUnit.MICROS)

    private fun utc(instante: Instant) = OffsetDateTime.ofInstant(instante, ZoneOffset.UTC)

    companion object {
        const val MAX_TENTATIVAS = 8
        const val LOTE = 10
        private val FOLGA_DA_RESERVA: Duration = Duration.ofMinutes(1)
        private val PRIMEIRA_ESPERA: Duration = Duration.ofSeconds(30)
        private val MAIOR_ESPERA: Duration = Duration.ofHours(1)
        private const val SEGUNDOS_PARA_CONECTAR = 5L
        private const val MAX_RESPOSTA = 300
        private const val FOLGA_MAXIMA = 0.2
        private val SUCESSO = 200..299

        /**
         * Espera antes da tentativa seguinte à [tentativas]-ésima: 30 s, 1 min, 2 min, 4 min... até 1 h,
         * mais até 20% ao acaso. Sem o acaso, mil avisos que falharam juntos (o lojista caiu)
         * voltariam todos no mesmo segundo quando ele subisse.
         */
        fun espera(
            tentativas: Int,
            sorteio: Double = Random.nextDouble(),
        ): Duration {
            val base = PRIMEIRA_ESPERA.multipliedBy(1L shl (tentativas - 1).coerceIn(0, 20))
            val limitada = if (base > MAIOR_ESPERA) MAIOR_ESPERA else base
            return limitada.plusMillis((limitada.toMillis() * FOLGA_MAXIMA * sorteio).toLong())
        }
    }
}
