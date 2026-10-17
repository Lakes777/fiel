import { BASE } from './cliente';
import { carregarSessao } from './sessao';

/**
 * "pronto" chega ao abrir (e a cada reconexão): é a hora de buscar tudo de novo, porque algo pode
 * ter mudado enquanto a conexão estava fechada. Os outros dizem o que mudou.
 */
export type TipoDeEvento = 'pronto' | 'carteira' | 'cobranca' | 'avisos';

export interface EventoAoVivo {
    tipo: TipoDeEvento;
    /** Em "cobranca": qual mudou */
    cobranca?: number;
}

export type Ouvinte = (evento: EventoAoVivo) => void;

const TIPOS: TipoDeEvento[] = ['pronto', 'carteira', 'cobranca', 'avisos'];

/** O mínimo do EventSource que usamos (os testes trocam por um falso) */
export interface FonteDeEventos {
    addEventListener(
        tipo: string,
        ouvinte: (e: MessageEvent<string>) => void,
    ): void;
    close(): void;
    readyState: number;
    onerror: ((e: Event) => void) | null;
}

type Fabrica = (url: string) => FonteDeEventos;
const fabricaPadrao: Fabrica = (url) =>
    new EventSource(url) as unknown as FonteDeEventos;
let fabrica: Fabrica = fabricaPadrao;

/**
 * Uma conexão GET /api/eventos por aba, aberta só enquanto alguma tela escuta. O próprio
 * EventSource reconecta se a rede cair; se o servidor recusar (sessão acabou), ele desiste
 * (readyState 2), e a sessão é conferida de novo, o que leva a tela para o login.
 */
class CanalAoVivo {
    private fonte: FonteDeEventos | null = null;
    private readonly ouvintes = new Set<Ouvinte>();

    ouvir(ouvinte: Ouvinte): () => void {
        this.ouvintes.add(ouvinte);
        this.abrir();
        return () => {
            this.ouvintes.delete(ouvinte);
            if (this.ouvintes.size === 0) this.fechar();
        };
    }

    private abrir(): void {
        if (this.fonte) return;
        const fonte = fabrica(`${BASE}/eventos`);
        for (const tipo of TIPOS) {
            fonte.addEventListener(tipo, (e) => this.entregar(tipo, e.data));
        }
        fonte.onerror = () => {
            if (fonte.readyState === 2) {
                this.fonte = null;
                void carregarSessaoDeNovo();
            }
        };
        this.fonte = fonte;
    }

    private fechar(): void {
        this.fonte?.close();
        this.fonte = null;
    }

    private entregar(tipo: TipoDeEvento, dados: string): void {
        let cobranca: number | undefined;
        try {
            cobranca = (JSON.parse(dados) as { cobranca?: number }).cobranca;
        } catch {
            // sem dados: tudo bem
        }
        const evento: EventoAoVivo =
            cobranca === undefined ? { tipo } : { tipo, cobranca };
        this.ouvintes.forEach((o) => o(evento));
    }
}

async function carregarSessaoDeNovo(): Promise<void> {
    const { reiniciarConsultaDaSessao } = await import('./sessao');
    reiniciarConsultaDaSessao();
    await carregarSessao();
}

let canal: CanalAoVivo | null = null;

export function aoVivo(): CanalAoVivo {
    canal ??= new CanalAoVivo();
    return canal;
}

/** Só para os testes: troca o EventSource por um falso (null volta ao de verdade) */
export function trocarEventosParaTeste(nova: Fabrica | null): void {
    canal = null;
    fabrica = nova ?? fabricaPadrao;
}
