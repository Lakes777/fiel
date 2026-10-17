import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Chave, Conta, Limites } from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo, renderNaRota } from '../teste/render';
import { Carteira } from './Carteira';

const CONTA: Conta = { id: 7, nome: 'Carteira', saldoCentavos: 100000 };
const LIMITES: Limites = {
    diaLimiteCentavos: 500000,
    diaUsadoCentavos: 0,
    noiteLimiteCentavos: 100000,
    noiteUsadoCentavos: 0,
    ehNoite: false,
    disponivelCentavos: 500000,
};
const UUID = '3f2a9c1e-7b4d-4e8a-9f60-1c2d3e4f5a6b';

function chave(id: number, tipo: Chave['tipo'], valor: string): Chave {
    return { id, tipo, valor, criadaEm: '2026-10-15T21:02:00Z' };
}

function montar(rotas: Parameters<typeof apiFalsa>[0]) {
    const falsa = apiFalsa({
        'GET /contas': { corpo: [CONTA] },
        'GET /contas/:id/extrato': { corpo: [] },
        'GET /limites': { corpo: LIMITES },
        ...rotas,
    });
    logarComo(ANA);
    renderNaRota(<Carteira />, { rota: '/carteira', caminho: '/carteira' });
    return falsa;
}

describe('Minhas chaves', () => {
    it('cria uma chave aleatória e mostra a lista nova', async () => {
        let lista = [chave(1, 'email', 'ana@exemplo.com')];
        const falsa = montar({
            'GET /chaves': () => ({ corpo: lista }),
            'POST /chaves': () => {
                lista = [...lista, chave(2, 'aleatoria', UUID)];
                return { status: 201, corpo: lista[1] };
            },
        });
        const usuario = userEvent.setup();
        await screen.findByText('ana@exemplo.com');
        expect(
            screen.getByRole('button', { name: 'Criar chave de e-mail' }),
        ).toBeDisabled();
        await usuario.click(
            screen.getByRole('button', { name: 'Criar chave aleatória' }),
        );
        expect(await screen.findByText(UUID)).toBeInTheDocument();
        expect(screen.getByText('Aleatória')).toBeInTheDocument();
        expect(falsa.pedidos('POST /chaves')[0].corpo).toEqual({
            tipo: 'aleatoria',
        });
    });

    it('copia a chave e diz que copiou', async () => {
        montar({
            'GET /chaves': { corpo: [chave(2, 'aleatoria', UUID)] },
        });
        const usuario = userEvent.setup();
        const escrever = vi.spyOn(navigator.clipboard, 'writeText');
        await usuario.click(
            await screen.findByRole('button', {
                name: `Copiar a chave ${UUID}`,
            }),
        );
        expect(escrever).toHaveBeenCalledWith(UUID);
        expect(await screen.findByText('Copiada')).toBeInTheDocument();
        expect(screen.getByText('Chave copiada.')).toBeInTheDocument();
    });

    it('apaga só depois de confirmar', async () => {
        let lista = [
            chave(1, 'email', 'ana@exemplo.com'),
            chave(2, 'aleatoria', UUID),
        ];
        const falsa = montar({
            'GET /chaves': () => ({ corpo: lista }),
            'DELETE /chaves/:id': () => {
                lista = lista.filter((c) => c.id !== 2);
                return { status: 204 };
            },
        });
        const usuario = userEvent.setup();
        await usuario.click(
            await screen.findByRole('button', {
                name: `Apagar a chave ${UUID}`,
            }),
        );
        expect(falsa.pedidos('DELETE /chaves/:id')).toHaveLength(0);
        expect(screen.getByText(/Quem guardou esta chave/)).toBeInTheDocument();

        await usuario.click(screen.getByRole('button', { name: 'Manter' }));
        expect(falsa.pedidos('DELETE /chaves/:id')).toHaveLength(0);

        await usuario.click(
            screen.getByRole('button', { name: `Apagar a chave ${UUID}` }),
        );
        await usuario.click(screen.getByRole('button', { name: 'Apagar' }));
        await waitFor(() =>
            expect(screen.queryByText(UUID)).not.toBeInTheDocument(),
        );
        expect(falsa.pedidos('DELETE /chaves/:id')[0].caminho).toBe(
            '/chaves/2',
        );
    });

    it('com 5 chaves, não deixa criar outra e explica', async () => {
        montar({
            'GET /chaves': {
                corpo: [
                    chave(1, 'email', 'ana@exemplo.com'),
                    ...[2, 3, 4, 5].map((id) =>
                        chave(id, 'aleatoria', `${UUID.slice(0, -1)}${id}`),
                    ),
                ],
            },
        });
        expect(
            await screen.findByText(/Você já tem 5 chaves, o máximo/),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: 'Criar chave aleatória' }),
        ).toBeDisabled();
        expect(
            screen.getByRole('button', { name: 'Criar chave de e-mail' }),
        ).toBeDisabled();
    });
});
