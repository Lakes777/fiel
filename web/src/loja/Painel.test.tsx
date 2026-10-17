import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CobrancaDoLojista } from '../api/tipos';
import { apiFalsa } from '../teste/api-falsa';
import { eventosFalsos } from '../teste/preparar';
import { BOB, logarComo, renderNaRota } from '../teste/render';
import { haMinutos, umaCobranca } from './amostras';
import { Loja } from './Loja';

function abrir(caminho = '/loja') {
    logarComo(BOB);
    return renderNaRota(<Loja />, { rota: '/loja/*', caminho });
}

function linhaDe(id: number): HTMLElement {
    return screen.getByRole('link', { name: new RegExp(`^Cobrança #${id},`) });
}

const ONTEM = new Date(Date.now() - 2 * 86_400_000).toISOString();

function cobrancasDoDia(): CobrancaDoLojista[] {
    return [
        umaCobranca({ id: 132 }),
        umaCobranca({
            id: 131,
            descricao: 'Bule de ferro',
            valorCentavos: 12000,
            estado: 'paga',
            pagaEm: haMinutos(5),
        }),
        umaCobranca({
            id: 130,
            descricao: 'Jogo de xícaras',
            valorCentavos: 5000,
            estado: 'parcialmente_estornada',
            pagaEm: haMinutos(30),
            estornadoCentavos: 2000,
        }),
        umaCobranca({
            id: 129,
            descricao: 'Pano de prato',
            valorCentavos: 9900,
            estado: 'paga',
            criadaEm: ONTEM,
            pagaEm: ONTEM,
        }),
    ];
}

describe('Painel do lojista', () => {
    it('mostra o resumo de hoje e a lista de cobranças', async () => {
        apiFalsa({ 'GET /cobrancas': { corpo: cobrancasDoDia() } });
        abrir();

        const resumo = await screen.findByRole('region', {
            name: 'Resumo de hoje',
        });
        // Só as pagas hoje: 120 + 50 (a de ontem não conta)
        expect(within(resumo).getByText('R$ 170,00')).toBeInTheDocument();
        expect(within(resumo).getByText('2')).toBeInTheDocument();
        expect(within(resumo).getByText('R$ 20,00')).toBeInTheDocument();
        expect(
            within(resumo).getByText(/100 cobranças mais recentes/),
        ).toBeInTheDocument();

        expect(screen.getAllByRole('listitem')).toHaveLength(4);
        const pendente = linhaDe(132);
        expect(pendente).toHaveAttribute('href', '/loja/cobrancas/132');
        expect(within(pendente).getByText('Caneca esmaltada')).toBeVisible();
        expect(within(pendente).getByText('pendente')).toHaveClass(
            'estado',
            'pendente',
        );
        expect(within(pendente).getByText('R$ 34,00')).toBeInTheDocument();
        expect(within(pendente).getByText(/^vence em 2\d min$/)).toBeVisible();
        expect(
            within(linhaDe(130)).getByText('estornada em parte'),
        ).toBeInTheDocument();
    });

    it('pendente que passou do prazo (o servidor ainda não marcou) diz "venceu", não "vence venceu"', async () => {
        apiFalsa({
            'GET /cobrancas': {
                corpo: [umaCobranca({ id: 140, expiraEm: haMinutos(2) })],
            },
        });
        abrir();
        await waitFor(() => expect(linhaDe(140)).toBeInTheDocument());
        expect(within(linhaDe(140)).getByText('venceu')).toBeInTheDocument();
        expect(linhaDe(140)).not.toHaveTextContent('vence venceu');
    });

    it('filtra por estado', async () => {
        apiFalsa({ 'GET /cobrancas': { corpo: cobrancasDoDia() } });
        abrir();
        await screen.findByText('Caneca esmaltada');

        await userEvent.click(
            screen.getByRole('button', { name: 'Pendentes' }),
        );
        expect(
            screen.getByRole('button', { name: 'Pendentes' }),
        ).toHaveAttribute('aria-pressed', 'true');
        expect(screen.getAllByRole('listitem')).toHaveLength(1);
        expect(linhaDe(132)).toBeInTheDocument();

        await userEvent.click(
            screen.getByRole('button', { name: 'Estornadas' }),
        );
        expect(screen.getAllByRole('listitem')).toHaveLength(1);
        expect(linhaDe(130)).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: 'Pagas' }));
        expect(screen.getAllByRole('listitem')).toHaveLength(2);

        await userEvent.click(screen.getByRole('button', { name: 'Todas' }));
        expect(screen.getAllByRole('listitem')).toHaveLength(4);
    });

    it('um evento "cobranca" busca de novo e a linha muda de estado com brilho', async () => {
        let lista = [umaCobranca({ id: 132 })];
        const falsa = apiFalsa({ 'GET /cobrancas': () => ({ corpo: lista }) });
        abrir();
        expect(
            await within(await screen.findByRole('list')).findByText(
                'pendente',
            ),
        ).toBeInTheDocument();
        expect(linhaDe(132)).not.toHaveClass('mudou');

        // A Ana pagou, em outro lugar
        lista = [
            umaCobranca({ id: 132, estado: 'paga', pagaEm: haMinutos(0) }),
        ];
        eventosFalsos().emitir('cobranca', { cobranca: 132 });

        expect(await within(linhaDe(132)).findByText('paga')).toHaveClass(
            'estado',
            'paga',
        );
        // O brilho e a frase vêm de efeitos, um instante depois da troca
        await waitFor(() => expect(linhaDe(132)).toHaveClass('mudou'));
        expect(
            await screen.findByText('Cobrança #132 agora está paga.'),
        ).toBeInTheDocument();
        const resumo = screen.getByRole('region', { name: 'Resumo de hoje' });
        await waitFor(() =>
            expect(within(resumo).getAllByText('R$ 34,00')[0]).toHaveClass(
                'mudou',
            ),
        );
        expect(falsa.pedidos('GET /cobrancas')).toHaveLength(2);
    });

    it('sem cobranças, convida a criar a primeira', async () => {
        apiFalsa({ 'GET /cobrancas': { corpo: [] } });
        abrir();

        expect(
            await screen.findByRole('heading', {
                name: 'Nenhuma cobrança ainda',
            }),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('region', { name: 'Resumo de hoje' }),
        ).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('link', { name: /Criar a primeira cobrança/ }),
        );
        expect(
            await screen.findByRole('heading', { name: 'Nova cobrança' }),
        ).toBeInTheDocument();
    });

    it('marca a aba atual e mostra o erro com como tentar de novo', async () => {
        let falhar = true;
        apiFalsa({
            'GET /cobrancas': () =>
                falhar ? { status: 500 } : { corpo: [umaCobranca()] },
        });
        abrir();
        expect(screen.getByRole('link', { name: 'Cobranças' })).toHaveAttribute(
            'aria-current',
            'page',
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Algo deu errado',
        );
        falhar = false;
        await userEvent.click(
            screen.getByRole('button', { name: 'Tentar de novo' }),
        );
        await waitFor(() =>
            expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
        );
        expect(screen.getByText('Caneca esmaltada')).toBeInTheDocument();
    });
});
