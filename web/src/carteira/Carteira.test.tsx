import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type {
    Chave,
    Conta,
    LinhaDoExtrato,
    Limites,
    TransferenciaFeita,
} from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { eventosFalsos } from '../teste/preparar';
import { ANA, logarComo, renderNaRota } from '../teste/render';
import { Exige } from '../comum/Exige';
import { Carteira } from './Carteira';

const CONTA: Conta = { id: 7, nome: 'Carteira', saldoCentavos: 100000 };
const LIMITES: Limites = {
    diaLimiteCentavos: 500000,
    diaUsadoCentavos: 0,
    noiteLimiteCentavos: 100000,
    noiteUsadoCentavos: 70000,
    ehNoite: true,
    disponivelCentavos: 30000,
};
const BONUS: LinhaDoExtrato = {
    lancamentoId: 1,
    descricao: 'Bônus de boas-vindas',
    em: '2026-10-15T21:02:00Z',
    valorCentavos: 100000,
    saldoDepoisCentavos: 100000,
};
const EMAIL: Chave = {
    id: 1,
    tipo: 'email',
    valor: 'ana@exemplo.com',
    criadaEm: '2026-10-15T21:02:00Z',
};
const FEITA: TransferenciaFeita = {
    id: 42,
    valorCentavos: 2550,
    para: 'Bob L.',
    em: new Date().toISOString(),
};

type Rotas = Parameters<typeof apiFalsa>[0];

function montar(extras: Rotas = {}, chaves: Chave[] = [EMAIL]) {
    const falsa = apiFalsa({
        'GET /contas': { corpo: [CONTA] },
        'GET /contas/:id/extrato': { corpo: [BONUS] },
        'GET /limites': { corpo: LIMITES },
        'GET /chaves': { corpo: chaves },
        'POST /chaves/consulta': { corpo: { nome: 'Bob L.', tipo: 'email' } },
        ...extras,
    });
    logarComo(ANA);
    renderNaRota(<Carteira />, { rota: '/carteira', caminho: '/carteira' });
    return falsa;
}

async function abrirTransferir() {
    const usuario = userEvent.setup();
    const botao = screen.getByRole('button', { name: 'Transferir' });
    await waitFor(() => expect(botao).toBeEnabled());
    await usuario.click(botao);
    return { usuario, botao, dialogo: screen.getByRole('dialog') };
}

/** Chave -> conferir o nome -> valor -> tela de confirmar */
async function ateConfirmar(
    usuario: ReturnType<typeof userEvent.setup>,
    valor = '25,50',
) {
    await usuario.type(
        screen.getByLabelText('Chave de quem vai receber'),
        'bob@exemplo.com',
    );
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
    await usuario.type(await screen.findByLabelText('Valor'), valor);
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
}

describe('Carteira', () => {
    it('mostra saldo, extrato e chaves, e busca de novo quando chega o evento carteira', async () => {
        // Pediu menos movimento: o saldo aparece direto, sem contar
        vi.stubGlobal('matchMedia', () => ({ matches: true }));
        let extrato = [BONUS];
        const falsa = montar({
            'GET /contas/:id/extrato': () => ({ corpo: extrato }),
        });
        expect(
            await screen.findByText('Bônus de boas-vindas'),
        ).toBeInTheDocument();
        // O saldo (o único número com aria-live da carteira); sem movimento, sem contagem
        await waitFor(() =>
            expect(
                document.querySelector('.saldo[aria-live]'),
            ).toHaveTextContent('R$ 1.000,00'),
        );
        expect(
            screen.getByText(/Saldo da carteira · Ana Souza/),
        ).toBeInTheDocument();
        expect(await screen.findByText('ana@exemplo.com')).toBeInTheDocument();
        expect(falsa.pedidos('GET /contas/:id/extrato')[0].caminho).toBe(
            '/contas/7/extrato?limite=50',
        );

        extrato = [
            {
                lancamentoId: 2,
                descricao: 'Transferência de Bob L. para Ana S.',
                em: new Date().toISOString(),
                valorCentavos: 5000,
                saldoDepoisCentavos: 105000,
            },
            BONUS,
        ];
        act(() => eventosFalsos().emitir('carteira'));
        const nova = await screen.findByText(
            'Transferência de Bob L. para Ana S.',
        );
        expect(nova.closest('li')).toHaveClass('entrar');
        expect(falsa.pedidos('GET /contas/:id/extrato')).toHaveLength(2);
    });

    it('tem os caminhos para pagar um QR e para cobrar', async () => {
        montar();
        expect(
            await screen.findByRole('link', { name: 'Pagar QR' }),
        ).toHaveAttribute('href', '/pagar');
        expect(screen.getByRole('link', { name: 'Cobrar' })).toHaveAttribute(
            'href',
            '/loja/nova',
        );
    });
});

