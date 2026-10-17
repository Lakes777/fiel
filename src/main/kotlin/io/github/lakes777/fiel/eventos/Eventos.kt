package io.github.lakes777.fiel.eventos

import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * O que a tela precisa saber para buscar de novo: "carteira" (saldo e extrato), "cobranca" (uma
 * cobrança do lojista mudou) ou "avisos" (uma entrega de webhook terminou). O evento não leva
 * dados: a tela busca pelas rotas de sempre, que já conferem quem pode ver o quê.
 */
data class Evento(
    val usuario: Long,
    val tipo: String,
    val cobranca: Long? = null,
)

/**
 * Publica eventos com o NOTIFY do Postgres, DENTRO da transação do fato. O Postgres só entrega o
 * NOTIFY no COMMIT: o pagamento desfeito (saldo, limite) não avisa ninguém, e o aviso nunca chega
 * antes de o dado estar gravado. Toda instância da API escuta o canal ([Ouvinte]), então o evento
 * chega a quem estiver conectado em qualquer uma.
 */
@Component
class Eventos(
    private val jdbc: JdbcClient,
) {
    /** Para o dono da carteira [contaId]. */
    fun publicar(
        contaId: Long,
        tipo: String,
        cobrancaId: Long? = null,
    ) {
        require(tipo in TIPOS) { "tipo de evento desconhecido: $tipo" }
        jdbc
            .sql(
                """
                select pg_notify('$CANAL', json_build_object('usuario', dono_id, 'tipo', cast(:tipo as text), 'cobranca', cast(:cobranca as bigint))::text)
                from conta where id = :conta and dono_id is not null
                """.trimIndent(),
            ).param("tipo", tipo)
            .param("cobranca", cobrancaId)
            .param("conta", contaId)
            .query { _, _ -> }
            .list()
    }

    /** Para o usuário [usuarioId] (os avisos de webhook são por usuário, não por conta). */
    fun publicarParaUsuario(
        usuarioId: Long,
        tipo: String,
    ) {
        require(tipo in TIPOS) { "tipo de evento desconhecido: $tipo" }
        jdbc
            .sql(
                """
                select pg_notify('$CANAL', json_build_object('usuario', cast(:usuario as bigint), 'tipo', cast(:tipo as text))::text)
                """.trimIndent(),
            ).param("usuario", usuarioId)
            .param("tipo", tipo)
            .query { _, _ -> }
            .list()
    }

    companion object {
        const val CANAL = "fiel_eventos"
        val TIPOS = setOf("carteira", "cobranca", "avisos")
    }
}

/**
 * As conexões abertas em GET /eventos nesta instância, por usuário. Cada pessoa tem no máximo
 * [MAX_POR_USUARIO] (abas e aparelhos); a mais antiga fecha quando passa disso, para ninguém
 * segurar memória abrindo conexões sem parar.
 *
 * Cada envio roda numa thread virtual própria: um celular que não lê (rede ruim, TCP cheio) trava
 * só o envio dele, não o de todo mundo. Os eventos só dizem "busque de novo", então a ordem entre
 * dois deles não importa.
 */
