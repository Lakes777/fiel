import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, renderNaRota } from '../teste/render';
import { Cadastro } from './Cadastro';
import { problemaDaSenha } from './regras';

function abrir() {
    renderNaRota(<Cadastro />, { rota: '/cadastro', caminho: '/cadastro' });
}

async function preencher(senha: string) {
    await userEvent.type(screen.getByLabelText('Nome'), 'Ana Souza');
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com');
    await userEvent.type(screen.getByLabelText('Senha'), senha);
    await userEvent.click(
        screen.getByRole('button', {
            name: 'Criar conta e ganhar R$ 1.000 de mentira',
        }),
    );
}

describe('Cadastro', () => {
    it('recusa senha curta antes de enviar, no próprio campo', async () => {
        const falsa = apiFalsa({});
        abrir();
        await preencher('curta');
        const senha = screen.getByLabelText('Senha');
        expect(senha).toHaveAttribute('aria-invalid', 'true');
        expect(senha).toHaveAccessibleDescription(
            'A senha precisa ter pelo menos 10 caracteres.',
        );
        expect(falsa.pedidos('POST /cadastro')).toHaveLength(0);
    });

    it('recusa senha que passa de 72 bytes mesmo com menos de 72 letras', async () => {
        const falsa = apiFalsa({});
        abrir();
        // 40 letras, mas 80 bytes: cada "é" ocupa dois
        await preencher('é'.repeat(40));
        expect(
            screen.getByText(/A senha pode ter no máximo 72 bytes/),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /cadastro')).toHaveLength(0);
    });

    it('mostra a mensagem da API quando o e-mail já tem conta', async () => {
        apiFalsa({
            'POST /cadastro': {
                status: 409,
                corpo: { detail: 'este e-mail já tem uma conta' },
            },
        });
        abrir();
        await preencher('senha-forte-123');
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Este e-mail já tem uma conta.',
        );
        expect(
            screen.getByRole('button', { name: /Criar conta e ganhar/ }),
        ).toBeEnabled();
    });

    it('cadastra, entra e vai para a carteira', async () => {
        const falsa = apiFalsa({
            'POST /cadastro': { status: 201, corpo: ANA },
            'POST /sessao': { corpo: ANA },
        });
        abrir();
        await preencher('senha-forte-123');
        expect(await screen.findByText('outra página')).toBeInTheDocument();
        expect(falsa.pedidos('POST /cadastro')[0].corpo).toEqual({
            nome: 'Ana Souza',
            email: 'ana@exemplo.com',
            senha: 'senha-forte-123',
        });
        expect(falsa.pedidos('POST /sessao')[0].corpo).toEqual({
            email: 'ana@exemplo.com',
            senha: 'senha-forte-123',
        });
    });

    it('avisa que nada é dinheiro de verdade e não pede CPF', () => {
        apiFalsa({});
        abrir();
        expect(
            screen.getByText(
                /Nada aqui é dinheiro de verdade.*não pedimos CPF/,
            ),
        ).toBeInTheDocument();
        expect(screen.getByLabelText('Senha')).toHaveAttribute(
            'autocomplete',
            'new-password',
        );
        expect(screen.getByLabelText('Nome')).toHaveAttribute(
            'autocomplete',
            'name',
        );
    });
});

describe('problemaDaSenha', () => {
    it('conta letras no mínimo e bytes no máximo', () => {
        expect(problemaDaSenha('123456789')).toMatch(/pelo menos 10/);
        expect(problemaDaSenha('1234567890')).toBeNull();
        expect(problemaDaSenha('a'.repeat(72))).toBeNull();
        expect(problemaDaSenha('a'.repeat(73))).toMatch(/72 bytes/);
        expect(problemaDaSenha('ç'.repeat(36))).toBeNull();
        expect(problemaDaSenha('ç'.repeat(37))).toMatch(/72 bytes/);
    });
});
