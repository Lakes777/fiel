package io.github.lakes777.fiel.erros

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Um erro que a API explica a quem chamou: vira uma resposta RFC 9457 com [status] e a mensagem. */
open class ErroDaApi(
    val status: HttpStatus,
    mensagem: String,
) : RuntimeException(mensagem)

class PedidoInvalido(
    mensagem: String,
) : ErroDaApi(HttpStatus.BAD_REQUEST, mensagem)

class NaoAutenticado(
    mensagem: String,
) : ErroDaApi(HttpStatus.UNAUTHORIZED, mensagem)

/**
 * Também é o que recebe quem pede algo de outra pessoa: responder 403 contaria que aquilo existe.
 */
class NaoEncontrado(
    mensagem: String,
) : ErroDaApi(HttpStatus.NOT_FOUND, mensagem)

class Conflito(
    mensagem: String,
) : ErroDaApi(HttpStatus.CONFLICT, mensagem)

/** O pedido está bem escrito, mas a regra não deixa: saldo insuficiente, limite passado. */
class RegraDeNegocio(
    mensagem: String,
) : ErroDaApi(HttpStatus.UNPROCESSABLE_CONTENT, mensagem)

class MuitasTentativas(
    val esperarSegundos: Long,
) : ErroDaApi(HttpStatus.TOO_MANY_REQUESTS, "muitas tentativas; tente de novo em $esperarSegundos s")

@RestControllerAdvice
class TratadorDeErros {
    @ExceptionHandler(ErroDaApi::class)
    fun erroDaApi(erro: ErroDaApi): ResponseEntity<ProblemDetail> {
        val resposta = ResponseEntity.status(erro.status)
        if (erro is MuitasTentativas) resposta.header(HttpHeaders.RETRY_AFTER, erro.esperarSegundos.toString())
        return resposta.body(ProblemDetail.forStatusAndDetail(erro.status, erro.message))
    }
}
