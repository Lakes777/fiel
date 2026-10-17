import type { FonteDeEventos } from '../api/eventos';

/**
 * Um EventSource de mentira: o teste chama `emitir("carteira")` e as telas recebem como se viesse
 * do servidor. `abertas` guarda as conexões criadas (para conferir que a tela abriu e fechou).
 */
export class EventosFalsos {
    readonly abertas: FonteFalsa[] = [];

    fabrica = (url: string): FonteDeEventos => {
        const fonte = new FonteFalsa(url);
        this.abertas.push(fonte);
        return fonte;
    };

    /** Manda o evento para todas as conexões abertas */
    emitir(tipo: string, dados: object = {}): void {
        this.abertas
            .filter((f) => f.readyState !== 2)
            .forEach((f) => f.disparar(tipo, JSON.stringify(dados)));
    }
}

export class FonteFalsa implements FonteDeEventos {
    readyState = 1;
    onerror: ((e: Event) => void) | null = null;
    private readonly ouvintes = new Map<
        string,
        ((e: MessageEvent<string>) => void)[]
    >();

    constructor(readonly url: string) {}

    addEventListener(
        tipo: string,
        ouvinte: (e: MessageEvent<string>) => void,
    ): void {
        this.ouvintes.set(tipo, [...(this.ouvintes.get(tipo) ?? []), ouvinte]);
    }

    close(): void {
        this.readyState = 2;
    }

    disparar(tipo: string, dados: string): void {
        this.ouvintes
            .get(tipo)
            ?.forEach((o) => o(new MessageEvent(tipo, { data: dados })));
    }

    /** O servidor recusou (sessão acabou): o EventSource desiste */
    recusar(): void {
        this.readyState = 2;
        this.onerror?.(new Event('error'));
    }
}
