import { useCallback, useMemo, useRef } from 'react';
import { novaIdempotencia } from '../api/cliente';

export interface IdempotenciaDoPedido {
    /** A chave para mandar (chave Pix + valor): a mesma enquanto o pedido for o mesmo */
    para(chave: string, valorCentavos: number): string;
    /** O pedido deu certo: o próximo, mesmo igual, é outra transferência */
    concluir(): void;
}

/**
 * A Idempotency-Key de uma transferência. Por que guardar e reaproveitar: se a rede cai depois do
 * envio, a tela não sabe se o dinheiro saiu. Mandar de novo com a MESMA chave faz a API devolver a
 * transferência já feita (Idempotent-Replayed) em vez de fazer outra: a pessoa pode tocar em
 * "Tentar de novo" sem medo de pagar duas vezes. A chave muda quando o pedido muda (outra chave
 * Pix ou outro valor: a API recusaria a mesma chave com outro corpo) e depois do sucesso (uma
 * segunda transferência igual, de propósito, é outra transferência). Fica na Carteira, e não no
 * diálogo, para sobreviver a fechar e abrir o diálogo depois do erro.
 */
export function useIdempotencia(): IdempotenciaDoPedido {
    const atual = useRef<{ pedido: string; chave: string } | null>(null);
    const para = useCallback((chave: string, valorCentavos: number) => {
        const pedido = `${chave}\n${valorCentavos}`;
        if (atual.current?.pedido !== pedido)
            atual.current = { pedido, chave: novaIdempotencia() };
        return atual.current.chave;
    }, []);
    const concluir = useCallback(() => {
        atual.current = null;
    }, []);
    return useMemo(() => ({ para, concluir }), [para, concluir]);
}
