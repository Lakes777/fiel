import { useEffect, useRef, useState } from 'react';

/** Se a pessoa pediu menos movimento no sistema */
export function menosMovimento(): boolean {
    return (
        typeof matchMedia === 'function' &&
        matchMedia('(prefers-reduced-motion: reduce)').matches
    );
}

/**
 * Um número que conta até o valor novo (saldo que muda ao vivo, o bônus da tela inicial). A
 * primeira vez conta a partir de `de`; depois, do valor anterior. Com menos movimento, pula direto.
 */
export function useContagem(
    alvo: number,
    { de = alvo, duracao = 900 }: { de?: number; duracao?: number } = {},
) {
    const [valor, setValor] = useState(menosMovimento() ? alvo : de);
    const anterior = useRef(menosMovimento() ? alvo : de);
    useEffect(() => {
        const inicio = anterior.current;
        anterior.current = alvo;
        if (
            inicio === alvo ||
            menosMovimento() ||
            typeof requestAnimationFrame === 'undefined'
        ) {
            setValor(alvo);
            return;
        }
        const comeco = performance.now();
        let quadro = 0;
        const passo = (t: number) => {
            // O horário do quadro pode vir um pouco antes do performance.now() do começo: sem o piso, o
            // primeiro quadro daria um valor fora do intervalo (um saldo negativo piscando)
            const k = Math.max(0, Math.min(1, (t - comeco) / duracao));
            const suave = 1 - Math.pow(1 - k, 3);
            setValor(Math.round(inicio + (alvo - inicio) * suave));
            if (k < 1) quadro = requestAnimationFrame(passo);
        };
        quadro = requestAnimationFrame(passo);
        return () => cancelAnimationFrame(quadro);
    }, [alvo, duracao]);
    return valor;
}

/**
 * Devolve true por um instante quando `valor` muda (não na primeira vez): para pôr a classe
 * .mudou num saldo ou num estado que acabou de mudar ao vivo.
 */
export function useMudou(valor: unknown, duracao = 1400): boolean {
    const [mudou, setMudou] = useState(false);
    const primeiro = useRef(true);
    useEffect(() => {
        if (primeiro.current) {
            primeiro.current = false;
            return;
        }
        setMudou(true);
        const t = setTimeout(() => setMudou(false), duracao);
        return () => clearTimeout(t);
    }, [valor, duracao]);
    return mudou;
}
