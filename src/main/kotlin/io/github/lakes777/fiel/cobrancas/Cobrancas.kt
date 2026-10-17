package io.github.lakes777.fiel.cobrancas

import io.github.lakes777.fiel.avisos.Avisos
import io.github.lakes777.fiel.chaves.mascararNome
import io.github.lakes777.fiel.erros.Conflito
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.PedidoInvalido
import io.github.lakes777.fiel.eventos.Eventos
import io.github.lakes777.fiel.razao.NovaPartida
import io.github.lakes777.fiel.razao.NovoLancamento
import io.github.lakes777.fiel.razao.Razao
import io.github.lakes777.fiel.seguranca.LimitesDeUso
import io.github.lakes777.fiel.transferencias.Limites
import io.github.lakes777.fiel.transferencias.Saidas
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class PedidoDeCobranca(
    @Positive val valorCentavos: Long,
    @NotBlank @Size(max = 140) val descricao: String,
    /** Quanto tempo a cobrança vale; de 1 minuto a 1 dia. */
    @Min(1) @Max(MINUTOS_POR_DIA) val validadeMinutos: Long = 30,
)

data class PedidoComQr(
    @NotBlank @Size(max = 300) val qr: String,
)

/** A cobrança como o lojista vê: com o texto do QR para mostrar a quem vai pagar. */
data class CobrancaDoLojista(
    val id: Long,
    val valorCentavos: Long,
    val descricao: String,
    val estado: String,
    val criadaEm: Instant,
    val expiraEm: Instant,
    val pagaEm: Instant?,
    /** Quanto já voltou para quem pagou. */
    val estornadoCentavos: Long,
    val qr: String,
)

/** A cobrança como quem vai pagar vê antes de confirmar: o recebedor só pelo nome mascarado. */
data class CobrancaParaPagar(
    val valorCentavos: Long,
    val descricao: String,
    val recebedor: String,
    val estado: String,
    val expiraEm: Instant,
)

data class PagamentoFeito(
    val valorCentavos: Long,
    val descricao: String,
    val recebedor: String,
    val pagaEm: Instant,
)

private const val MINUTOS_POR_DIA = 1_440L

/**
 * Cobranças: o lojista (qualquer pessoa com carteira) cria, mostra o QR, e alguém paga uma vez.
 *
 * A idempotência do pagamento é natural, sem Idempotency-Key: a cobrança só pode ser paga uma vez.
 * Quem já pagou e manda de novo (a rede caiu) recebe o mesmo pagamento; outra pessoa recebe 409.
 */
