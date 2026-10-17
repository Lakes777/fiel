import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BotaoTema } from './tema';

describe('tema', () => {
    it('alterna claro e escuro e lembra a escolha', async () => {
        document.documentElement.dataset.tema = 'claro';
        render(<BotaoTema />);

        await userEvent.click(
            screen.getByRole('button', { name: 'Usar tema escuro' }),
        );
        expect(document.documentElement.dataset.tema).toBe('escuro');
        expect(localStorage.getItem('fiel-tema')).toBe('escuro');

        await userEvent.click(
            screen.getByRole('button', { name: 'Usar tema claro' }),
        );
        expect(document.documentElement.dataset.tema).toBe('claro');
        expect(localStorage.getItem('fiel-tema')).toBe('claro');
    });
});
