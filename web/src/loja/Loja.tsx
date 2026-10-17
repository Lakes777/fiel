import { Link, Route, Routes, useLocation } from 'react-router';
import { useSessao } from '../api/hooks';
import { useTitulo } from '../comum/titulo';
import { Avisos } from './Avisos';
import { DetalheDaCobranca } from './Detalhe';
import { NovaCobranca } from './NovaCobranca';
import { Painel } from './Painel';
import './loja.css';

/**
 * O painel do lojista: as cobranças (com o resumo do dia), uma cobrança aberta (QR, cancelar,
 * estornar), criar uma nova e os avisos ao sistema da loja (webhook). Tudo ao vivo: o servidor
 * avisa quando algo muda e a tela busca de novo.
 */
export function Loja() {
    const { usuario } = useSessao();
    const { pathname } = useLocation();
    // "Cobranças" fica marcada também com uma cobrança aberta: ela é parte da lista
    const aba = pathname.startsWith('/loja/nova')
        ? 'nova'
        : pathname.startsWith('/loja/avisos')
          ? 'avisos'
          : 'cobrancas';
    return (
        <main className="pagina loja">
            <div className="loja-topo">
                <p className="rotulo">
                    Loja{usuario ? ` · ${usuario.nome}` : ''}
                </p>
                <span className="ao-vivo">ao vivo</span>
            </div>
            <nav className="loja-abas" aria-label="Loja">
                <Aba para="/loja" atual={aba === 'cobrancas'}>
                    Cobranças
                </Aba>
                <Aba para="/loja/nova" atual={aba === 'nova'}>
                    Nova cobrança
                </Aba>
                <Aba para="/loja/avisos" atual={aba === 'avisos'}>
                    Avisos
                </Aba>
            </nav>
            <Routes>
                <Route index element={<Painel />} />
                <Route path="nova" element={<NovaCobranca />} />
                <Route path="cobrancas/:id" element={<DetalheDaCobranca />} />
                <Route path="avisos" element={<Avisos />} />
                <Route path="*" element={<NaLojaNaoExiste />} />
            </Routes>
        </main>
    );
}

function Aba({
    para,
    atual,
    children,
}: {
    para: string;
    atual: boolean;
    children: string;
}) {
    return (
        <Link to={para} aria-current={atual ? 'page' : undefined}>
            {children}
        </Link>
    );
}

function NaLojaNaoExiste() {
    useTitulo('Página não encontrada');
    return (
        <section className="loja-vazio">
            <h1>Esta página não existe</h1>
            <p className="suave">O endereço pode estar errado.</p>
            <Link className="botao" to="/loja">
                Ver as cobranças
            </Link>
        </section>
    );
}