@Service
class Cobrancas(
    private val banco: CobrancasNoBanco,
    private val razao: Razao,
    private val saidas: Saidas,
    private val avisos: Avisos,
    private val eventos: Eventos,
    private val relogio: Clock,
    transacoes: PlatformTransactionManager,
) {
    private val transacao = TransactionTemplate(transacoes)

    fun criar(
        usuario: UsuarioLogado,
        pedido: PedidoDeCobranca,
    ): CobrancaDoLojista {
        if (pedido.valorCentavos > Limites.DIA.valor) {
            throw PedidoInvalido(
                "o valor máximo de uma cobrança é ${Saidas.reais(Limites.DIA)}, o limite diário de quem paga",
            )
        }
        val agora = agora()
        val validade = Duration.ofMinutes(pedido.validadeMinutos)
        // Numa transação com o evento: se ele falhar, a cobrança não fica criada (repetir não duplica)
        return checkNotNull(
            transacao.execute {
                val id =
                    banco.inserir(
                        usuario.id,
                        pedido.valorCentavos,
                        pedido.descricao.trim(),
                        agora,
                        agora.plus(validade),
                    )
                val criada = checkNotNull(banco.porId(id))
                // Outras abas do lojista também mostram a cobrança nova
                eventos.publicar(criada.recebedorConta, "cobranca", id)
                criada.doLojista(agora)
            },
        )
    }

    /** As 100 mais recentes. */
    fun doLojista(usuarioId: Long): List<CobrancaDoLojista> {
        val agora = agora()
        return banco.doLojista(usuarioId).map { it.doLojista(agora) }
    }

    /** Autorização por objeto: a cobrança de outro lojista responde igual a uma que não existe. */
    fun doLojista(
        usuarioId: Long,
        id: Long,
    ): CobrancaDoLojista =
        banco.doLojista(usuarioId, id)?.doLojista(agora())
            ?: throw naoEncontrada()

    fun cancelar(
        usuarioId: Long,
        id: Long,
    ): CobrancaDoLojista =
        checkNotNull(
            transacao.execute {
                // Travada: cancelar e pagar ao mesmo tempo, um espera o outro e vê o estado novo
                val cobranca =
                    banco.doLojista(usuarioId, id, travar = true)
                        ?: throw naoEncontrada()
                val agora = agora()
                val estado = cobranca.estadoEm(agora)
                if (estado != "pendente") throw Conflito("só cobrança pendente pode ser cancelada; esta está $estado")
                banco.marcarCancelada(id)
                eventos.publicar(cobranca.recebedorConta, "cobranca", id)
                cobranca.copy(estado = "cancelada").doLojista(agora)
            },
        )

    /** O que quem vai pagar confere antes: dados do servidor, não do QR. */
    fun consultar(qr: String): CobrancaParaPagar {
        val lido = ler(qr)
        val cobranca = banco.porCodigo(lido.codigo) ?: throw naoEncontrada()
        cobranca.conferir(lido)
        return CobrancaParaPagar(
            cobranca.valor.valor,
            cobranca.descricao,
            mascararNome(cobranca.recebedor),
            cobranca.estadoEm(agora()),
            cobranca.expiraEm,
        )
    }

    /** O pagamento e se ele já existia (o mesmo pagador mandou de novo). */
    data class Resultado(
        val pagamento: PagamentoFeito,
        val repetido: Boolean,
    )

    /**
     * Numa transação: trava a cobrança (dois pagadores ao mesmo tempo: o segundo espera e vê que
     * ela já foi paga), depois as duas carteiras em ordem de id, confere saldo e limites e grava.
     * A ordem das travas é sempre cobrança e depois contas; nada trava uma conta e depois uma
     * cobrança, então não há como uma esperar a outra em círculo.
     */
    fun pagar(
        usuario: UsuarioLogado,
        qr: String,
    ): Resultado {
        val lido = ler(qr)
        return checkNotNull(
            transacao.execute {
                val cobranca =
                    banco.porCodigo(lido.codigo, travar = true) ?: throw naoEncontrada()
                cobranca.conferir(lido)
                val pagador = banco.carteiraDe(usuario.id)
                val agora = agora()
                // O pagamento gravado nunca muda: quem pagou repete e recebe o mesmo, mesmo depois de um estorno
                if (cobranca.pagadorConta == pagador) {
                    Resultado(cobranca.pagamento(), repetido = true)
                } else {
                    cobranca.exigirPagavel(pagador, agora)
                    Resultado(gravarPagamento(cobranca, usuario, pagador, agora), repetido = false)
                }
            },
        )
    }

    private fun gravarPagamento(
        cobranca: Cobranca,
        usuario: UsuarioLogado,
        pagador: Long,
        agora: Instant,
    ): PagamentoFeito {
        razao.travar(listOf(pagador, cobranca.recebedorConta))
        saidas.exigirSaldoELimite(pagador, cobranca.valor, agora)
        val recebedor = mascararNome(cobranca.recebedor)
        val lancamento =
            razao.registrar(
                NovoLancamento(
                    "Pagamento de ${mascararNome(usuario.nome)} a $recebedor: ${cobranca.descricao}"
                        .take(NovoLancamento.MAX_DESCRICAO),
                    listOf(NovaPartida(pagador, -cobranca.valor), NovaPartida(cobranca.recebedorConta, cobranca.valor)),
                ),
            )
        banco.marcarPaga(cobranca.id, pagador, lancamento.id, agora)
        // Na mesma transação do pagamento (outbox): pagou, o aviso existe; desfez, nenhum dos dois
        avisos.registrar(
            cobranca.recebedorConta,
            "cobranca.paga",
            mapOf(
                "cobranca" to cobranca.dadosDoAviso() + ("pagaEm" to agora.toString()),
                "pagador" to mascararNome(usuario.nome),
            ),
            agora,
        )
        eventos.publicar(pagador, "carteira")
        eventos.publicar(cobranca.recebedorConta, "carteira")
        eventos.publicar(cobranca.recebedorConta, "cobranca", cobranca.id)
        return PagamentoFeito(cobranca.valor.valor, cobranca.descricao, recebedor, agora)
    }

    /**
     * Marca como expiradas as pendentes que venceram. Só arruma o estado guardado: quem decide se
     * ainda dá para pagar é a hora, conferida no pagamento, então um atraso desta tarefa não deixa
     * pagar cobrança vencida. Uma cobrança sendo paga agora está travada; esta espera e, depois,
     * já a vê paga e passa reto.
     */
    fun expirarVencidas(): Int =
        checkNotNull(
            transacao.execute {
                val agora = agora()
                val expiradas = banco.marcarExpiradas(agora)
                expiradas.forEach {
                    avisos.registrar(
                        it.recebedorConta,
                        "cobranca.expirada",
                        mapOf(
                            "cobranca" to it.dadosDoAviso(),
                        ),
                        agora,
                    )
                    eventos.publicar(it.recebedorConta, "cobranca", it.id)
                }
                expiradas.size
            },
        )

    private fun ler(qr: String): Qr = Qr.ler(qr.trim()) ?: throw PedidoInvalido("QR inválido: não é um QR do Fiel")

    /** A hora com a precisão do Postgres (microssegundos): a resposta repetida mostra a mesma hora da primeira. */
    private fun agora() = relogio.instant().truncatedTo(ChronoUnit.MICROS)

    private fun naoEncontrada() = NaoEncontrado("cobrança não encontrada")
}

