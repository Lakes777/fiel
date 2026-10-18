package io.github.lakes777.fiel.demo

import io.github.lakes777.fiel.chaves.Chaves
import io.github.lakes777.fiel.cobrancas.Cobrancas
import io.github.lakes777.fiel.cobrancas.PagamentoFeito
import io.github.lakes777.fiel.cobrancas.PedidoDeCobranca
import io.github.lakes777.fiel.erros.NaoEncontrado
import io.github.lakes777.fiel.erros.RegraDeNegocio
import io.github.lakes777.fiel.usuarios.Cadastro
import io.github.lakes777.fiel.usuarios.UsuarioLogado
import io.github.lakes777.fiel.usuarios.Usuarios
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/** Domínios das contas da demonstração: o cadastro público recusa e-mails deles. */
val DOMINIOS_DA_DEMO = listOf("demo.fiel", "exemplo.fiel")

/** O que a tela mostra ao visitante para ele saber o que experimentar. */
data class Roteiro(
    /** O QR da cobrança da Loja Exemplo feita para ele (a tela abre /pagar?qr=...). */
    val qrDaLoja: String,
    val chaveDaAna: String,
    val chaveDoBob: String,
)

data class Visitante(
    val usuario: UsuarioLogado,
    val roteiro: Roteiro,
)

/**
 * A demonstração pública (FIEL_DEMO=true): quem abre o site entra como visitante sem inventar
 * e-mail e senha.
 *
 * Cada visitante ganha uma conta descartável com os R$ 1.000 de todo cadastro, uma cobrança da Loja
 * Exemplo para pagar e um **Bob só dele**, que paga as cobranças que o visitante criar (a cena "o
 * pagamento chegou agora" no painel da loja). Um Bob para todos esvaziaria em uns 16 visitantes, e a
 * demonstração pararia até o banco ser recriado (uma vez por dia, ver deploy/).
 *
 * Tudo passa pelos mesmos serviços da API: os R$ 1.000 vêm da Emissão num lançamento normal, e o
 * pagamento do Bob tem as mesmas regras de qualquer pagamento. As senhas são aleatórias e ninguém
 * as conhece: as contas da demonstração só entram pelo botão de visitante.
 */
@Service
class Demonstracao(
    @param:Value("\${fiel.demo:false}") val ativa: Boolean,
    @Value("\${server.forward-headers-strategy:none}") estrategiaDoProxy: String,
    private val cadastro: Cadastro,
    private val usuarios: Usuarios,
    private val chaves: Chaves,
    private val cobrancas: Cobrancas,
) {
    init {
        // A demonstração é pública e fica atrás do Caddy. Sem ler o X-Forwarded-For, todo visitante
        // teria o IP do Caddy, e o 11º da hora travaria a demonstração para o site inteiro: melhor
        // não subir do que subir assim
        check(!ativa || !estrategiaDoProxy.equals("none", ignoreCase = true)) {
            "FIEL_DEMO=true exige FIEL_CABECALHOS_DO_PROXY=native (a demonstração fica atrás do proxy)"
        }
    }

    private data class Pessoa(
        val nome: String,
        val email: String,
    )

    /** As contas fixas: existem desde a partida, para todo visitante. */
    @EventListener(ApplicationReadyEvent::class)
    fun semear() {
        if (ativa) {
            garantir(LOJA)
            garantir(ANA)
        }
    }

    fun novoVisitante(): Visitante {
        val sufixo = UUID.randomUUID().toString().take(SUFIXO)
        val visitante = cadastrar(Pessoa("Visitante ${sufixo.uppercase()}", "visitante-$sufixo@$DOMINIO"))
        val bob = cadastrar(Pessoa("Bob Lima", bobDe(sufixo)))
        chaves.criar(bob, "email")
        val cobranca = cobrancas.criar(garantir(LOJA), PedidoDeCobranca(PRECO_DA_CAMISETA, "Camiseta Fiel", UM_DIA))
        // Vai para a sessão: sem o hash da senha (o login normal apaga do mesmo jeito)
        visitante.eraseCredentials()
        return Visitante(visitante, Roteiro(cobranca.qr, ANA.email, bob.email))
    }

    /** O Bob do visitante paga uma cobrança que o visitante criou. */
    fun bobPaga(
        visitante: UsuarioLogado,
        cobrancaId: Long,
    ): PagamentoFeito {
        val sufixo =
            VISITANTE.matchEntire(visitante.email)?.groupValues?.get(1)
                ?: throw RegraDeNegocio("só a conta de visitante da demonstração tem um Bob para pagar")
        // Só uma cobrança do próprio visitante (de outra pessoa: 404, como em toda a API)
        val qr = cobrancas.doLojista(visitante.id, cobrancaId).qr
        val bob = logado(bobDe(sufixo)) ?: throw NaoEncontrado("o Bob deste visitante não existe mais")
        return cobrancas.pagar(bob, qr).pagamento
    }

    private fun garantir(pessoa: Pessoa): UsuarioLogado =
        logado(pessoa.email) ?: cadastrar(pessoa).also { chaves.criar(it, "email") }

    private fun cadastrar(pessoa: Pessoa): UsuarioLogado {
        cadastro.cadastrar(pessoa.nome, pessoa.email, "${UUID.randomUUID()}${UUID.randomUUID()}")
        return checkNotNull(logado(pessoa.email))
    }

    private fun logado(email: String): UsuarioLogado? =
        try {
            usuarios.loadUserByUsername(email) as UsuarioLogado
        } catch (_: UsernameNotFoundException) {
            null
        }

    private fun bobDe(sufixo: String) = "bob-$sufixo@$DOMINIO"

    private companion object {
        const val DOMINIO = "demo.fiel"
        const val SUFIXO = 8
        const val PRECO_DA_CAMISETA = 5_990L
        const val UM_DIA = 1_440L
        val VISITANTE = Regex("^visitante-([0-9a-f]{8})@demo\\.fiel$")
        val LOJA = Pessoa("Loja Exemplo", "loja@exemplo.fiel")
        val ANA = Pessoa("Ana Souza", "ana@exemplo.fiel")
    }
}
