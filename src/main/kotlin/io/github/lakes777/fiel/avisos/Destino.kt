package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.erros.PedidoInvalido
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Para onde um aviso pode ir. O Fiel faz um pedido HTTP para uma URL escolhida pelo usuário, então
 * sem esta conferência ele viraria uma porta para a rede de dentro do servidor (SSRF): alguém
 * cadastraria http://169.254.169.254/ (os metadados da nuvem, com credenciais), http://localhost:5432
 * ou um contêiner vizinho, e usaria o Fiel para sondar o que não está exposto.
 *
 * Confere na hora do cadastro e de novo antes de cada envio (o DNS pode mudar depois do cadastro).
 * Sobra uma janela pequena: o nome pode mudar de endereço entre esta conferência e a conexão
 * (DNS rebinding). Fechar de vez exigiria conectar no IP conferido, o que o HttpClient do Java não
 * deixa fazer mantendo o HTTPS com o nome certo.
 */
class Destino(
    /** Só nos testes e no desenvolvimento: aceita http e endereços locais (o receptor de teste roda na máquina). */
    private val permitirRedeLocal: Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** A URL pronta para usar, ou [PedidoInvalido] explicando o que está errado. */
    fun conferir(url: String): URI {
        val uri =
            try {
                URI(url.trim())
            } catch (_: java.net.URISyntaxException) {
                throw PedidoInvalido("URL inválida")
            }
        val esquemas = if (permitirRedeLocal) setOf("https", "http") else setOf("https")
        exigir(uri.scheme?.lowercase() in esquemas) { "a URL do aviso precisa ser https://" }
        exigir(uri.rawUserInfo == null) { "a URL não pode ter usuário e senha" }
        val host = uri.host
        exigir(host != null) { "a URL precisa de um endereço" }
        exigir(uri.port == -1 || uri.port in 1..MAIOR_PORTA) { "porta inválida" }
        if (!permitirRedeLocal) exigirEnderecoPublico(checkNotNull(host))
        return uri
    }

    private fun exigir(
        condicao: Boolean,
        mensagem: () -> String,
    ) {
        if (!condicao) throw PedidoInvalido(mensagem())
    }

    private fun exigirEnderecoPublico(host: String) {
        val enderecos =
            try {
                resolver(host)
            } catch (_: UnknownHostException) {
                throw PedidoInvalido("o endereço $host não existe")
            }
        // Todos: um nome com um IP público e um interno levaria o pedido para o interno de vez em quando
        if (enderecos.isEmpty() || enderecos.any(::interno)) {
            throw PedidoInvalido("o endereço $host aponta para uma rede interna")
        }
    }

    companion object {
        private const val MAIOR_PORTA = 65_535
        private const val UM_BYTE = 0xFF

        /** Prefixos IPv4 que não são internet pública, como (primeiro byte, segundo byte de, até). */
        private val IPV4_FORA_DA_INTERNET =
            listOf(
                // 0.0.0.0/8: "esta rede"
                Triple(0, 0, 255),
                // 100.64.0.0/10: CGNAT, a rede de dentro do provedor
                Triple(100, 64, 127),
                // 198.18.0.0/15: testes de equipamento de rede
                Triple(198, 18, 19),
            )

        /** 240.0.0.0/4 em diante: reservado, e o fim é o broadcast 255.255.255.255. */
        private const val IPV4_RESERVADO = 240

        /**
         * Loopback, redes privadas, link-local (os metadados da nuvem, 169.254.169.254), multicast,
         * as faixas IPv4 de [IPV4_FORA_DA_INTERNET] e reservadas, e no IPv6: local (fc00::/7),
         * IPv4 embutido (::a.b.c.d, 64:ff9b::/96 do NAT64, 2002::/16 do 6to4), que levariam a um
         * IPv4 sem passar pela conferência dele.
         */
        fun interno(endereco: InetAddress): Boolean {
            val doJava =
                listOf(
                    endereco.isLoopbackAddress,
                    endereco.isSiteLocalAddress,
                    endereco.isLinkLocalAddress,
                    endereco.isAnyLocalAddress,
                    endereco.isMulticastAddress,
                )
            val b = endereco.address.map { it.toInt() and UM_BYTE }
            return doJava.any { it } || if (endereco is Inet6Address) ipv6Interno(b) else ipv4Interno(b)
        }

        private fun ipv4Interno(b: List<Int>) =
            b[0] >= IPV4_RESERVADO ||
                IPV4_FORA_DA_INTERNET.any { (primeiro, de, ate) -> b[0] == primeiro && b[1] in de..ate }

        @Suppress("MagicNumber")
        private fun ipv6Interno(b: List<Int>) =
            // fc00::/7
            (b[0] and 0xFE) == 0xFC ||
                // ::a.b.c.d (os 12 primeiros bytes zerados)
                b.take(12).all { it == 0 } ||
                // 64:ff9b::/96
                (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && b.subList(4, 12).all { it == 0 }) ||
                // 2002::/16
                (b[0] == 0x20 && b[1] == 0x02)
    }
}
