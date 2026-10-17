package io.github.lakes777.fiel.avisos

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * O cabeçalho Fiel-Assinatura: `t=<segundos desde 1970>,v1=<HMAC-SHA256 em hexadecimal>`, calculado
 * sobre `"<t>.<corpo>"` com o segredo do webhook (o mesmo desenho do Stripe).
 *
 * O HMAC prova que o aviso veio de quem tem o segredo e que o corpo não mudou no caminho. O `t`
 * entra na conta para o lojista recusar um aviso velho reenviado por alguém que o capturou: ele
 * confere se `t` está a poucos minutos da hora dele.
 */
object Assinatura {
    fun assinar(
        segredo: String,
        segundos: Long,
        corpo: String,
    ): String = "t=$segundos,v1=${hmac(segredo, "$segundos.$corpo")}"

    /** O que o lojista faz: o mesmo cálculo, comparado em tempo constante. */
    fun confere(
        segredo: String,
        cabecalho: String,
        corpo: String,
    ): Boolean {
        val partes = cabecalho.split(",").mapNotNull { parte -> parte.split("=", limit = 2).takeIf { it.size == 2 } }
        val campos = partes.associate { (chave, valor) -> chave.trim() to valor.trim() }
        val segundos = campos["t"]?.toLongOrNull()
        val recebido = campos["v1"]
        return segundos != null &&
            recebido != null &&
            MessageDigest.isEqual(hmac(segredo, "$segundos.$corpo").toByteArray(), recebido.toByteArray())
    }

    private fun hmac(
        segredo: String,
        texto: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(segredo.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(texto.toByteArray()))
    }
}
