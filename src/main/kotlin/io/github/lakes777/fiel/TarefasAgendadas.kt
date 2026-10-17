package io.github.lakes777.fiel

import io.github.lakes777.fiel.avisos.Entregador
import io.github.lakes777.fiel.cobrancas.Cobrancas
import io.github.lakes777.fiel.conciliacao.Conciliacao
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O que roda sozinho, de tempos em tempos. Desligável (fiel.tarefas-agendadas=false): os testes
 * chamam cada tarefa na hora que querem, com o relógio parado.
 *
 * Cada tarefa é segura com várias instâncias rodando ao mesmo tempo: a expiração é um UPDATE que
 * só pega pendentes, a entrega reserva os avisos com SKIP LOCKED, e a conciliação grava um
 * resultado por dia (a segunda instância encontra o dia ocupado e não avisa de novo).
 */
@Component
@EnableScheduling
@ConditionalOnProperty("fiel.tarefas-agendadas", havingValue = "true", matchIfMissing = true)
class TarefasAgendadas(
    private val cobrancas: Cobrancas,
    private val entregador: Entregador,
    private val conciliacao: Conciliacao,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun expirarCobrancas() {
        val expiradas = cobrancas.expirarVencidas()
        if (expiradas > 0) log.info("{} cobrança(s) expirada(s)", expiradas)
    }

    /** A cada 2 s; um lote cheio roda de novo na hora, para uma fila grande não esperar 2 s por lote. */
    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    fun entregarAvisos() {
        while (entregador.entregarLote() == Entregador.LOTE) {
            // continua enquanto vier lote cheio
        }
    }

    /** De madrugada, no horário de Brasília: o movimento é pouco e o resultado chega antes do dia começar. */
    @Scheduled(cron = "\${fiel.conciliacao.cron:0 0 3 * * *}", zone = "America/Sao_Paulo")
    fun conciliar() {
        conciliacao.conciliar()
    }

    /** A mensagem da conciliação que não chegou (Telegram fora do ar) vai de novo, a cada 5 min. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    fun avisarConciliacao() {
        conciliacao.avisarPendentes()
    }
}