@RestController
class CobrancasController(
    private val cobrancas: Cobrancas,
    private val limites: LimitesDeUso,
) {
    @PostMapping("/cobrancas")
    @ResponseStatus(HttpStatus.CREATED)
    fun criar(
        @Valid @RequestBody pedido: PedidoDeCobranca,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): CobrancaDoLojista {
        limites.cobranca.registrar(usuario.username)
        return cobrancas.criar(usuario, pedido)
    }

    @GetMapping("/cobrancas")
    fun minhas(
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = cobrancas.doLojista(usuario.id)

    @GetMapping("/cobrancas/{id}")
    fun uma(
        @PathVariable id: Long,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = cobrancas.doLojista(usuario.id, id)

    @PostMapping("/cobrancas/{id}/cancelamento")
    fun cancelar(
        @PathVariable id: Long,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ) = cobrancas.cancelar(usuario.id, id)

    /** POST e não GET: o QR inteiro iria parar na URL, e a URL vai para logs e histórico. */
    @PostMapping("/pagamentos/consulta")
    fun consultar(
        @Valid @RequestBody pedido: PedidoComQr,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): CobrancaParaPagar {
        limites.pagamento.registrar(usuario.username)
        return cobrancas.consultar(pedido.qr)
    }

    @PostMapping("/pagamentos")
    fun pagar(
        @Valid @RequestBody pedido: PedidoComQr,
        @AuthenticationPrincipal usuario: UsuarioLogado,
    ): ResponseEntity<PagamentoFeito> {
        limites.pagamento.registrar(usuario.username)
        val (feito, repetido) = cobrancas.pagar(usuario, pedido.qr)
        val resposta = ResponseEntity.status(HttpStatus.CREATED)
        if (repetido) resposta.header("Idempotent-Replayed", "true")
        return resposta.body(feito)
    }
}
