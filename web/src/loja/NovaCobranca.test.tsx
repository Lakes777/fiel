import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFalsa } from '../teste/api-falsa';
import { BOB, logarComo, renderNaRota } from '../teste/render';
import { umaCobranca } from './amostras';
import { Loja } from './Loja';

function abrir() {
    logarComo(BOB);
    return renderNaRota(<Loja />, { rota: '/loja/*', caminho: '/loja/nova' });
}

describe('Nova cobrança', () => {
    it('confere antes de mandar: valor, máximo e descrição', async () => {
        const falsa = apiFalsa({});
        abrir();
        const criar = screen.getByRole('button', { name: 'Criar cobrança' });

        await userEvent.click(criar);
        expect(screen.getByText('Digite um valor, como 34,00.')).toBeVisible();
        expect(screen.getByLabelText('Valor')).toHaveFocus();
        expect(
            screen.getByText('Escreva o que está sendo cobrado.'),
        ).toBeVisible();

        await userEvent.type(screen.getByLabelText('Valor'), '5000,01');
        await userEvent.type(screen.getByLabelText('Descrição'), 'Mesa');
        await userEvent.click(criar);
        expect(
            screen.getByText('O máximo de uma cobrança é R$ 5.000,00.'),
        ).toBeVisible();

        await userEvent.clear(screen.getByLabelText('Valor'));
        await userEvent.type(screen.getByLabelText('Valor'), '0');
        await userEvent.click(criar);
        expect(
            screen.getByText('O valor precisa ser maior que zero.'),
        ).toBeVisible();

        expect(falsa.pedidos('POST /cobrancas')).toHaveLength(0);
    });

    it('cria e vai ao detalhe com o QR se desenhando', async () => {
        const criada = umaCobranca({ id: 133 });
        const falsa = apiFalsa({
            'POST /cobrancas': { status: 201, corpo: criada },
            'GET /cobrancas/:id': { corpo: criada },
        });
        abrir();
        expect(screen.getByLabelText('Vale por')).toHaveValue('30');

        await userEvent.type(screen.getByLabelText('Valor'), '34,00');
        await userEvent.type(
            screen.getByLabelText('Descrição'),
            '  Caneca esmaltada ',
        );
        await userEvent.selectOptions(screen.getByLabelText('Vale por'), '60');
        await userEvent.click(
            screen.getByRole('button', { name: 'Criar cobrança' }),
        );

        expect(
            await screen.findByRole('heading', { name: 'Cobrança #133' }),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /cobrancas')[0].corpo).toEqual({
            valorCentavos: 3400,
            descricao: 'Caneca esmaltada',
            validadeMinutos: 60,
        });
        expect(screen.getByRole('img', { name: 'QR da cobrança' })).toHaveClass(
            'qr-desenhar',
        );
    });

    it('mostra a mensagem da API quando ela recusa', async () => {
        apiFalsa({
            'POST /cobrancas': {
                status: 429,
                corpo: { detail: 'muitas cobranças em pouco tempo' },
            },
        });
        abrir();
        await userEvent.type(screen.getByLabelText('Valor'), '10');
        await userEvent.type(screen.getByLabelText('Descrição'), 'Pão');
        await userEvent.click(
            screen.getByRole('button', { name: 'Criar cobrança' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Muitas cobranças em pouco tempo.',
        );
        expect(
            screen.getByRole('button', { name: 'Criar cobrança' }),
        ).toBeEnabled();
    });
});
