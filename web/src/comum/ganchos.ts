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
 * fica visível na tela para a pessoa selecionar. A `marca` (qual das chaves, por exemplo) muda junto
 * com o resultado, e só a última cópia pedida vale: uma cópia lenta não sobrescreve a seguinte.
 */
export function useCopiar<M = undefined>(): [
    EstadoDaCopia,
    (texto: string, marca?: M) => void,
    M | undefined,
] {
    const [{ estado, marca }, setResultado] = useState<{
        estado: EstadoDaCopia;
        marca?: M;
    }>({ estado: 'nada' });
    const temporizador = useRef<ReturnType<typeof setTimeout> | null>(null);
    const ultima = useRef(0);
    useEffect(
        () => () => {
            if (temporizador.current) clearTimeout(temporizador.current);
        },
        [],
    );
    const copiar = useCallback((texto: string, marcaNova?: M) => {
        const pedido = ++ultima.current;
        const mostrar = (novo: EstadoDaCopia) => {
            if (pedido !== ultima.current) return;
            setResultado({ estado: novo, marca: marcaNova });
            if (temporizador.current) clearTimeout(temporizador.current);
            temporizador.current = setTimeout(
                () => setResultado({ estado: 'nada' }),
                2500,
            );
        };
        // A cópia anterior some na hora: o "Copiada" não fica no botão errado enquanto esta não termina
        if (temporizador.current) clearTimeout(temporizador.current);
        setResultado({ estado: 'nada' });
        if (!navigator.clipboard) {
            mostrar('falhou');
            return;
        }
        navigator.clipboard.writeText(texto).then(
            () => mostrar('copiado'),
            () => mostrar('falhou'),
        );
    }, []);
    return [estado, copiar, marca];
}
