import { vi } from 'vitest';

export interface PedidoFeito {
    metodo: string;
    caminho: string;
    corpo: unknown;
    cabecalhos: Record<string, string>;
}

export type Resposta = { status?: number; corpo?: unknown };
export type Tratador = (pedido: PedidoFeito) => Resposta | Promise<Resposta>;

/**
 * Troca o fetch por uma API falsa (as rotas sem o /api). As rotas são "METODO /caminho" (sem a busca ?...) ou com
 * parâmetros (":id"); a resposta pode ser fixa ou uma função do pedido. Rota sem tratador
 * responde 404 e aparece em `pedidos`, para o teste conferir.
 *
 *   const falsa = apiFalsa({
 *     'GET /contas': { corpo: [conta] },
 *     'POST /cobrancas/:id/estornos': (p) => ({ status: 201, corpo: estorno }),
 *   });
 *   expect(falsa.pedidos('POST /cobrancas/:id/estornos')).toHaveLength(1);
 */
export function apiFalsa(rotas: Record<string, Resposta | Tratador>) {
    const feitos: PedidoFeito[] = [];
    const padroes = Object.entries(rotas).map(([chave, tratador]) => {
        const [metodo, molde] = chave.split(' ');
        const regex = new RegExp(
            `^${molde.replace(/:[^/]+/g, '[^/]+').replace(/\//g, '\\/')}$`,
        );
        return { chave, metodo, regex, tratador };
    });

    const fetchFalso = vi.fn(
        async (entrada: RequestInfo | URL, init?: RequestInit) => {
            // As rotas do teste são as da API, sem o /api do proxy
            const url = new URL(
                String(entrada).replace(/^\/api/, ''),
                'http://localhost',
            );
            const metodo = (init?.method ?? 'GET').toUpperCase();
            const pedido: PedidoFeito = {
                metodo,
                caminho: url.pathname + url.search,
                corpo:
                    typeof init?.body === 'string'
                        ? JSON.parse(init.body)
                        : undefined,
                cabecalhos: { ...(init?.headers as Record<string, string>) },
            };
            feitos.push(pedido);
            const achado = padroes.find(
                (p) => p.metodo === metodo && p.regex.test(url.pathname),
            );
            if (!achado) return new Response(null, { status: 404 });
            const { status = 200, corpo } =
                typeof achado.tratador === 'function'
                    ? await achado.tratador(pedido)
                    : achado.tratador;
            return new Response(
                corpo === undefined ? null : JSON.stringify(corpo),
                {
                    status,
                    headers: { 'Content-Type': 'application/json' },
                },
            );
        },
    );
    vi.stubGlobal('fetch', fetchFalso);

    return {
        fetch: fetchFalso,
        /** Os pedidos feitos (todos, ou só os de uma rota, como escrita em `rotas`) */
        pedidos(chave?: string): PedidoFeito[] {
            if (!chave) return feitos;
            const padrao = padroes.find((p) => p.chave === chave);
            return feitos.filter(
                (f) =>
                    padrao &&
                    f.metodo === padrao.metodo &&
                    padrao.regex.test(f.caminho.split('?')[0]),
            );
        },
    };
}
