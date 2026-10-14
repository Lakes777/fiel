package io.github.lakes777.fiel.razao

/** O lançamento mais comum: [valor] centavos saem de [de] e entram em [para]. */
fun transferencia(
    de: Long,
    para: Long,
    valor: Long,
    descricao: String = "transferência",
) = NovoLancamento(descricao, listOf(NovaPartida(de, Centavos(-valor)), NovaPartida(para, Centavos(valor))))
