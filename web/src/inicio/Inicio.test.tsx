import { screen, within } from '@testing-library/react';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo, renderNaRota } from '../teste/render';
import { Inicio } from './Inicio';

describe('Inicio', () => {
    beforeEach(() => {
        apiFalsa({});
    });

    it('mostra o título e chama para criar conta quando ninguém está logado', async () => {
        renderNaRota(<Inicio />);
        expect(
            screen.getByRole('heading', {
                level: 1,
                name: 'Pagamentos onde a conta sempre fecha.',
            }),
        ).toBeInTheDocument();
        expect(
            await screen.findByRole('link', {
                name: /Criar conta com R\$\s1\.000 de mentira/,
            }),
        ).toHaveAttribute('href', '/cadastro');
        expect(
            screen.getByRole('link', { name: 'Como funciona' }),
        ).toHaveAttribute('href', '#recursos');
        expect(
            screen.getByRole('heading', {
                name: 'Dinheiro de mentira, regras de verdade',
            }),
        ).toBeInTheDocument();
        expect(screen.getAllByRole('article')).toHaveLength(6);
        expect(
            screen.getByRole('link', { name: 'código no GitHub' }),
        ).toHaveAttribute('href', 'https://github.com/Lakes777/fiel');
    });

    it('logado, o botão principal leva à carteira', () => {
        logarComo(ANA);
        renderNaRota(<Inicio />);
        expect(
            screen.getByRole('link', { name: /Ir para a carteira/ }),
        ).toHaveAttribute('href', '/carteira');
        expect(
            screen.queryByRole('link', { name: /Criar conta/ }),
        ).not.toBeInTheDocument();
    });

    it('o saldo do celular começa do zero para contar até o exemplo', () => {
        renderNaRota(<Inicio />);
        const celular = document.querySelector(
            '.inicio-celular',
        ) as HTMLElement;
        expect(within(celular).getByText('R$ 0,00')).toBeInTheDocument();
    });

    it('o celular mostra a carteira de exemplo, escondida do leitor de tela', async () => {
        // Com menos movimento o saldo vai direto ao valor final: o teste não depende do relógio
        vi.stubGlobal('matchMedia', (consulta: string) => ({
            matches: consulta.includes('reduce'),
        }));
        renderNaRota(<Inicio />);
        const celular = document.querySelector(
            '.inicio-celular',
        ) as HTMLElement;
        expect(celular).toHaveAttribute('aria-hidden', 'true');
        const dentro = within(celular);
        expect(await dentro.findByText('R$ 1.366,00')).toBeInTheDocument();
        expect(dentro.getByText('Bônus de boas-vindas')).toBeInTheDocument();
        expect(dentro.getByText('−R$ 34,00')).toBeInTheDocument();
        expect(dentro.getByText('+R$ 400,00')).toBeInTheDocument();
        expect(dentro.getByText('Saldo da carteira · Ana Souza')).toBeVisible();
        // Nada clicável dentro do celular
        expect(celular.querySelectorAll('button, a')).toHaveLength(0);
        expect(celular.querySelector('svg.qr')).not.toBeNull();
        // Para o leitor de tela, uma frase no lugar
        expect(screen.getByText(/Exemplo da carteira num celular/)).toHaveClass(
            'so-leitor',
        );
    });
});
