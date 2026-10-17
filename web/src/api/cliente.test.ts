import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo } from '../teste/render';
import { api, ErroDaApi, mensagemDe } from './cliente';
import { estadoDaSessao } from './sessao';

describe('api', () => {
    it('manda o token CSRF e a Idempotency-Key em quem muda algo, e não em GET', async () => {
        const falsa = apiFalsa({
            'GET /contas': { corpo: [] },
            'POST /transferencias': { status: 201, corpo: { id: 1 } },
        });
        await api('/contas');
        await api('/transferencias', {
            metodo: 'POST',
            corpo: { chave: 'x', valorCentavos: 1 },
            idempotencia: 'k-1',
        });

        const [get, post] = falsa.pedidos();
        expect(get.cabecalhos['X-XSRF-TOKEN']).toBeUndefined();
        expect(post.cabecalhos['X-XSRF-TOKEN']).toBe('token-de-teste');
        expect(post.cabecalhos['Idempotency-Key']).toBe('k-1');
        expect(post.corpo).toEqual({ chave: 'x', valorCentavos: 1 });
        expect(falsa.fetch.mock.calls[0][0]).toBe('/api/contas');
    });

    it('sem o cookie do CSRF, busca em /csrf antes', async () => {
        document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
        const falsa = apiFalsa({
            'GET /csrf': {
                corpo: { cabecalho: 'X-XSRF-TOKEN', token: 'novo' },
            },
            'DELETE /sessao': { status: 204 },
        });
        await api('/sessao', { metodo: 'DELETE' });

        expect(falsa.pedidos().map((p) => `${p.metodo} ${p.caminho}`)).toEqual([
            'GET /csrf',
            'DELETE /sessao',
        ]);
        expect(
            falsa.pedidos('DELETE /sessao')[0].cabecalhos['X-XSRF-TOKEN'],
        ).toBe('novo');
    });

    it('transforma o problem+json em frase, e troca a mensagem em inglês do Spring', async () => {
        apiFalsa({
            'POST /transferencias': {
                status: 422,
                corpo: { detail: 'saldo insuficiente: você tem R$ 5,00' },
            },
            'POST /cobrancas': {
                status: 400,
                corpo: { detail: 'Invalid request content.' },
            },
        });
        const regra = await api('/transferencias', {
            metodo: 'POST',
            corpo: {},
        }).catch((e: unknown) => e);
        const validacao = await api('/cobrancas', {
            metodo: 'POST',
            corpo: {},
        }).catch((e: unknown) => e);

        expect(regra).toBeInstanceOf(ErroDaApi);
        expect(mensagemDe(regra)).toBe('Saldo insuficiente: você tem R$ 5,00.');
        expect((regra as ErroDaApi).status).toBe(422);
        expect(mensagemDe(validacao)).toBe('Confira os dados e tente de novo.');
    });

    it('um 401 no meio do uso tira o login da aba', async () => {
        logarComo(ANA);
        apiFalsa({
            'GET /contas': {
                status: 401,
                corpo: { detail: 'faça login para continuar' },
            },
        });
        await expect(api('/contas')).rejects.toBeInstanceOf(ErroDaApi);
        expect(estadoDaSessao().usuario).toBeNull();
    });

    it('sem rede, a mensagem diz isso', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
        );
        await expect(api('/contas')).rejects.toThrow(
            'Sem conexão com o servidor. Tente de novo.',
        );
    });
});
