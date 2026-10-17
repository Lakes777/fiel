import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * O "agora" que anda sozinho a cada `intervalo` ms, só enquanto `ativo` (uma cobrança pendente):
 * o "vence em 12 min" desce sem precisar de evento do servidor. Parado, não gasta nada.
 */
export function useAgora(intervalo: number, ativo = true): Date {
    const [agora, setAgora] = useState(() => new Date());
    useEffect(() => {
        if (!ativo) return;
        const t = setInterval(() => setAgora(new Date()), intervalo);
        return () => clearInterval(t);
    }, [intervalo, ativo]);
    return agora;
}

export type EstadoDaCopia = 'nada' | 'copiado' | 'falhou';

/**
 * Copia um texto (o código do QR, o segredo do webhook) e diz por um instante se deu certo. Sem
 * permissão de área de transferência (http sem TLS, navegador antigo), diz que falhou: o texto
 * fica visível na tela para a pessoa selecionar.
 */
export function useCopiar(): [EstadoDaCopia, (texto: string) => void] {
    const [estado, setEstado] = useState<EstadoDaCopia>('nada');
    const temporizador = useRef<ReturnType<typeof setTimeout> | null>(null);
    useEffect(
        () => () => {
            if (temporizador.current) clearTimeout(temporizador.current);
        },
        [],
    );
    const copiar = useCallback((texto: string) => {
        const mostrar = (novo: EstadoDaCopia) => {
            setEstado(novo);
            if (temporizador.current) clearTimeout(temporizador.current);
            temporizador.current = setTimeout(() => setEstado('nada'), 2500);
        };
        if (!navigator.clipboard) {
            mostrar('falhou');
            return;
        }
        navigator.clipboard.writeText(texto).then(
            () => mostrar('copiado'),
            () => mostrar('falhou'),
        );
    }, []);
    return [estado, copiar];
}
