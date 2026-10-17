import { NavLink, Outlet, useNavigate } from 'react-router';
import { useSessao } from '../api/hooks';
import { sair } from '../api/sessao';
import { Marca } from './Marca';
import { BotaoTema } from './tema';

/** O topo de todas as telas: a marca, os caminhos de quem está logado (ou entrar) e o tema */
export function Layout() {
    const { usuario } = useSessao();
    const navegar = useNavigate();
    return (
        <>
            <header className="topo">
                <Marca para={usuario ? '/carteira' : '/'} />
                <nav aria-label="Principal">
                    {usuario ? (
                        <>
                            <NavLink to="/carteira">Carteira</NavLink>
                            <NavLink to="/loja">Loja</NavLink>
                            <button
                                type="button"
                                className="sair"
                                onClick={() =>
                                    void sair().finally(() =>
                                        navegar('/', { replace: true }),
                                    )
                                }
                            >
                                Sair
                            </button>
                        </>
                    ) : (
                        <>
                            <a
                                className="some-no-celular"
                                href="https://github.com/Lakes777/fiel"
                                target="_blank"
                                rel="noreferrer"
                            >
                                GitHub
                            </a>
                            <NavLink to="/entrar">Entrar</NavLink>
                        </>
                    )}
                    <BotaoTema />
                </nav>
            </header>
            <Outlet />
        </>
    );
}
