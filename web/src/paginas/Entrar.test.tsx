import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { apiFalsa } from '../teste/api-falsa';
import { ANA, logarComo } from '../teste/render';
import { Entrar } from './Entrar';
import { destinoDaVolta } from './regras';

/** Qualquer outra rota mostra onde a tela foi parar */
function Lugar() {
    const local = useLocation();
    return <p>em {local.pathname + local.search}</p>;
}

function abrir(caminho: string) {
    render(
        <MemoryRouter initialEntries={[caminho]}>
            <Routes>
                <Route path="/entrar" element={<Entrar />} />
                <Route path="*" element={<Lugar />} />
            </Routes>
        </MemoryRouter>,
    );
}

async function preencherEEntrar(senha = 'senha-forte-123') {
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com');
    await userEvent.type(screen.getByLabelText('Senha'), senha);
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
}

describe('Entrar', () => {
    it('entra e volta para onde a pessoa estava', async () => {
        const falsa = apiFalsa({ 'POST /sessao': { corpo: ANA } });
        abrir('/entrar?volta=%2Floja%2Fcobrancas%3Fx%3D1');
        await preencherEEntrar();
        expect(
            await screen.findByText('em /loja/cobrancas?x=1'),
        ).toBeInTheDocument();
        expect(falsa.pedidos('POST /sessao')[0].corpo).toEqual({
            email: 'ana@exemplo.com',
            senha: 'senha-forte-123',
        });
    });

    it('ignora volta para outro site e vai para a carteira', async () => {
        apiFalsa({ 'POST /sessao': { corpo: ANA } });
        abrir('/entrar?volta=%2F%2Fmal.exemplo.com%2Fx');
        await preencherEEntrar();
        expect(await screen.findByText('em /carteira')).toBeInTheDocument();
    });

    it('mostra o erro quando e-mail ou senha não batem, e deixa tentar de novo', async () => {
        apiFalsa({
            'POST /sessao': {
                status: 401,
                corpo: { detail: 'credenciais inválidas' },
            },
        });
        abrir('/entrar');
        await preencherEEntrar('errada-errada');
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'E-mail ou senha incorretos.',
        );
        expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
    });

    it('mostra a mensagem da API quando há tentativas demais', async () => {
        apiFalsa({
            'POST /sessao': {
                status: 429,
                corpo: { detail: 'muitas tentativas, espere 15 minutos' },
            },
        });
        abrir('/entrar');
        await preencherEEntrar();
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Muitas tentativas, espere 15 minutos.',
        );
    });

    it('já logado, nem mostra o formulário', async () => {
        logarComo(ANA);
        abrir('/entrar?volta=%2Floja');
        expect(await screen.findByText('em /loja')).toBeInTheDocument();
    });

    it('tem os autocompletes certos e link para criar conta', () => {
        apiFalsa({});
        abrir('/entrar');
        expect(screen.getByLabelText('E-mail')).toHaveAttribute(
            'autocomplete',
            'email',
        );
        expect(screen.getByLabelText('Senha')).toHaveAttribute(
            'autocomplete',
            'current-password',
        );
        expect(
            screen.getByRole('link', { name: 'Criar conta' }),
        ).toHaveAttribute('href', '/cadastro');
    });
});

describe('destinoDaVolta', () => {
    it('aceita só caminho deste site', () => {
        expect(destinoDaVolta('/loja')).toBe('/loja');
        expect(destinoDaVolta(null)).toBe('/carteira');
        expect(destinoDaVolta('')).toBe('/carteira');
        expect(destinoDaVolta('https://mal.exemplo.com')).toBe('/carteira');
        expect(destinoDaVolta('//mal.exemplo.com')).toBe('/carteira');
        expect(destinoDaVolta('/\\mal.exemplo.com')).toBe('/carteira');
        expect(destinoDaVolta('loja')).toBe('/carteira');
    });
});
