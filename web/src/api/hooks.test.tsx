import { act, render, screen } from '@testing-library/react';
import { eventosFalsos } from '../teste/preparar';
import { apiFalsa } from '../teste/api-falsa';
import { api } from './cliente';
import { useDados } from './hooks';

function Saldo() {
    const { dados, erro } = useDados(
        () => api<{ saldoCentavos: number }[]>('/contas'),
        ['carteira'],
    );
    if (erro) return <p>{erro}</p>;
    return <p>saldo {dados?.[0].saldoCentavos ?? '...'}</p>;
}

describe('useDados', () => {
    it('busca, e busca de novo quando chega um evento do tipo dela ou o pronto', async () => {
        let saldo = 100;
        const falsa = apiFalsa({
            'GET /contas': () => ({ corpo: [{ saldoCentavos: saldo }] }),
        });
        render(<Saldo />);
        expect(await screen.findByText('saldo 100')).toBeInTheDocument();

        saldo = 250;
        act(() => eventosFalsos().emitir('carteira'));
        expect(await screen.findByText('saldo 250')).toBeInTheDocument();

        act(() => eventosFalsos().emitir('avisos'));
        act(() => eventosFalsos().emitir('pronto'));
        await screen.findByText('saldo 250');
        expect(falsa.pedidos('GET /contas')).toHaveLength(3);
    });

    it('abre uma conexão ao vivo só enquanto alguma tela escuta', () => {
        apiFalsa({ 'GET /contas': { corpo: [{ saldoCentavos: 1 }] } });
        const { unmount } = render(
            <>
                <Saldo />
                <Saldo />
            </>,
        );
        expect(eventosFalsos().abertas).toHaveLength(1);
        expect(eventosFalsos().abertas[0].url).toBe('/api/eventos');
        unmount();
        expect(eventosFalsos().abertas[0].readyState).toBe(2);
    });

    it('mostra o erro como frase', async () => {
        apiFalsa({ 'GET /contas': { status: 500 } });
        render(<Saldo />);
        expect(
            await screen.findByText('Algo deu errado. Tente de novo.'),
        ).toBeInTheDocument();
    });
});
