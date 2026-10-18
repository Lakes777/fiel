import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/cliente';
import { useDados, useSessao } from '../api/hooks';
import type { Chave, Conta, LinhaDoExtrato, Limites } from '../api/tipos';
import { reais } from '../comum/formatos';
import { useTitulo } from '../comum/titulo';
import { Chaves } from './Chaves';
import { RoteiroDaDemo } from './RoteiroDaDemo';
import { useIdempotencia } from './idempotencia';
import { Transferir } from './Transferir';
import { VisaoDaCarteira } from './VisaoDaCarteira';
import './carteira.css';

interface DadosDaCarteira {
    conta: Conta;
    extrato: LinhaDoExtrato[];
    limites: Limites;
}

/** Contas, extrato e limites juntos: chegam (e mudam ao vivo) como uma coisa só */
async function carregarCarteira(sinal: AbortSignal): Promise<DadosDaCarteira> {
    const contas = await api<Conta[]>('/contas', { sinal });
    // A carteira é a primeira conta (cada pessoa tem uma)
    const conta = contas[0];
    if (!conta) throw new Error('sem carteira');
    const [extrato, limites] = await Promise.all([
        api<LinhaDoExtrato[]>(`/contas/${conta.id}/extrato?limite=50`, {
            sinal,
        }),
        api<Limites>('/limites', { sinal }),
    ]);
    return { conta, extrato, limites };
}

/**
 * A carteira de quem está logado: saldo, limites e extrato ao vivo (um evento "carteira" busca
 * tudo de novo), as ações (transferir aqui mesmo, pagar um QR, cobrar) e as chaves ao lado.
 */
export function Carteira() {
    useTitulo('Carteira');
    const { usuario } = useSessao();
    const carteira = useDados(carregarCarteira, ['carteira']);
    const chaves = useDados((sinal) => api<Chave[]>('/chaves', { sinal }));
    const idempotencia = useIdempotencia();
    const [transferindo, setTransferindo] = useState(false);
    const botaoTransferir = useRef<HTMLButtonElement>(null);
    const dados = carteira.dados;

    return (
        <main className="pagina tela-carteira">
            <div className="cabeca-carteira">
                <h1>Carteira</h1>
                <span className="ao-vivo">ao vivo</span>
            </div>
            <div className="grade-carteira">
                <div className="cartao coluna-carteira">
                    {carteira.erro && !dados && (
                        <p className="alerta" role="alert">
                            {carteira.erro}
                        </p>
                    )}
                    <VisaoDaCarteira
                        nome={usuario?.nome ?? ''}
                        saldoCentavos={dados?.conta.saldoCentavos ?? null}
                        limites={dados?.limites ?? null}
                        extrato={dados?.extrato ?? null}
                        linhas={50}
                        acoes={
                            <>
                                <button
                                    ref={botaoTransferir}
                                    type="button"
                                    className="botao"
                                    disabled={!dados}
                                    aria-haspopup="dialog"
                                    onClick={() => setTransferindo(true)}
                                >
                                    Transferir
                                </button>
                                <Link className="botao vazado" to="/pagar">
                                    Pagar QR
                                </Link>
                                <Link className="botao vazado" to="/loja/nova">
                                    Cobrar
                                </Link>
                            </>
                        }
                    />
                </div>
                <div className="coluna-lado">
                    <RoteiroDaDemo />
                    <Chaves
                        chaves={chaves.dados}
                        erroAoCarregar={chaves.erro}
                        aoMudar={chaves.recarregar}
                    />
                    <section
                        className="cartao ajuda"
                        aria-labelledby="titulo-ajuda"
                    >
                        <h2 className="rotulo" id="titulo-ajuda">
                            Como funciona
                        </h2>
                        <ul>
                            <li>
                                Todo o dinheiro aqui é de mentira: o cadastro
                                ganha {reais(100000)} para testar.
                            </li>
                            <li>
                                Dá para mandar até {reais(500000)} por dia e{' '}
                                {reais(100000)} à noite (20h às 6h, horário de
                                Brasília).
                            </li>
                            <li>
                                Se a conexão cair no meio de uma transferência,
                                pode tentar de novo: ela não sai duas vezes.
                            </li>
                        </ul>
                    </section>
                </div>
            </div>
            {transferindo && dados && (
                <Transferir
                    saldoCentavos={dados.conta.saldoCentavos}
                    limites={dados.limites}
                    minhasChaves={chaves.dados?.map((c) => c.valor) ?? []}
                    idempotencia={idempotencia}
                    aoFechar={() => setTransferindo(false)}
                    aoTransferir={carteira.recarregar}
                    voltarPara={botaoTransferir}
                />
            )}
        </main>
    );
}