@Component
class Canais(
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val abertos = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()
    private val envios = Executors.newVirtualThreadPerTaskExecutor()
    private val batimento =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "fiel-eventos-batimento").apply { isDaemon = true } }

    init {
        // Um comentário a cada 25 s: proxies fecham conexão parada, e assim uma conexão morta é
        // descoberta. O try segura qualquer erro: uma exceção que escapasse pararia o batimento de vez
        batimento.scheduleAtFixedRate(
            {
                try {
                    abertos.forEach { (usuario, lista) -> lista.forEach { enviar(usuario, it, ping()) } }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log.warn("batimento dos eventos falhou: {}", e.javaClass.simpleName)
                }
            },
            BATIMENTO_SEGUNDOS,
            BATIMENTO_SEGUNDOS,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Abre uma conexão que dura [duracao]: o que falta para a sessão de login vencer por falta de
     * uso ([InatividadeDaSessao]). Depois o navegador reconecta sozinho, e a reconexão só passa
     * se a pessoa usou o sistema nesse meio-tempo.
     */
    fun abrir(
        usuarioId: Long,
        duracao: Duration,
    ): SseEmitter {
        val emissor = SseEmitter(duracao.toMillis().coerceAtLeast(1))
        // compute: adicionar e tirar a lista vazia do mapa são atômicos, sem deixar emissor numa lista órfã
        val excedentes = mutableListOf<SseEmitter>()
        abertos.compute(usuarioId) { _, lista ->
            (lista ?: CopyOnWriteArrayList()).apply {
                add(emissor)
                while (size > MAX_POR_USUARIO) excedentes += removeFirst()
            }
        }
        excedentes.forEach { it.complete() }
        val fechar = Runnable { remover(usuarioId, emissor) }
        emissor.onCompletion(fechar)
        // Completar no tempo limite evita o AsyncRequestTimeoutException no log a cada conexão vencida
        emissor.onTimeout {
            emissor.complete()
            fechar.run()
        }
        emissor.onError { fechar.run() }
        // A tela busca tudo de novo ao receber "pronto": o que mudou antes de a conexão abrir não se perde
        enviar(usuarioId, emissor, SseEmitter.event().name("pronto").data("{}"))
        return emissor
    }

    fun entregar(evento: Evento) {
        val dados = json.writeValueAsString(mapOf("cobranca" to evento.cobranca).filterValues { it != null })
        abertos[evento.usuario]?.forEach {
            enviar(
                evento.usuario,
                it,
                SseEmitter.event().name(evento.tipo).data(dados),
            )
        }
    }

    /** "pronto" para todas as telas abertas: o ouvinte esteve fora do ar e algum evento pode ter se perdido. */
    fun avisarTodos() {
        abertos.forEach { (usuario, lista) ->
            lista.forEach { enviar(usuario, it, SseEmitter.event().name("pronto").data("{}")) }
        }
    }

    /** Fecha as conexões de um usuário nesta instância (ao sair). */
    fun fecharDe(usuarioId: Long) {
        abertos.remove(usuarioId)?.forEach { it.complete() }
    }

    /** Quantas conexões abertas o usuário tem nesta instância (para os testes). */
    fun quantos(usuarioId: Long) = abertos[usuarioId]?.size ?: 0

    /**
     * Antes do desligamento gracioso: uma conexão de eventos nunca termina sozinha, e o servidor
     * esperaria por ela os 20 s inteiros. Fechadas aqui, os navegadores reconectam na instância nova.
     */
    @EventListener(ContextClosedEvent::class)
    fun fecharTudo() {
        batimento.shutdownNow()
        abertos.keys.toList().forEach(::fecharDe)
        envios.shutdown()
    }

    private fun ping() = SseEmitter.event().comment("ping")

    private fun enviar(
        usuarioId: Long,
        emissor: SseEmitter,
        evento: SseEmitter.SseEventBuilder,
    ) {
        try {
            envios.execute {
                try {
                    synchronized(emissor) { emissor.send(evento) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // O navegador foi embora (ou a conexão já fechou): tira da lista
                    remover(usuarioId, emissor)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Desligando
        }
    }

    private fun remover(
        usuarioId: Long,
        emissor: SseEmitter,
    ) {
        abertos.computeIfPresent(usuarioId) { _, lista ->
            lista.remove(emissor)
            lista.ifEmpty { null }
        }
    }

    companion object {
        const val MAX_POR_USUARIO = 5
        private const val BATIMENTO_SEGUNDOS = 25L
    }
}

/**
 * Escuta o canal do Postgres (LISTEN) numa conexão só dela e repassa cada evento a [Canais]. Se a
 * conexão cair, tenta de novo com espera crescente e, ao voltar, manda "pronto" a todas as telas:
 * o que aconteceu durante a queda não chegou, e elas buscam tudo de novo. Usa uma conexão do pool
 * o tempo todo (1 das 10).
 */
@Component
class Ouvinte(
    private val fonte: DataSource,
    private val canais: Canais,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var rodando = true

    @Volatile
    private var conectado = false

    @Volatile
    private var pid = 0

    /** Se a conexão do LISTEN está de pé (para os testes esperarem por ela). */
    fun conectado() = conectado

    /** O processo do Postgres que escuta (para o teste derrubar a conexão). */
    fun processo() = pid

    @EventListener(ApplicationReadyEvent::class)
    fun comecar() {
        Thread(::escutar, "fiel-eventos-ouvinte").apply { isDaemon = true }.start()
    }

    @EventListener(ContextClosedEvent::class)
    fun parar() {
        rodando = false
    }

    private fun escutar() {
        var espera = ESPERA_INICIAL
        while (rodando) {
            try {
                fonte.connection.use { conexao -> escutarNa(conexao) { espera = ESPERA_INICIAL } }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                conectado = false
                if (!rodando) return
                log.warn("ouvinte de eventos caiu ({}); tentando de novo em {} s", e.javaClass.simpleName, espera)
                Thread.sleep(espera * MS_POR_S)
                espera = minOf(espera * 2, ESPERA_MAXIMA)
            }
        }
    }

    private fun escutarNa(
        conexao: Connection,
        conectou: () -> Unit,
    ) {
        conexao.createStatement().use { it.execute("listen ${Eventos.CANAL}") }
        pid = processoDe(conexao)
        conectado = true
        conectou()
        canais.avisarTodos()
        repassarEnquantoRodando(conexao)
    }

    private fun processoDe(conexao: Connection): Int =
        conexao.createStatement().use { comando ->
            comando.executeQuery("select pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }

    /**
     * Sem aviso por [CONFERIR_APOS_MS], confere a conexão com um SELECT: um firewall que derruba
     * conexão parada sem avisar deixaria a leitura esperando para sempre, sem erro nenhum.
     */
    private fun repassarEnquantoRodando(conexao: Connection) {
        val pg = conexao.unwrap(PGConnection::class.java)
        var ultimoSinal = System.nanoTime()
        while (rodando) {
            val avisos = pg.getNotifications(ESPERA_POR_AVISO_MS)
            if (!avisos.isNullOrEmpty()) {
                ultimoSinal = System.nanoTime()
                avisos.forEach { repassar(it.parameter) }
            } else if (System.nanoTime() - ultimoSinal > TimeUnit.MILLISECONDS.toNanos(CONFERIR_APOS_MS)) {
                conexao.createStatement().use { it.execute("select 1") }
                ultimoSinal = System.nanoTime()
            }
        }
    }

    /** Um aviso estragado (NOTIFY escrito à mão, por exemplo) é ignorado, sem derrubar o LISTEN. */
    private fun repassar(texto: String) {
        try {
            val no = json.readTree(texto)
            val cobranca = no["cobranca"]?.takeUnless { it.isNull }?.asLong()
            canais.entregar(Evento(no.required("usuario").asLong(), no.required("tipo").asString(), cobranca))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("evento ignorado ({}): {}", e.javaClass.simpleName, texto.take(MAX_NO_LOG))
        }
    }

    private companion object {
        const val ESPERA_POR_AVISO_MS = 1_000
        const val CONFERIR_APOS_MS = 30_000L
        const val ESPERA_INICIAL = 1L
        const val ESPERA_MAXIMA = 30L
        const val MS_POR_S = 1_000L
        const val MAX_NO_LOG = 200
    }
}