describe('Transferir', () => {
    it('vai da chave ao comprovante, com a Idempotency-Key', async () => {
        const falsa = montar({
            'POST /transferencias': { status: 201, corpo: FEITA },
        });
        const { usuario } = await abrirTransferir();
        expect(
            screen.getByLabelText('Chave de quem vai receber'),
        ).toHaveFocus();

        await usuario.type(
            screen.getByLabelText('Chave de quem vai receber'),
            'bob@exemplo.com',
        );
        await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
        expect(await screen.findByText('Bob L.')).toBeInTheDocument();
        expect(falsa.pedidos('POST /chaves/consulta')[0].corpo).toEqual({
            valor: 'bob@exemplo.com',
        });
        expect(
            screen.getByText(/Você pode mandar até R\$ 300,00 agora/),
        ).toBeInTheDocument();

        await usuario.type(screen.getByLabelText('Valor'), '25,50');
        await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
        expect(screen.getByText('R$ 25,50')).toBeInTheDocument();
        await usuario.click(screen.getByRole('button', { name: 'Confirmar' }));

        const comprovante = await screen.findByRole('status');
        expect(within(comprovante).getByText('R$ 25,50')).toBeInTheDocument();
        expect(within(comprovante).getByText('Bob L.')).toBeInTheDocument();
        expect(within(comprovante).getByText('42')).toBeInTheDocument();
        const [pedido] = falsa.pedidos('POST /transferencias');
        expect(pedido.corpo).toEqual({
            chave: 'bob@exemplo.com',
            valorCentavos: 2550,
        });
        expect(pedido.cabecalhos['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('reenvia a MESMA Idempotency-Key depois de erro de rede, e uma nova depois do sucesso', async () => {
        let tentativa = 0;
        const falsa = montar({
            'POST /transferencias': () => {
                tentativa++;
                if (tentativa === 1) throw new TypeError('a rede caiu');
                return { status: 201, corpo: FEITA };
            },
        });
        const { usuario } = await abrirTransferir();
        await ateConfirmar(usuario);
        await usuario.click(screen.getByRole('button', { name: 'Confirmar' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Sem conexão com o servidor',
        );
        await usuario.click(
            screen.getByRole('button', { name: 'Tentar de novo' }),
        );
        await screen.findByRole('status');

        await usuario.click(
            screen.getByRole('button', { name: 'Nova transferência' }),
        );
        await ateConfirmar(usuario);
        await usuario.click(screen.getByRole('button', { name: 'Confirmar' }));
        await screen.findByRole('status');

        const chaves = falsa
            .pedidos('POST /transferencias')
            .map((p) => p.cabecalhos['Idempotency-Key']);
        expect(chaves).toHaveLength(3);
        expect(chaves[1]).toBe(chaves[0]);
        expect(chaves[2]).not.toBe(chaves[0]);
    });

    it('barra antes de mandar um valor acima do disponível', async () => {
        const falsa = montar();
        const { usuario } = await abrirTransferir();
        await ateConfirmar(usuario, '400,00');
        const campo = screen.getByLabelText('Valor');
        expect(campo).toHaveAttribute('aria-invalid', 'true');
        expect(campo).toHaveAccessibleDescription(
            'Passa do seu limite: agora dá para mandar até R$ 300,00.',
        );
        expect(campo).toHaveFocus();
        expect(
            screen.queryByRole('button', { name: 'Confirmar' }),
        ).not.toBeInTheDocument();
        expect(falsa.pedidos('POST /transferencias')).toHaveLength(0);
    });

    it('sessão vencida no meio da transferência: volta para entrar, sem repetir o pedido', async () => {
        const falsa = apiFalsa({
            'GET /contas': { corpo: [CONTA] },
            'GET /contas/:id/extrato': { corpo: [BONUS] },
            'GET /limites': { corpo: LIMITES },
            'GET /chaves': { corpo: [EMAIL] },
            'POST /chaves/consulta': {
                corpo: { nome: 'Bob L.', tipo: 'email' },
            },
            'POST /transferencias': { status: 401 },
        });
        logarComo(ANA);
        function Lugar() {
            const local = useLocation();
            return <p>em {local.pathname + local.search}</p>;
        }
        render(
            <MemoryRouter initialEntries={['/carteira']}>
                <Routes>
                    <Route
                        path="/carteira"
                        element={
                            <Exige>
                                <Carteira />
                            </Exige>
                        }
                    />
                    <Route path="*" element={<Lugar />} />
                </Routes>
            </MemoryRouter>,
        );
        const { usuario } = await abrirTransferir();
        await ateConfirmar(usuario);
        await usuario.click(screen.getByRole('button', { name: 'Confirmar' }));
        expect(
            await screen.findByText('em /entrar?volta=%2Fcarteira'),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /transferencias')).toHaveLength(1);
    });

    it('mostra a mensagem do 422 da API', async () => {
        montar({
            'POST /transferencias': {
                status: 422,
                corpo: {
                    detail: 'limite noturno passado: dá para mandar R$ 10,00',
                },
            },
        });
        const { usuario } = await abrirTransferir();
        await ateConfirmar(usuario);
        await usuario.click(screen.getByRole('button', { name: 'Confirmar' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Limite noturno passado: dá para mandar R$ 10,00.',
        );
    });

    it('avisa quando a chave não existe ou é da própria pessoa', async () => {
        montar({ 'POST /chaves/consulta': { status: 404 } });
        const { usuario } = await abrirTransferir();
        const campo = screen.getByLabelText('Chave de quem vai receber');
        await usuario.type(campo, 'ninguem@exemplo.com');
        await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Nenhuma carteira tem essa chave',
        );
        await usuario.clear(campo);
        await usuario.type(campo, 'ana@exemplo.com');
        await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
        expect(screen.getByRole('alert')).toHaveTextContent('Essa chave é sua');
    });

    it('fecha com Esc e devolve o foco ao botão', async () => {
        montar();
        const { usuario, botao } = await abrirTransferir();
        await usuario.keyboard('{Escape}');
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(botao).toHaveFocus();
    });
});
