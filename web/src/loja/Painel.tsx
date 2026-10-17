import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/cliente';
import { useDados } from '../api/hooks';
import type { CobrancaDoLojista, EstadoDaCobranca } from '../api/tipos';
import { Balanca } from '../comum/Marca';
import {
    NOME_DO_ESTADO,
    ehHoje,
    falta,
    quando,
    reais,
} from '../comum/formatos';
import { useMudou } from '../comum/movimento';
import { useTitulo } from '../comum/titulo';
import { useAgora } from './ganchos';

type Filtro = 'todas' | 'pendentes' | 'pagas' | 'estornadas';

const FILTROS: { chave: Filtro; nome: string; estados: EstadoDaCobranca[] }[] =
    [
        { chave: 'todas', nome: 'Todas', estados: [] },
        { chave: 'pendentes', nome: 'Pendentes', estados: ['pendente'] },
        { chave: 'pagas', nome: 'Pagas', estados: ['paga'] },
        {
            chave: 'estornadas',
            nome: 'Estornadas',
            estados: ['parcialmente_estornada', 'estornada'],
        },
    ];

function filtroDe(texto: string | null): Filtro {
    return FILTROS.some((f) => f.chave === texto) ? (texto as Filtro) : 'todas';
}

/** O resumo do dia, contado das cobranças que vieram (as 100 mais recentes) */
function resumoDoDia(cobrancas: CobrancaDoLojista[], agora: Date) {
    const pagasHoje = cobrancas.filter(
        (c) => c.pagaEm !== null && ehHoje(c.pagaEm, agora),
    );
    return {
        recebido: pagasHoje.reduce((s, c) => s + c.valorCentavos, 0),
        pagas: pagasHoje.length,
        devolvido: pagasHoje.reduce((s, c) => s + c.estornadoCentavos, 0),
    };
}

/** "vence em 12 min", "paga hoje, 14:32": o momento que importa em cada estado */
function momento(c: CobrancaDoLojista, agora: Date): string {
    switch (c.estado) {
        case 'pendente':
            return `vence ${falta(c.expiraEm, agora)}`;
        case 'expirada':
            return `venceu ${quando(c.expiraEm, agora)}`;
        case 'cancelada':
            return `criada ${quando(c.criadaEm, agora)}`;
        default:
            return c.pagaEm
                ? `paga ${quando(c.pagaEm, agora)}`
                : quando(c.criadaEm, agora);
    }
}

/** /loja: o resumo do dia e a lista de cobranças, ao vivo */
export function Painel() {
    useTitulo('Cobranças');
    const { dados, erro, recarregar } = useDados(
        (sinal) => api<CobrancaDoLojista[]>('/cobrancas', { sinal }),
        ['cobranca'],
    );
    const agora = useAgora(30_000);
    const [busca, setBusca] = useSearchParams();
    const filtro = filtroDe(busca.get('estado'));
    const anuncio = useAnuncioDeMudanca(dados);

    const visiveis = useMemo(() => {
        const estados = FILTROS.find((f) => f.chave === filtro)!.estados;
        return (dados ?? []).filter(
            (c) => estados.length === 0 || estados.includes(c.estado),
        );
    }, [dados, filtro]);

    return (
        <section aria-labelledby="titulo-cobrancas">
            <div className="loja-cabeca">
                <h1 id="titulo-cobrancas">Cobranças</h1>
                <Link className="botao pequeno" to="/loja/nova">
                    Nova cobrança
                </Link>
            </div>
            <p className="so-leitor" aria-live="polite">
                {anuncio}
            </p>

            {dados === null && erro === null && <Esqueleto />}
            {erro !== null && (
                <div className="alerta" role="alert">
                    {erro}{' '}
                    <button
                        type="button"
                        className="link-botao"
                        onClick={recarregar}
                    >
                        Tentar de novo
                    </button>
                </div>
            )}
            {dados !== null && dados.length === 0 && <NenhumaAinda />}
            {dados !== null && dados.length > 0 && (
                <>
                    <Resumo cobrancas={dados} agora={agora} />
                    <div
                        className="filtros"
                        role="group"
                        aria-label="Mostrar cobranças"
                    >
                        {FILTROS.map((f) => (
                            <button
                                key={f.chave}
                                type="button"
                                className="filtro"
                                aria-pressed={filtro === f.chave}
                                onClick={() =>
                                    setBusca(
                                        f.chave === 'todas'
                                            ? {}
                                            : { estado: f.chave },
                                        { replace: true },
                                    )
                                }
                            >
                                {f.nome}
                            </button>
                        ))}
                    </div>
                    {visiveis.length === 0 ? (
                        <p className="suave lista-vazia">
                            Nenhuma cobrança com este filtro.
                        </p>
                    ) : (
                        <ul className="cobrancas" aria-label="Cobranças">
                            {visiveis.map((c, i) => (
                                <LinhaDaCobranca
                                    key={c.id}
                                    cobranca={c}
                                    agora={agora}
                                    i={i}
                                />
                            ))}
                        </ul>
                    )}
                </>
            )}
        </section>
    );
}

