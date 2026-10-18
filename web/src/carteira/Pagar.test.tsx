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
            await screen.findByRole('button', { name: 'Pagar R$\u00a025,50' }),
        );
        const comprovante = await screen.findByRole('status');
        expect(within(comprovante).getByText('Pago')).toBeInTheDocument();
        expect(within(comprovante).getByText('R$ 25,50')).toBeInTheDocument();
        expect(within(comprovante).getByText('Bob L.')).toBeInTheDocument();
        expect(
            within(comprovante).getByRole('link', { name: 'Ver a carteira' }),
        ).toHaveAttribute('href', '/carteira');
        // Um caminho só para a carteira: o link do rodapé sai com o comprovante
        expect(
            screen.queryByRole('link', { name: 'Voltar para a carteira' }),
        ).not.toBeInTheDocument();
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

    it.each([
        ['paga', 'Esta cobrança já foi paga.'],
        ['cancelada', 'Quem cobrou cancelou esta cobrança.'],
    ] as const)(
        '409: a cobrança virou %s depois de conferida, e a tela mostra o estado de verdade',
        async (estado, motivo) => {
            let consultas = 0;
            const falsa = abrir(
                {
                    'POST /pagamentos/consulta': () => ({
                        corpo: cobranca(consultas++ ? { estado } : {}),
                    }),
                    'POST /pagamentos': {
                        status: 409,
                        corpo: { detail: `esta cobrança está ${estado}` },
                    },
                },
                `/pagar?qr=${encodeURIComponent(QR)}`,
            );
            const usuario = userEvent.setup();
            await usuario.click(
                await screen.findByRole('button', {
                    name: 'Pagar R$\u00a025,50',
                }),
            );
            expect(await screen.findByRole('alert')).toHaveTextContent(
                'A cobrança mudou enquanto você conferia. Nada foi pago.',
            );
            expect(await screen.findByText(motivo)).toBeInTheDocument();
            expect(
                screen.queryByRole('button', { name: /^Pagar/ }),
            ).not.toBeInTheDocument();
            expect(falsa.pedidos('POST /pagamentos/consulta')).toHaveLength(2);
        },
    );

    it('enquanto confere, o QR não muda, e paga o QR que foi conferido', async () => {
        let soltarA: () => void = () => {};
        const falsa = abrir({
            'POST /pagamentos/consulta': (p) =>
                (p.corpo as { qr: string }).qr === QR
                    ? new Promise((pronto) => {
                          soltarA = () => pronto({ corpo: cobranca() });
                      })
                    : {
                          corpo: cobranca({
                              recebedor: 'Eva M.',
                              valorCentavos: 9900,
                          }),
                      },
            'POST /pagamentos': { status: 201, corpo: PAGO },
        });
        const usuario = userEvent.setup();
        const campo = screen.getByLabelText('Cole o código do QR');
        await usuario.click(campo);
        await usuario.paste(QR);
        await usuario.click(screen.getByRole('button', { name: 'Conferir' }));
        // Enquanto confere, o campo não muda
        expect(campo).toHaveAttribute('readonly');
        soltarA();
        await usuario.click(
            await screen.findByRole('button', { name: 'Pagar R$\u00a025,50' }),
        );
        expect(falsa.pedidos('POST /pagamentos')[0].corpo).toEqual({ qr: QR });
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
            await screen.findByRole('button', { name: 'Pagar R$\u00a025,50' }),
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
