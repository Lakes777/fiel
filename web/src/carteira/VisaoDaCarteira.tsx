import type { ReactNode } from 'react';
import type { LinhaDoExtrato, Limites } from '../api/tipos';
import { quando, reais, reaisComSinal } from '../comum/formatos';
import { useContagem, useMudou } from '../comum/movimento';
import './visao.css';

export interface PropsDaVisao {
    /** O nome de quem é a carteira ("Ana Souza") */
    nome: string;
    /** null enquanto carrega */
    saldoCentavos: number | null;
    limites: Limites | null;
    extrato: LinhaDoExtrato[] | null;
    /** Os botões (Transferir, Pagar QR, Cobrar): a tela de verdade e a da tela inicial põem os seus */
    acoes: ReactNode;
    /** De onde o saldo começa a contar na primeira vez (a tela inicial conta do zero) */
    contarDe?: number;
    /** Quantas linhas do extrato mostrar */
    linhas?: number;
    /** Para os testes e para a tela inicial: o "agora" das datas ("hoje, 14:32") */
    agora?: Date;
}

/**
 * A carteira como aparece: saldo (que conta até o valor novo quando muda ao vivo), limites do dia e
 * da noite, as ações e o extrato. Só mostra: quem usa busca os dados. A tela inicial usa este mesmo
 * componente dentro do celular, com dados de exemplo, para mostrar a tela de verdade.
 */
export function VisaoDaCarteira({
    nome,
    saldoCentavos,
    limites,
    extrato,
    acoes,
    contarDe,
    linhas = 20,
    agora,
}: PropsDaVisao) {
    const saldo = useContagem(saldoCentavos, { de: contarDe });
    const mudou = useMudou(saldoCentavos);
    return (
        <section className="visao-carteira" aria-label="Carteira">
            <p className="rotulo-saldo">Saldo da carteira · {nome}</p>
            {saldoCentavos === null ? (
                <div className="esqueleto saldo-esqueleto" aria-busy="true" />
            ) : (
                <p
                    className={`numero-grande saldo${mudou ? ' mudou' : ''}`}
                    aria-live="polite"
                >
                    {reais(saldo)}
                </p>
            )}
            {limites && <BarrasDeLimite limites={limites} />}
            <div className="acoes">{acoes}</div>
            <h2 className="rotulo">Extrato</h2>
            {extrato === null ? (
                <div
                    className="esqueleto"
                    style={{ height: 160 }}
                    aria-busy="true"
                />
            ) : extrato.length === 0 ? (
                <p className="suave">Nenhum movimento ainda.</p>
            ) : (
                <ul className="extrato">
                    {extrato.slice(0, linhas).map((m, i) => (
                        <li
                            key={m.lancamentoId}
                            className="entrar"
                            style={{ ['--i' as string]: Math.min(i, 8) }}
                        >
                            <span className="descricao">{m.descricao}</span>
                            <span className="quando">
                                {quando(m.em, agora)}
                            </span>
                            <span
                                className={`valor numero ${m.valorCentavos > 0 ? 'entra' : 'sai'}`}
                            >
                                {reaisComSinal(m.valorCentavos)}
                            </span>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

function BarrasDeLimite({ limites }: { limites: Limites }) {
    const dia = limites.diaLimiteCentavos - limites.diaUsadoCentavos;
    const noite = limites.noiteLimiteCentavos - limites.noiteUsadoCentavos;
    return (
        <div className="limites">
            <Barra
                livre={Math.max(0, dia)}
                total={limites.diaLimiteCentavos}
                texto={`livres hoje, de ${reais(limites.diaLimiteCentavos)}`}
            />
            <Barra
                livre={Math.max(0, noite)}
                total={limites.noiteLimiteCentavos}
                texto={
                    limites.ehNoite
                        ? 'livres esta noite (20h–6h)'
                        : 'à noite (20h–6h)'
                }
            />
        </div>
    );
}

function Barra({
    livre,
    total,
    texto,
}: {
    livre: number;
    total: number;
    texto: string;
}) {
    const usado =
        total > 0 ? Math.min(100, ((total - livre) / total) * 100) : 0;
    return (
        <div className="limite">
            <b className="numero">{reais(livre)}</b>
            <span>{texto}</span>
            <div className="trilho" role="presentation">
                <span style={{ width: `${usado}%` }} />
            </div>
        </div>
    );
}