/**
 * Uma frase para leitor de tela quando uma cobrança muda de estado ao vivo ("Cobrança #132 agora
 * está paga"). A lista inteira com aria-live leria tudo de novo a cada evento.
 */
function useAnuncioDeMudanca(dados: CobrancaDoLojista[] | null): string {
    const anteriores = useRef<Map<number, EstadoDaCobranca> | null>(null);
    const [anuncio, setAnuncio] = useState('');
    useEffect(() => {
        if (!dados) return;
        const antes = anteriores.current;
        anteriores.current = new Map(dados.map((c) => [c.id, c.estado]));
        if (!antes) return;
        const mudaram = dados.filter(
            (c) => antes.has(c.id) && antes.get(c.id) !== c.estado,
        );
        if (mudaram.length > 0)
            setAnuncio(
                mudaram
                    .map(
                        (c) =>
                            `Cobrança #${c.id} agora está ${NOME_DO_ESTADO[c.estado]}.`,
                    )
                    .join(' '),
            );
    }, [dados]);
    return anuncio;
}

function Resumo({
    cobrancas,
    agora,
}: {
    cobrancas: CobrancaDoLojista[];
    agora: Date;
}) {
    const { recebido, pagas, devolvido } = resumoDoDia(cobrancas, agora);
    const mudou = useMudou(recebido);
    return (
        <section className="resumo" aria-label="Resumo de hoje">
            <div className="cartao">
                <b className={`numero${mudou ? ' mudou' : ''}`}>
                    {reais(recebido)}
                </b>
                <small>recebido hoje</small>
            </div>
            <div className="cartao">
                <b className="numero">{pagas}</b>
                <small>
                    {pagas === 1
                        ? 'cobrança paga hoje'
                        : 'cobranças pagas hoje'}
                </small>
            </div>
            <div className="cartao">
                <b className="numero">{reais(devolvido)}</b>
                <small>devolvido das pagas hoje</small>
            </div>
            <p className="resumo-nota suave">
                Conta as 100 cobranças mais recentes.
            </p>
        </section>
    );
}

function LinhaDaCobranca({
    cobranca: c,
    agora,
    i,
}: {
    cobranca: CobrancaDoLojista;
    agora: Date;
    i: number;
}) {
    // O brilho fica no link, e a entrada no <li>: as duas animações na mesma caixa brigariam, e a
    // entrada voltaria a tocar quando o brilho saísse
    const mudou = useMudou(c.estado);
    return (
        <li className="entrar" style={{ ['--i' as string]: Math.min(i, 8) }}>
            <Link
                to={`/loja/cobrancas/${c.id}`}
                className={`linha-cobranca${mudou ? ' mudou' : ''}`}
                // Os pedaços colados viram "#132Caneca..." no leitor de tela: uma frase inteira é melhor
                aria-label={`Cobrança #${c.id}, ${c.descricao}, ${reais(c.valorCentavos)}, ${NOME_DO_ESTADO[c.estado]}, ${momento(c, agora)}`}
            >
                <span className="id numero">#{c.id}</span>
                <span className="descricao">{c.descricao}</span>
                <span className="momento">{momento(c, agora)}</span>
                <span className="lugar-estado">
                    <span className={`estado ${c.estado}`}>
                        {NOME_DO_ESTADO[c.estado]}
                    </span>
                </span>
                <span className="valor numero">{reais(c.valorCentavos)}</span>
            </Link>
        </li>
    );
}

function NenhumaAinda() {
    return (
        <div className="loja-vazio cartao entrar">
            <Balanca className="loja-vazio-icone" />
            <h2>Nenhuma cobrança ainda</h2>
            <p className="suave">
                Crie uma cobrança, mostre o QR a quem vai pagar e veja o
                pagamento chegar aqui na hora.
            </p>
            <Link className="botao" to="/loja/nova">
                Criar a primeira cobrança <span className="seta">→</span>
            </Link>
        </div>
    );
}

function Esqueleto() {
    return (
        <div aria-busy="true" aria-label="Carregando as cobranças">
            <div className="resumo">
                {[0, 1, 2].map((i) => (
                    <div key={i} className="esqueleto" style={{ height: 76 }} />
                ))}
            </div>
            {[0, 1, 2, 3].map((i) => (
                <div
                    key={i}
                    className="esqueleto"
                    style={{ height: 52, marginBottom: 8 }}
                />
            ))}
        </div>
    );
}
