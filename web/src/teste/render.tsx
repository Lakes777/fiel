import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { aplicar } from '../api/sessao';
import type { Usuario } from '../api/tipos';

/** Renderiza a tela numa rota (com os parâmetros), como se o navegador estivesse nela */
export function renderNaRota(
    tela: ReactElement,
    { rota = '/', caminho = '/' }: { rota?: string; caminho?: string } = {},
) {
    return render(
        <MemoryRouter initialEntries={[caminho]}>
            <Routes>
                <Route path={rota} element={tela} />
                <Route path="*" element={<p>outra página</p>} />
            </Routes>
        </MemoryRouter>,
    );
}

export const ANA: Usuario = {
    id: 1,
    nome: 'Ana Souza',
    email: 'ana@exemplo.com',
};
export const BOB: Usuario = {
    id: 2,
    nome: 'Bob Lima',
    email: 'bob@exemplo.com',
};

/** Deixa a aba logada (sem passar pela tela de entrar) */
export function logarComo(usuario: Usuario): void {
    aplicar(usuario);
}
