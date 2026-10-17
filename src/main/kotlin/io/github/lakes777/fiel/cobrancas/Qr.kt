package io.github.lakes777.fiel.cobrancas

import java.util.UUID

/**
 * O texto que vai dentro do QR de uma cobrança, num formato próprio do Fiel parecido com o dos QR
 * de pagamento de verdade (EMV): uma fila de campos TLV, cada um com ID (2 dígitos), tamanho
 * (2 dígitos) e valor, terminando num CRC16 que pega erro de leitura ou de digitação.
 *
 * De propósito incompatível com app de banco: o primeiro campo é "FIEL", não o "01" do padrão EMV,
 * então nenhum app de banco tenta pagar um QR do Fiel (nem o contrário).
 *
 * O CRC não é segurança: qualquer um recalcula. Quem paga vê os dados da cobrança vindos do
 * servidor, e um QR cujo valor não bate com o da cobrança é recusado.
 *
 * | ID | Campo |
 * | -- | ----- |
 * | 00 | formato, sempre FIEL |
 * | 01 | versão, sempre 1 |
 * | 26 | código da cobrança (UUID) |
 * | 54 | valor em centavos |
 * | 59 | recebedor, mascarado ("Bob L."), até 25 caracteres (o tamanho conta unidades UTF-16) |
 * | 63 | CRC16/CCITT-FALSE de tudo antes dele, incluindo o próprio "6304" |
 */
data class Qr(
    val codigo: UUID,
    val valorCentavos: Long,
    val recebedor: String,
) {
    fun texto(): String {
        val corpo =
            campo(FORMATO, "FIEL") + campo(VERSAO, "1") + campo(CODIGO, codigo.toString()) +
                campo(VALOR, valorCentavos.toString()) + campo(RECEBEDOR, ateCaracteres(recebedor, MAX_RECEBEDOR)) +
                CRC + "04"
        return corpo + crc16(corpo)
    }

    companion object {
        private const val FORMATO = "00"
        private const val VERSAO = "01"
        private const val CODIGO = "26"
        private const val VALOR = "54"
        private const val RECEBEDOR = "59"
        private const val CRC = "63"
        private const val MAX_RECEBEDOR = 25
        private const val MAX_CAMPO = 99
        private const val TAMANHO_DO_CRC = 4
        private const val TAMANHO_DO_ID = 2

        /** ID e tamanho, 2 dígitos cada, antes do valor de cada campo. */
        private const val CABECALHO = 4
        private const val CRC_INICIAL = 0xFFFF
        private const val POLINOMIO = 0x1021
        private const val BIT_MAIS_ALTO = 0x8000
        private const val UM_BYTE = 0xFF
        private const val BITS_POR_BYTE = 8
        private const val HEXADECIMAL = 16

        private val DOIS_DIGITOS = Regex("[0-9]{2}")
        private val VALOR_ESCRITO = Regex("[1-9][0-9]*")

        /** Corta em caracteres de verdade (code points): take() poderia partir um emoji no meio. */
        fun ateCaracteres(
            texto: String,
            maximo: Int,
        ): String =
            if (texto.codePointCount(0, texto.length) <= maximo) {
                texto
            } else {
                texto.substring(0, texto.offsetByCodePoints(0, maximo))
            }

        private fun campo(
            id: String,
            valor: String,
        ): String {
            require(valor.length in 1..MAX_CAMPO) { "campo $id com ${valor.length} caracteres" }
            return id + valor.length.toString().padStart(2, '0') + valor
        }

        /** Lê o texto de um QR; null se ele não for um QR do Fiel válido. */
        fun ler(texto: String): Qr? =
            try {
                lerOuFalhar(texto)
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun lerOuFalhar(texto: String): Qr {
            val campos = linkedMapOf<String, String>()
            var i = 0
            while (i < texto.length) {
                require(i + CABECALHO <= texto.length)
                val id = texto.substring(i, i + TAMANHO_DO_ID)
                val textoDoTamanho = texto.substring(i + TAMANHO_DO_ID, i + CABECALHO)
                // Só 0-9 ASCII: isDigit e toInt também aceitariam "+5" e dígitos de outros alfabetos ("٠٥")
                require(DOIS_DIGITOS.matches(id) && DOIS_DIGITOS.matches(textoDoTamanho))
                val tamanho = textoDoTamanho.toInt()
                val inicio = i + CABECALHO
                require(tamanho >= 1 && inicio + tamanho <= texto.length)
                require(campos.put(id, texto.substring(inicio, inicio + tamanho)) == null) { "campo $id repetido" }
                i = inicio + tamanho
            }
            // O CRC é o último campo e cobre tudo antes do valor dele
            val crc = campos[CRC]
            require(campos.keys.lastOrNull() == CRC && crc?.length == TAMANHO_DO_CRC)
            require(crc16(texto.dropLast(TAMANHO_DO_CRC)) == crc.uppercase()) { "CRC não confere" }
            require(campos[FORMATO] == "FIEL" && campos[VERSAO] == "1") { "não é um QR do Fiel" }
            val textoDoCodigo = requireNotNull(campos[CODIGO])
            val codigo = UUID.fromString(textoDoCodigo)
            // UUID.fromString aceita formas tortas ("1-1-1-1-1"); só vale a forma que o Fiel escreve
            require(codigo.toString() == textoDoCodigo)
            // Do jeito que o Fiel escreve: sem sinal, sem zero à esquerda
            val valor = requireNotNull(campos[VALOR]?.takeIf(VALOR_ESCRITO::matches)?.toLongOrNull())
            return Qr(codigo, valor, requireNotNull(campos[RECEBEDOR]))
        }

        /** CRC16/CCITT-FALSE (polinômio 0x1021, começa em 0xFFFF), em 4 dígitos hexadecimais maiúsculos. */
        fun crc16(texto: String): String {
            var crc = CRC_INICIAL
            for (byte in texto.toByteArray()) {
                crc = crc xor ((byte.toInt() and UM_BYTE) shl BITS_POR_BYTE)
                repeat(BITS_POR_BYTE) {
                    crc = if (crc and BIT_MAIS_ALTO != 0) (crc shl 1) xor POLINOMIO else crc shl 1
                }
                crc = crc and CRC_INICIAL
            }
            return crc.toString(HEXADECIMAL).uppercase().padStart(TAMANHO_DO_CRC, '0')
        }
    }
}
