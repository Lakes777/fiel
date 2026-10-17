import {
    useCallback,
    useEffect,
    useRef,
    useState,
    useSyncExternalStore,
} from 'react';
import { ErroDaApi, mensagemDe } from './cliente';
import { aoVivo, type EventoAoVivo, type TipoDeEvento } from './eventos';
import { assinarSessao, estadoDaSessao, type EstadoDaSessao } from './sessao';

/** Quem está logado nesta aba (e se a sessão ainda está sendo conferida) */
export function useSessao(): EstadoDaSessao {
    return useSyncExternalStore(assinarSessao, estadoDaSessao);
}

/** Recebe cada evento ao vivo enquanto a tela estiver aberta */
export function useAoVivo(ouvinte: (evento: EventoAoVivo) => void): void {
    const atual = useRef(ouvinte);
    useEffect(() => {
        atual.current = ouvinte;
    });
    useEffect(() => aoVivo().ouvir((evento) => atual.current(evento)), []);
}

export interface Dados<T> {
    dados: T | null;
    erro: string | null;
    /** O status HTTP do erro (404, 422...), ou 0 sem conexão; null sem erro */
    statusDoErro: number | null;
    carregando: boolean;
    /** Busca de novo (depois de uma ação da própria tela, por exemplo) */
    recarregar: () => void;
}

/**
 * Busca com `carregar` e busca de novo quando chega um evento ao vivo de um dos `tipos` (e no
 * "pronto", que vem a cada conexão). Enquanto recarrega, os dados antigos continuam na tela: sem
 * piscar. Uma resposta atrasada não passa por cima de uma mais nova.
 *
 *   const { dados: contas } = useDados(() => api<Conta[]>('/contas'), ['carteira']);
 */
export function useDados<T>(
    carregar: (sinal: AbortSignal) => Promise<T>,
    tipos: TipoDeEvento[] = [],
): Dados<T> {
    const [dados, setDados] = useState<T | null>(null);
    const [erro, setErro] = useState<string | null>(null);
    const [statusDoErro, setStatusDoErro] = useState<number | null>(null);
    const [carregando, setCarregando] = useState(true);
    const funcao = useRef(carregar);
    useEffect(() => {
        funcao.current = carregar;
    });
    const vez = useRef(0);
    const controle = useRef<AbortController | null>(null);

    const recarregar = useCallback(() => {
        const minha = ++vez.current;
        controle.current?.abort();
        const atual = new AbortController();
        controle.current = atual;
        setCarregando(true);
        funcao
            .current(atual.signal)
            .then((novos) => {
                if (minha !== vez.current) return;
                setDados(novos);
                setErro(null);
                setStatusDoErro(null);
            })
            .catch((e: unknown) => {
                if (
                    minha !== vez.current ||
                    (e instanceof DOMException && e.name === 'AbortError')
                )
                    return;
                setErro(mensagemDe(e));
                setStatusDoErro(e instanceof ErroDaApi ? e.status : null);
            })
            .finally(() => {
                if (minha === vez.current) setCarregando(false);
            });
    }, []);

    useEffect(() => {
        recarregar();
        return () => controle.current?.abort();
    }, [recarregar]);

    const chave = tipos.join(',');
    useAoVivo((evento) => {
        if (evento.tipo === 'pronto' || chave.split(',').includes(evento.tipo))
            recarregar();
    });

    return { dados, erro, statusDoErro, carregando, recarregar };
}
