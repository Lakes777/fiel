import { useEffect, useRef, useState } from 'react';

/** Se a pessoa pediu menos movimento no sistema */
export function menosMovimento(): boolean {
    return (
        typeof matchMedia === 'function' &&
        matchMedia('(prefers-reduced-motion: reduce)').matches
    );
}

/**
 * Um número que conta até o valor novo (saldo que muda ao vivo, o bônus da tela inicial). Enquanto
 * `alvo` é null (carregando), fica em 0. O primeiro valor que chega aparece direto, a não ser que
 * `de` diga de onde contar (a tela inicial conta do zero); depois, cada mudança conta a partir do
 * valor anterior. Com menos movimento, pula direto sempre.
 */
export function useContagem(
    alvo: number | null,
    { de, duracao = 900 }: { de?: number; duracao?: number } = {},
): number {
    const [valor, setValor] = useState(alvo === null ? 0 : (de ?? alvo));
    // undefined: nenhum valor chegou ainda
    const anterior = useRef<number | undefined>(
        alvo === null ? undefined : (de ?? alvo),
    );
    useEffect(() => {
        if (alvo === null) return;
        const inicio = anterior.current ?? de ?? alvo;
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
        // `de` só vale para o primeiro valor: mudar depois não recomeça a contagem
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [alvo, duracao]);
    return valor;
}

/**
 * Devolve true por um instante quando `valor` muda: para pôr a classe .mudou num saldo ou num
 * estado que acabou de mudar ao vivo. null/undefined é "carregando": o primeiro valor de verdade
 * não conta como mudança (abrir a tela não pisca nada).
 */
export function useMudou(valor: unknown, duracao = 1400): boolean {
    const [mudou, setMudou] = useState(false);
    const anterior = useRef(valor);
    useEffect(() => {
        const antes = anterior.current;
        anterior.current = valor;
        if (antes == null || Object.is(antes, valor)) return;
        setMudou(true);
        const t = setTimeout(() => setMudou(false), duracao);
        return () => clearTimeout(t);
    }, [valor, duracao]);
    return mudou;
}
