import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Aviso, Webhook } from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { eventosFalsos } from '../teste/preparar';
import { BOB, logarComo, renderNaRota } from '../teste/render';
import { daquiA, umAviso } from './amostras';
import { Loja } from './Loja';

function abrir() {
    logarComo(BOB);
    return renderNaRota(<Loja />, {
        rota: '/loja/*',
        caminho: '/loja/avisos',
    });
}

const ENDERECO = 'https://loja-do-bob.com.br/fiel';

describe('Avisos', () => {
    it('cadastra o endereço e mostra o segredo uma vez só', async () => {
        const usuario = userEvent.setup();
        let webhook: Webhook | null = null;
        const falsa = apiFalsa({
            'GET /webhook': () =>
                webhook ? { corpo: webhook } : { status: 404 },
            'PUT /webhook': (p) => {
                webhook = { url: (p.corpo as Webhook).url };
                return { corpo: { ...webhook, segredo: 'whsec_9f2c41ab' } };
            },
            'GET /avisos': { corpo: [] },
        });
        abrir();
        expect(
            await screen.findByText(/Nenhum endereço cadastrado/),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: 'exemplos/lojista.py' }),
        ).toHaveAttribute(
            'href',
            expect.stringContaining('exemplos/lojista.py'),
        );

        const campo = screen.getByLabelText('Endereço (https)');
        await usuario.type(campo, 'http://loja.com/fiel');
        await usuario.click(
            screen.getByRole('button', { name: 'Cadastrar endereço' }),
        );
        expect(
            screen.getByText('O endereço precisa começar com https://.'),
        ).toBeVisible();
        expect(falsa.pedidos('PUT /webhook')).toHaveLength(0);

        await usuario.clear(campo);
        await usuario.type(campo, ENDERECO);
        await usuario.click(
            screen.getByRole('button', { name: 'Cadastrar endereço' }),
        );

        const segredo = await screen.findByRole('region', {
            name: 'Segredo da assinatura',
        });
        await waitFor(() => expect(segredo).toHaveFocus());
        expect(within(segredo).getByText('whsec_9f2c41ab')).toBeVisible();
        expect(
            within(segredo).getByText(/ele não aparece de novo/),
        ).toBeVisible();
        await usuario.click(
            within(segredo).getByRole('button', { name: 'Copiar o segredo' }),
        );
        expect(await navigator.clipboard.readText()).toBe('whsec_9f2c41ab');
        expect(
            await within(segredo).findByText('Segredo copiado.'),
        ).toBeInTheDocument();

        // O endereço atual aparece, e o formulário vira "trocar"
        expect(await screen.findByText(ENDERECO)).toBeInTheDocument();
        expect(screen.getByLabelText('Trocar o endereço')).toBeInTheDocument();

        await usuario.click(
            within(segredo).getByRole('button', { name: 'Já guardei' }),
        );
        expect(screen.queryByText('whsec_9f2c41ab')).not.toBeInTheDocument();
    });

    it('mostra a mensagem da API para endereço que não é público', async () => {
        apiFalsa({
            'GET /webhook': { status: 404 },
            'PUT /webhook': {
                status: 422,
                corpo: {
                    detail: 'o endereço precisa apontar para um IP público',
                },
            },
            'GET /avisos': { corpo: [] },
        });
        abrir();
        await userEvent.type(
            await screen.findByLabelText('Endereço (https)'),
            'https://192.168.0.10/fiel',
        );
        await userEvent.click(
            screen.getByRole('button', { name: 'Cadastrar endereço' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'O endereço precisa apontar para um IP público.',
        );
        expect(
            screen.queryByRole('region', { name: 'Segredo da assinatura' }),
        ).not.toBeInTheDocument();
    });

    it('apaga o endereço depois de confirmar', async () => {
        let webhook: Webhook | null = { url: ENDERECO };
        const falsa = apiFalsa({
            'GET /webhook': () =>
                webhook ? { corpo: webhook } : { status: 404 },
            'DELETE /webhook': () => {
                webhook = null;
                return { status: 204 };
            },
            'GET /avisos': { corpo: [] },
        });
        abrir();
        await screen.findByText(ENDERECO);

        await userEvent.click(screen.getByRole('button', { name: 'Apagar' }));
        expect(
            screen.getByRole('alertdialog', { name: 'Apagar o endereço?' }),
        ).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Voltar' })).toHaveFocus();
        await userEvent.click(
            screen.getByRole('button', { name: 'Sim, apagar' }),
        );

        expect(await screen.findByText(/Endereço apagado/)).toBeInTheDocument();
        expect(falsa.pedidos('DELETE /webhook')).toHaveLength(1);
        expect(
            await screen.findByText(/Nenhum endereço cadastrado/),
        ).toBeInTheDocument();
    });

    it('filtra o histórico, reenvia e atualiza ao vivo', async () => {
        let avisos: Aviso[] = [
            umAviso({
                id: 9,
                estado: 'pendente',
                tentativas: 2,
                entregueEm: null,
                ultimaResposta: 'HTTP 500',
                proximaTentativaEm: daquiA(2),
            }),
            umAviso({
                id: 8,
                estado: 'falhou',
                tipo: 'cobranca.estornada',
                tentativas: 8,
                entregueEm: null,
                ultimaResposta: 'sem resposta em 15 s',
            }),
            umAviso({ id: 7 }),
        ];
        const falsa = apiFalsa({
            'GET /webhook': { corpo: { url: ENDERECO } },
            'GET /avisos': (p) => {
                const estado = new URLSearchParams(p.caminho.split('?')[1]).get(
                    'estado',
                );
                return {
                    corpo: estado
                        ? avisos.filter((a) => a.estado === estado)
                        : avisos,
                };
            },
            'POST /avisos/:id/reenvio': () => {
                avisos = avisos.map((a) =>
                    a.id === 8
                        ? { ...a, estado: 'pendente', tentativas: 0 }
                        : a,
                );
                return { corpo: avisos.find((a) => a.id === 8) };
            },
        });
        abrir();

        const lista = await screen.findByRole('list', { name: 'Avisos' });
        expect(within(lista).getAllByRole('listitem')).toHaveLength(3);
        expect(within(lista).getByText('na fila')).toHaveClass(
            'estado',
            'pendente',
        );
        expect(within(lista).getByText('2 tentativas')).toBeInTheDocument();
        expect(
            within(lista).getByText('última resposta: HTTP 500'),
        ).toBeInTheDocument();
        expect(
            within(lista).getByText(/^próxima tentativa em [12] min$/),
        ).toBeInTheDocument();
        // O que está na fila não tem reenviar: ele já vai sair
        expect(
            within(lista).queryByRole('button', {
                name: 'Reenviar o aviso #9',
            }),
        ).not.toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: 'Falharam' }));
        await waitFor(() =>
            expect(
                screen.getAllByRole('listitem').map((li) => li.textContent),
            ).toEqual([expect.stringContaining('Cobrança estornada')]),
        );
        expect(falsa.pedidos('GET /avisos').at(-1)!.caminho).toBe(
            '/avisos?estado=falhou',
        );

        await userEvent.click(
            screen.getByRole('button', { name: 'Reenviar o aviso #8' }),
        );
        expect(
            await screen.findByText(/Aviso #8 voltou para a fila/),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /avisos/:id/reenvio')[0].caminho).toBe(
            '/avisos/8/reenvio',
        );
        // Saiu da fila de falhas
        await waitFor(() =>
            expect(
                screen.getByText('Nenhum aviso com este filtro.'),
            ).toBeVisible(),
        );

        // Ao vivo: a entrega terminou e falhou de novo
        avisos = avisos.map((a) =>
            a.id === 8 ? { ...a, estado: 'falhou', tentativas: 1 } : a,
        );
        eventosFalsos().emitir('avisos');
        expect(
            await screen.findByRole('button', { name: 'Reenviar o aviso #8' }),
        ).toBeInTheDocument();
        expect(screen.getByText('1 tentativa')).toBeInTheDocument();
    });
});
