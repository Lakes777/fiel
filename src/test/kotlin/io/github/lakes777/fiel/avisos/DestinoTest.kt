package io.github.lakes777.fiel.avisos

import io.github.lakes777.fiel.erros.PedidoInvalido
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DestinoTest {
    /** DNS de mentira: cada nome resolve para os IPs que o teste mandar. */
    private fun destino(vararg dns: Pair<String, List<String>>): Destino {
        val tabela = dns.toMap()
        return Destino(permitirRedeLocal = false) { nome ->
            tabela[nome]?.map { InetAddress.getByName(it) } ?: throw UnknownHostException(nome)
        }
    }

    @Test
    fun `endereço público com https passa`() {
        val d = destino("loja.exemplo.com" to listOf("93.184.216.34", "2606:2800:220:1::1"))

        assertEquals("https://loja.exemplo.com/avisos", d.conferir("https://loja.exemplo.com/avisos").toString())
    }

    @Test
    fun `recusa rede interna, metadados da nuvem e qualquer nome que resolva para elas`() {
        val internos =
            listOf(
                "127.0.0.1",
                "10.0.0.5",
                "172.16.3.4",
                "192.168.0.10",
                // Metadados da nuvem (Oracle, AWS, GCP): credenciais da máquina
                "169.254.169.254",
                "100.64.0.1",
                "0.0.0.0",
                "::1",
                "fd00::1",
                "fe80::1",
                "224.0.0.1",
                "0.0.0.1",
                "198.18.0.1",
                "240.0.0.1",
                "255.255.255.255",
                // IPv4 dentro de IPv6: levariam a um IPv4 sem passar pela conferência dele
                "::ffff:127.0.0.1",
                "::127.0.0.1",
                "64:ff9b::a00:5",
                "2002:a00:5::1",
            )
        for (ip in internos) {
            assertFailsWith<PedidoInvalido>(
                ip,
            ) { destino("x.exemplo.com" to listOf(ip)).conferir("https://x.exemplo.com/") }
        }
        // Um IP público e um interno: o pedido iria para o interno de vez em quando
        assertFailsWith<PedidoInvalido> {
            destino("misto.exemplo.com" to listOf("93.184.216.34", "10.0.0.5")).conferir("https://misto.exemplo.com/")
        }
    }

    @Test
    fun `recusa http, usuário e senha na URL, nome que não existe e URL torta`() {
        val d = destino("loja.exemplo.com" to listOf("93.184.216.34"))

        val recusadas =
            listOf(
                "http://loja.exemplo.com/",
                "ftp://loja.exemplo.com/",
                "https://ana:segredo@loja.exemplo.com/",
                "https://nao-existe.exemplo.com/",
                "https:///sem-endereco",
                "https://loja.exemplo.com/ com espaço",
                "https://loja.exemplo.com:99999/",
                "https://loja.exemplo.com:0/",
            )
        for (url in recusadas) assertFailsWith<PedidoInvalido>(url) { d.conferir(url) }
    }

    @Test
    fun `em desenvolvimento aceita http e a própria máquina`() {
        assertEquals(
            "http://localhost:8099/",
            Destino(permitirRedeLocal = true).conferir("http://localhost:8099/").toString(),
        )
    }
}
