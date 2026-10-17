import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CobrancaParaPagar, PagamentoFeito } from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo, renderNaRota } from '../teste/render';
import { Pagar } from './Pagar';

const QR =
    '0004FIEL010112636' +
    '3f2a9c1e-7b4d-4e8a-9f60-1c2d3e4f5a6b54042550590' +
    '6Bob L.6304ABCD';

function cobranca(mudar: Partial<CobrancaParaPagar> = {}): CobrancaParaPagar {
    return {
        valorCentavos: 2550,
        descricao: 'Caneca de cerâmica',
        recebedor: 'Bob L.',
        estado: 'pendente',
        expiraEm: new Date(Date.now() + 12 * 60_000).toISOString(),
        ...mudar,
    };
}

const PAGO: PagamentoFeito = {
    valorCentavos: 2550,
    descricao: 'Caneca de cerâmica',
    recebedor: 'Bob L.',
    pagaEm: new Date().toISOString(),
};

function abrir(rotas: Parameters<typeof apiFalsa>[0], caminho = '/pagar') {
    const falsa = apiFalsa(rotas);
    logarComo(ANA);
    renderNaRota(<Pagar />, { rota: '/pagar', caminho });
    return falsa;
}

describe('Pagar com QR', () => {
    it('com ?qr= no endereço, já preenche e confere', async () => {
        const falsa = abrir(
            { 'POST /pagamentos/consulta': { corpo: cobranca() } },
            `/pagar?qr=${encodeURIComponent(QR)}`,
        );
        expect(screen.getByLabelText('Cole o código do QR')).toHaveValue(QR);
        expect(await screen.findByText('Bob L.')).toBeInTheDocument();
        expect(screen.getByText('Caneca de cerâmica')).toBeInTheDocument();
        expect(screen.getByText('R$ 25,50')).toBeInTheDocument();
        expect(screen.getByText(/vence em 1[12] min/)).toBeInTheDocument();
        expect(falsa.pedidos('POST /pagamentos/consulta')[0].corpo).toEqual({
            qr: QR,
        });
    });

    it('cola, confere e paga, e mostra o comprovante', async () => {
        const falsa = abrir({
            'POST /pagamentos/consulta': { corpo: cobranca() },
            'POST /pagamentos': { status: 201, corpo: PAGO },
        });
        const usuario = userEvent.setup();
        await usuario.click(screen.getByLabelText('Cole o código do QR'));
        await usuario.paste(QR);
        await usuario.click(screen.getByRole('button', { name: 'Conferir' }));
        await usuario.click(
            await screen.findByRole('button', { name: 'Pagar R$ 25,50' }),
        );
        const comprovante = await screen.findByRole('status');
        expect(within(comprovante).getByText('Pago')).toBeInTheDocument();
        expect(within(comprovante).getByText('R$ 25,50')).toBeInTheDocument();
        expect(within(comprovante).getByText('Bob L.')).toBeInTheDocument();
        expect(
            within(comprovante).getByRole('link', { name: 'Ver a carteira' }),
        ).toHaveAttribute('href', '/carteira');
        expect(falsa.pedidos('POST /pagamentos')[0].corpo).toEqual({ qr: QR });
    });

    it('cobrança que não está pendente não deixa pagar', async () => {
        abrir(
            {
                'POST /pagamentos/consulta': {
                    corpo: cobranca({ estado: 'paga' }),
                },
            },
            `/pagar?qr=${encodeURIComponent(QR)}`,
        );
        expect(
            await screen.findByText('Esta cobrança já foi paga.'),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: /^Pagar/ }),
        ).not.toBeInTheDocument();
    });

    it('cobrança vencida (mesmo ainda pendente no servidor) não deixa pagar', async () => {
        abrir(
            {
                'POST /pagamentos/consulta': {
                    corpo: cobranca({
                        expiraEm: new Date(Date.now() - 60_000).toISOString(),
                    }),
                },
            },
            `/pagar?qr=${encodeURIComponent(QR)}`,
        );
        expect(
            await screen.findByText(/Esta cobrança venceu/),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: /^Pagar/ }),
        ).not.toBeInTheDocument();
    });

    it('409: outra pessoa pagou antes', async () => {
        abrir(
            {
                'POST /pagamentos/consulta': { corpo: cobranca() },
                'POST /pagamentos': { status: 409 },
            },
            `/pagar?qr=${encodeURIComponent(QR)}`,
        );
        const usuario = userEvent.setup();
        await usuario.click(
            await screen.findByRole('button', { name: 'Pagar R$ 25,50' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Outra pessoa pagou esta cobrança antes de você.',
        );
        expect(
            screen.queryByRole('button', { name: /^Pagar/ }),
        ).not.toBeInTheDocument();
    });

    it('422: mostra a mensagem da API', async () => {
        abrir(
            {
                'POST /pagamentos/consulta': { corpo: cobranca() },
                'POST /pagamentos': {
                    status: 422,
                    corpo: { detail: 'saldo insuficiente' },
                },
            },
            `/pagar?qr=${encodeURIComponent(QR)}`,
        );
        const usuario = userEvent.setup();
        await usuario.click(
            await screen.findByRole('button', { name: 'Pagar R$ 25,50' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Saldo insuficiente.',
        );
    });

    it('barra texto que não é de um QR do Fiel sem chamar a API', async () => {
        const falsa = abrir({});
        const usuario = userEvent.setup();
        await usuario.type(
            screen.getByLabelText('Cole o código do QR'),
            'qualquer coisa',
        );
        await usuario.click(screen.getByRole('button', { name: 'Conferir' }));
        expect(screen.getByRole('alert')).toHaveTextContent(
            'não é de uma cobrança do Fiel',
        );
        expect(falsa.pedidos()).toHaveLength(0);
    });
});
