import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { guardarRoteiro, lerRoteiro, type Roteiro } from '../api/demo';
import type { Usuario } from '../api/tipos';
import { RoteiroDaDemo } from '../carteira/RoteiroDaDemo';
import { umaCobranca } from '../loja/amostras';
import { Loja } from '../loja/Loja';
import { Entrar } from '../paginas/Entrar';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo, renderNaRota } from '../teste/render';
import { EntrarComoVisitante } from './EntrarComoVisitante';

const VISITANTE: Usuario = {
    id: 9,
    nome: 'Visitante 3F2A9C1B',
    email: 'visitante-3f2a9c1b@demo.fiel',
};

const ROTEIRO: Roteiro = {
    qrDaLoja: 'FIEL1|qr-da-loja',
    chaveDaAna: 'ana@exemplo.fiel',
    chaveDoBob: 'bob-3f2a9c1b@demo.fiel',
};

describe('Demonstração', () => {
    it('sem demonstração no servidor, o botão de visitante não aparece', async () => {
        // GET /demo sem tratador: 404, que conta como "não tem"
        const falsa = apiFalsa({});
        renderNaRota(<Entrar />, { rota: '/entrar', caminho: '/entrar' });
        await screen.findByRole('button', { name: 'Entrar' });
        await vi.waitFor(() =>
            expect(falsa.pedidos().map((p) => p.caminho)).toContain('/demo'),
        );
        expect(
            screen.queryByRole('button', { name: 'Entrar como visitante' }),
        ).not.toBeInTheDocument();
    });

    it('o visitante entra direto, vai para a carteira e o roteiro fica na aba', async () => {
        const falsa = apiFalsa({
            'GET /demo': { corpo: { ativa: true } },
            'POST /demo/visitante': {
                status: 201,
                corpo: { eu: VISITANTE, roteiro: ROTEIRO },
            },
        });
        renderNaRota(<EntrarComoVisitante />, { rota: '/', caminho: '/' });

        await userEvent.click(
            await screen.findByRole('button', {
                name: 'Entrar como visitante',
            }),
        );

        expect(await screen.findByText('outra página')).toBeInTheDocument();
        expect(falsa.pedidos('POST /demo/visitante')).toHaveLength(1);
        expect(lerRoteiro()).toEqual(ROTEIRO);
    });

    it('se a criação falhar (limite por IP), mostra o erro e deixa tentar de novo', async () => {
        apiFalsa({
            'GET /demo': { corpo: { ativa: true } },
            'POST /demo/visitante': {
                status: 429,
                corpo: { detail: 'muitas tentativas; tente de novo em 60 s' },
            },
        });
        renderNaRota(<EntrarComoVisitante />);
        await userEvent.click(
            await screen.findByRole('button', {
                name: 'Entrar como visitante',
            }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            /muitas tentativas/i,
        );
        expect(
            screen.getByRole('button', { name: 'Entrar como visitante' }),
        ).toBeEnabled();
    });

    it('a carteira do visitante mostra o roteiro, com o QR da loja no link de pagar', async () => {
        logarComo(VISITANTE);
        guardarRoteiro(ROTEIRO);
        renderNaRota(<RoteiroDaDemo />);

        expect(
            screen.getByRole('link', { name: 'pagar a cobrança' }),
        ).toHaveAttribute(
            'href',
            `/pagar?qr=${encodeURIComponent(ROTEIRO.qrDaLoja)}`,
        );
        expect(screen.getByText('ana@exemplo.fiel')).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: 'Esconder' }));
        expect(screen.queryByText('Experimente')).not.toBeInTheDocument();
        expect(lerRoteiro()).toBeNull();
    });

    it('e-mail que só começa com "visitante-" não é visitante', () => {
        logarComo({ ...ANA, email: 'visitante-joao@gmail.com' });
        guardarRoteiro(ROTEIRO);
        renderNaRota(<RoteiroDaDemo />);
        expect(screen.queryByText('Experimente')).not.toBeInTheDocument();
    });

    it('conta normal não vê roteiro, mesmo com um guardado na aba', () => {
        logarComo(ANA);
        guardarRoteiro(ROTEIRO);
        renderNaRota(<RoteiroDaDemo />);
        expect(screen.queryByText('Experimente')).not.toBeInTheDocument();
    });

    it('na cobrança pendente, o visitante pede para o Bob pagar', async () => {
        const c = umaCobranca();
        const falsa = apiFalsa({
            'GET /cobrancas/:id': { corpo: c },
            'POST /demo/cobrancas/:id/bob-paga': { corpo: {} },
        });
        logarComo(VISITANTE);
        renderNaRota(<Loja />, {
            rota: '/loja/*',
            caminho: `/loja/cobrancas/${c.id}`,
        });

        await userEvent.click(
            await screen.findByRole('button', {
                name: 'Pedir para o Bob pagar',
            }),
        );

        expect(
            falsa.pedidos('POST /demo/cobrancas/:id/bob-paga')[0].caminho,
        ).toBe(`/demo/cobrancas/${c.id}/bob-paga`);
        expect(
            await screen.findByRole('button', { name: 'O Bob pagou' }),
        ).toBeDisabled();
    });

    it('conta normal não tem o botão do Bob', async () => {
        apiFalsa({ 'GET /cobrancas/:id': { corpo: umaCobranca() } });
        logarComo(ANA);
        renderNaRota(<Loja />, {
            rota: '/loja/*',
            caminho: '/loja/cobrancas/132',
        });
        await screen.findByRole('img', { name: 'QR da cobrança' });
        expect(
            screen.queryByRole('button', { name: 'Pedir para o Bob pagar' }),
        ).not.toBeInTheDocument();
    });
});
