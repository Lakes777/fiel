import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CobrancaDoLojista, EstornoFeito } from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { eventosFalsos } from '../teste/preparar';
import { BOB, logarComo, renderNaRota } from '../teste/render';
import { haMinutos, umaCobranca } from './amostras';
import { Loja } from './Loja';

function abrir(caminho = '/loja/cobrancas/132') {
    logarComo(BOB);
    return renderNaRota(<Loja />, { rota: '/loja/*', caminho });
}

function umEstorno(parcial: Partial<EstornoFeito> = {}): EstornoFeito {
    return {
        id: 1,
        cobrancaId: 132,
        valorCentavos: 1000,
        motivo: 'Lascada',
        em: haMinutos(0),
        ...parcial,
    };
}

describe('Detalhe da cobrança', () => {
    it('mostra o QR e copia o código; o link abre como quem paga', async () => {
        const usuario = userEvent.setup();
        const c = umaCobranca();
        apiFalsa({ 'GET /cobrancas/:id': { corpo: c } });
        abrir();

        expect(
            await screen.findByRole('img', { name: 'QR da cobrança' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('heading', { name: 'Cobrança #132' }),
        ).toBeInTheDocument();
        expect(screen.getByText('R$ 34,00')).toHaveClass('numero-grande');
        expect(screen.getByText('Caneca esmaltada')).toBeInTheDocument();
        expect(screen.getByText(/^em 2\d min$/)).toBeInTheDocument();

        await usuario.click(
            screen.getByRole('button', { name: 'Copiar o código' }),
        );
        expect(await navigator.clipboard.readText()).toBe(c.qr);
        expect(await screen.findByText('Código copiado.')).toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: 'Abrir como quem paga' }),
        ).toHaveAttribute('href', `/pagar?qr=${encodeURIComponent(c.qr)}`);
    });

    it('quando a Ana paga com a tela aberta, o estado vira paga na hora', async () => {
        let atual = umaCobranca();
        apiFalsa({ 'GET /cobrancas/:id': () => ({ corpo: atual }) });
        abrir();
        await screen.findByRole('img', { name: 'QR da cobrança' });

        // Evento de outra cobrança: não busca esta de novo
        atual = { ...atual, estado: 'paga', pagaEm: haMinutos(0) };
        eventosFalsos().emitir('cobranca', { cobranca: 99 });
        eventosFalsos().emitir('cobranca', { cobranca: 132 });

        expect(
            await screen.findByText('Pagamento recebido agora'),
        ).toBeInTheDocument();
        const cabeca = screen.getByRole('heading', {
            name: 'Cobrança #132',
        }).parentElement!;
        expect(within(cabeca).getByText('paga')).toHaveClass('estado', 'paga');
        expect(
            screen.getByText(
                'Pagamento recebido: R$ 34,00. A cobrança está paga.',
            ),
        ).toBeInTheDocument();
        // O QR fica, esmaecido sob o carimbo; as ações de cobrar somem e a de estornar aparece
        expect(
            screen.getByRole('img', { name: 'QR da cobrança' }),
        ).toBeVisible();
        expect(
            screen.queryByRole('button', { name: 'Cancelar cobrança' }),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: 'Estornar' }),
        ).toBeInTheDocument();
    });

    it('abrir uma já paga não mostra QR nem o carimbo', async () => {
        apiFalsa({
            'GET /cobrancas/:id': {
                corpo: umaCobranca({ estado: 'paga', pagaEm: haMinutos(10) }),
            },
        });
        abrir();
        expect(
            await screen.findByText('O dinheiro já está na sua carteira.'),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('img', { name: 'QR da cobrança' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByText('Pagamento recebido agora'),
        ).not.toBeInTheDocument();
    });

    it('cancela depois de confirmar, com o foco no caminho seguro', async () => {
        let atual = umaCobranca();
        const falsa = apiFalsa({
            'GET /cobrancas/:id': () => ({ corpo: atual }),
            'POST /cobrancas/:id/cancelamento': () => {
                atual = { ...atual, estado: 'cancelada' };
                return { corpo: atual };
            },
        });
        abrir();
        const cancelar = await screen.findByRole('button', {
            name: 'Cancelar cobrança',
        });

        await userEvent.click(cancelar);
        const confirmacao = screen.getByRole('alertdialog', {
            name: 'Cancelar esta cobrança?',
        });
        expect(
            within(confirmacao).getByRole('button', { name: 'Voltar' }),
        ).toHaveFocus();

        // Voltar não cancela e devolve o foco ao botão
        await userEvent.keyboard('{Escape}');
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
        expect(cancelar).toHaveFocus();
        expect(falsa.pedidos('POST /cobrancas/:id/cancelamento')).toHaveLength(
            0,
        );

        await userEvent.click(cancelar);
        await userEvent.click(
            screen.getByRole('button', { name: 'Sim, cancelar' }),
        );
        expect(
            await screen.findByText('Cobrança cancelada. O QR não vale mais.', {
                selector: '[role="status"]',
            }),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /cobrancas/:id/cancelamento')).toHaveLength(
            1,
        );
        expect(
            falsa.pedidos('POST /cobrancas/:id/cancelamento')[0].caminho,
        ).toBe('/cobrancas/132/cancelamento');
        await waitFor(() =>
            expect(
                screen.queryByRole('button', { name: 'Cancelar cobrança' }),
            ).not.toBeInTheDocument(),
        );
    });

    it('estorna: começa no que falta, barra valor acima e reaproveita a chave só depois de erro de rede', async () => {
        let atual: CobrancaDoLojista = umaCobranca({
            estado: 'paga',
            pagaEm: haMinutos(10),
        });
        let estornos: EstornoFeito[] = [];
        let quedas = 1;
        const falsa = apiFalsa({
            'GET /cobrancas/:id': () => ({ corpo: atual }),
            'GET /cobrancas/:id/estornos': () => ({ corpo: estornos }),
            'POST /cobrancas/:id/estornos': (p) => {
                if (quedas-- > 0) throw new TypeError('Failed to fetch');
                const { valorCentavos, motivo } = p.corpo as {
                    valorCentavos: number;
                    motivo: string | null;
                };
                const feito = umEstorno({
                    id: estornos.length + 1,
                    valorCentavos,
                    motivo,
                });
                estornos = [...estornos, feito];
                const total = atual.estornadoCentavos + valorCentavos;
                atual = {
                    ...atual,
                    estornadoCentavos: total,
                    estado:
                        total === atual.valorCentavos
                            ? 'estornada'
                            : 'parcialmente_estornada',
                };
                return { status: 201, corpo: feito };
            },
        });
        abrir();
        await userEvent.click(
            await screen.findByRole('button', { name: 'Estornar' }),
        );

        const valor = screen.getByLabelText('Valor a devolver');
        expect(valor).toHaveValue('34,00');
        expect(valor).toHaveFocus();

        await userEvent.clear(valor);
        await userEvent.type(valor, '40,00');
        await userEvent.click(
            screen.getByRole('button', { name: 'Confirmar estorno' }),
        );
        expect(
            screen.getByText('Só falta devolver R$ 34,00.'),
        ).toBeInTheDocument();
        expect(valor).toHaveAttribute('aria-invalid', 'true');
        expect(falsa.pedidos('POST /cobrancas/:id/estornos')).toHaveLength(0);

        await userEvent.clear(valor);
        await userEvent.type(valor, '10,00');
        await userEvent.type(
            screen.getByLabelText('Motivo (opcional)'),
            'Lascada',
        );
        await userEvent.click(
            screen.getByRole('button', { name: 'Confirmar estorno' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Sem conexão com o servidor',
        );

        // De novo, o mesmo pedido: a mesma chave, e o servidor não estorna duas vezes
        await userEvent.click(
            screen.getByRole('button', { name: 'Confirmar estorno' }),
        );
        expect(
            await screen.findByText(
                'Estorno de R$ 10,00 feito. O dinheiro voltou para quem pagou.',
            ),
        ).toBeInTheDocument();
        const [primeiro, segundo] = falsa.pedidos(
            'POST /cobrancas/:id/estornos',
        );
        expect(primeiro.cabecalhos['Idempotency-Key']).toBeTruthy();
        expect(segundo.cabecalhos['Idempotency-Key']).toBe(
            primeiro.cabecalhos['Idempotency-Key'],
        );
        expect(segundo.corpo).toEqual({
            valorCentavos: 1000,
            motivo: 'Lascada',
        });

        // A lista de estornos aparece, e o próximo começa no que falta (R$ 24,00), com chave nova
        const lista = await screen.findByRole('region', { name: 'Estornos' });
        expect(within(lista).getByText('Lascada')).toBeInTheDocument();
        expect(within(lista).getByText('−R$ 10,00')).toBeInTheDocument();
        await userEvent.click(
            await screen.findByRole('button', { name: 'Estornar' }),
        );
        expect(screen.getByLabelText('Valor a devolver')).toHaveValue('24,00');
        await userEvent.click(
            screen.getByRole('button', { name: 'Confirmar estorno' }),
        );
        await waitFor(() =>
            expect(falsa.pedidos('POST /cobrancas/:id/estornos')).toHaveLength(
                3,
            ),
        );
        const terceiro = falsa.pedidos('POST /cobrancas/:id/estornos')[2];
        expect(terceiro.cabecalhos['Idempotency-Key']).not.toBe(
            primeiro.cabecalhos['Idempotency-Key'],
        );
        expect(terceiro.corpo).toEqual({ valorCentavos: 2400, motivo: null });
        // Tudo devolvido: não há mais o que estornar
        await waitFor(() =>
            expect(
                screen.queryByRole('button', { name: 'Estornar' }),
            ).not.toBeInTheDocument(),
        );
        expect(
            screen.getByText('Todo o valor voltou para quem pagou.'),
        ).toBeInTheDocument();
    });

    it('cobrança que não existe (ou de outra loja)', async () => {
        apiFalsa({});
        abrir('/loja/cobrancas/999');
        expect(
            await screen.findByText(
                'Esta cobrança não existe, ou é de outra loja.',
            ),
        ).toBeInTheDocument();
    });
});
