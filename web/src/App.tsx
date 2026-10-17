import { Route, Routes } from 'react-router';
import { Carteira } from './carteira/Carteira';
import { Pagar } from './carteira/Pagar';
import { Exige } from './comum/Exige';
import { Layout } from './comum/Layout';
import { NaoEncontrada } from './comum/NaoEncontrada';
import { Inicio } from './inicio/Inicio';
import { Loja } from './loja/Loja';
import { Cadastro } from './paginas/Cadastro';
import { Entrar } from './paginas/Entrar';

/**
 * As telas. Públicas: início, entrar, cadastro. Com login: a carteira (e pagar um QR) e a loja
 * (o painel do lojista: cobranças, estornos e avisos).
 */
export function App() {
    return (
        <Routes>
            <Route element={<Layout />}>
                <Route index element={<Inicio />} />
                <Route path="entrar" element={<Entrar />} />
                <Route path="cadastro" element={<Cadastro />} />
                <Route
                    path="carteira"
                    element={
                        <Exige>
                            <Carteira />
                        </Exige>
                    }
                />
                <Route
                    path="pagar"
                    element={
                        <Exige>
                            <Pagar />
                        </Exige>
                    }
                />
                <Route
                    path="loja/*"
                    element={
                        <Exige>
                            <Loja />
                        </Exige>
                    }
                />
                <Route path="*" element={<NaoEncontrada />} />
            </Route>
        </Routes>
    );
}
