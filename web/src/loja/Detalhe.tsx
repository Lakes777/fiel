import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api, mensagemDe } from '../api/cliente';
import { useAoVivo, useDados } from '../api/hooks';
import type {
    CobrancaDoLojista,
    EstadoDaCobranca,
    EstornoFeito,
} from '../api/tipos';
import { NOME_DO_ESTADO, falta, quando, reais } from '../comum/formatos';
import { useMudou } from '../comum/movimento';
import { Qr } from '../comum/Qr';
import { useTitulo } from '../comum/titulo';
import { Confirmar } from './Confirmar';
import { FormularioDeEstorno, type PedidoDeEstorno } from './Estornar';
import { useAgora, useCopiar } from './ganchos';

interface Carregado {
    cobranca: CobrancaDoLojista;
    estornos: EstornoFeito[];
}

interface Aviso {
    ok: boolean;
    texto: string;
}

/** /loja/cobrancas/:id. A chave pelo id: abrir outra cobrança começa do zero, sem sobra da anterior */
export function DetalheDaCobranca() {
    const { id = '' } = useParams();
    if (!/^\d{1,15}$/.test(id))
        return <NaoAchada mensagem="Esta cobrança não existe." />;
    return <Detalhe key={id} id={Number(id)} />;
}

/** A cobrança que a tela de nova cobrança mandou junto ao navegar (se for esta) */
function criadaAgora(estado: unknown, id: number): CobrancaDoLojista | null {
    const criada = (estado as { criada?: CobrancaDoLojista } | null)?.criada;
    return criada?.id === id ? criada : null;
}

function Detalhe({ id }: { id: number }) {
    useTitulo(`Cobrança #${id}`);
    const local = useLocation();
    const navegar = useNavigate();
    // Lida uma vez e tirada do histórico: depois de um F5 ou de Voltar, a cobrança velha que
    // veio junto passaria por "pendente" e um pagamento antigo pareceria recebido agora
    const [recemCriada] = useState(() => criadaAgora(local.state, id));
    const temEstado = local.state != null;
    useEffect(() => {
        if (temEstado)
            navegar(`${local.pathname}${local.search}`, {
                replace: true,
                state: null,
            });
    }, [temEstado, local.pathname, local.search, navegar]);
    const { dados, erro, statusDoErro, recarregar } = useDados<Carregado>(
        async (sinal) => {
            const cobranca = await api<CobrancaDoLojista>(`/cobrancas/${id}`, {
                sinal,
            });
            // Sem estorno, não há lista para buscar
            const estornos =
                cobranca.estornadoCentavos > 0
                    ? await api<EstornoFeito[]>(`/cobrancas/${id}/estornos`, {
                          sinal,
                      })
                    : [];
            return { cobranca, estornos };
        },
    );
    // Só os eventos desta cobrança (o "pronto" o useDados já trata)
    useAoVivo((evento) => {
        if (
            evento.tipo === 'cobranca' &&
            (evento.cobranca === undefined || evento.cobranca === id)
        )
            recarregar();
    });

    const c = dados?.cobranca ?? recemCriada;
    const pendente = c?.estado === 'pendente';
    const agora = useAgora(30_000, pendente);
    const prazo = c ? falta(c.expiraEm, agora) : '';
    const { pagaAgora, anuncio } = useMudancaAoVivo(c);
    const estadoMudou = useMudou(c?.estado);

    // Venceu com a tela aberta: busca de novo, e o servidor já diz "expirada"
    const venceu = pendente && prazo === 'venceu';
    useEffect(() => {
        if (venceu) recarregar();
    }, [venceu, recarregar]);

    const [aviso, setAviso] = useState<Aviso | null>(null);
    const lugarDoAviso = useRef<HTMLParagraphElement>(null);
    function avisar(novo: Aviso) {
        setAviso(novo);
        // O botão que a pessoa usou some (a cobrança mudou de estado): o foco vai para o resultado
        requestAnimationFrame(() => lugarDoAviso.current?.focus());
    }

    if (!c) {
        if (erro)
            return (
                <NaoAchada
                    mensagem={
                        statusDoErro === 404
                            ? 'Esta cobrança não existe, ou é de outra loja.'
                            : erro
                    }
                />
            );
        return (
            <div aria-busy="true" aria-label="Carregando a cobrança">
                <div className="esqueleto" style={{ height: 36, width: 220 }} />
                <div
                    className="esqueleto"
                    style={{ height: 280, marginTop: 20 }}
                />
            </div>
        );
    }

    const mostrarQr = pendente || pagaAgora;
    const podeEstornar =
        c.estado === 'paga' || c.estado === 'parcialmente_estornada';
    return (
        <section className="detalhe" aria-labelledby="titulo-cobranca">
            <Link to="/loja" className="voltar">
                <span aria-hidden="true">←</span> Cobranças
            </Link>
            <div className="loja-cabeca">
                <h1 id="titulo-cobranca">Cobrança #{c.id}</h1>
                <span
                    className={`estado ${c.estado}${estadoMudou ? ' mudou' : ''}`}
                >
                    {NOME_DO_ESTADO[c.estado]}
                </span>
            </div>
            <p className="so-leitor" aria-live="polite">
                {anuncio}
            </p>

            <div className="detalhe-grade">
                <div
                    className={`cartao lado-qr${pagaAgora ? ' recebida' : ''}`}
                >
                    {mostrarQr ? (
                        <div className="moldura-qr">
                            <Qr
                                texto={c.qr}
                                tamanho={240}
                                desenhar={recemCriada !== null}
                            />
                            {pagaAgora && <Carimbo />}
                        </div>
                    ) : (
                        <Selo cobranca={c} />
                    )}
                    {pagaAgora && (
                        <p className="recebida-texto">
                            Pagamento recebido agora
                        </p>
                    )}
                    {pendente && <AcoesDoQr qr={c.qr} />}
                </div>

                <div className="lado-dados">
                    <p className="numero-grande">{reais(c.valorCentavos)}</p>
                    <p className="descricao-grande">{c.descricao}</p>
                    <dl className="ficha">
                        <div>
                            <dt>Criada</dt>
                            <dd>{quando(c.criadaEm, agora)}</dd>
                        </div>
                        {pendente && (
                            <div>
                                <dt>Vence</dt>
                                <dd>
                                    {prazo === 'venceu' ? 'agora' : prazo}{' '}
                                    <span className="suave">
                                        ({quando(c.expiraEm, agora)})
                                    </span>
                                </dd>
                            </div>
                        )}
                        {c.estado === 'expirada' && (
                            <div>
                                <dt>Venceu</dt>
                                <dd>{quando(c.expiraEm, agora)}</dd>
                            </div>
                        )}
                        {c.pagaEm && (
                            <div>
                                <dt>Paga</dt>
                                <dd>{quando(c.pagaEm, agora)}</dd>
                            </div>
                        )}
                        {c.estornadoCentavos > 0 && (
                            <div>
                                <dt>Devolvido</dt>
                                <dd>
                                    <span className="numero">
                                        {reais(c.estornadoCentavos)}
                                    </span>{' '}
                                    de {reais(c.valorCentavos)}
                                </dd>
                            </div>
                        )}
                    </dl>

                    <p
                        ref={lugarDoAviso}
                        tabIndex={-1}
                        role="status"
                        className={
                            aviso ? `alerta${aviso.ok ? ' ok' : ''}` : 'vazio'
                        }
                    >
                        {aviso?.texto}
                    </p>

                    {pendente && (
                        <Cancelar
                            id={c.id}
                            aoTerminar={(novo) => {
                                avisar(novo);
                                recarregar();
                            }}
                        />
                    )}
                    {podeEstornar && (
                        <Estornar
                            cobranca={c}
                            aoFeito={(estorno) => {
                                avisar({
                                    ok: true,
                                    texto: `Estorno de ${reais(estorno.valorCentavos)} feito. O dinheiro voltou para quem pagou.`,
                                });
                                recarregar();
                            }}
                            aoErro={recarregar}
                        />
                    )}
                    {(dados?.estornos.length ?? 0) > 0 && (
                        <ListaDeEstornos
                            estornos={dados!.estornos}
                            agora={agora}
                        />
                    )}
                </div>
            </div>
        </section>
    );
}

/**
 * O que mudou ao vivo enquanto a pessoa olhava: a frase para o leitor de tela e, se a cobrança
 * pendente virou paga, o momento do carimbo. Abrir uma cobrança já paga não carimba nada.
 */
function useMudancaAoVivo(c: CobrancaDoLojista | null) {
    const estado = c?.estado;
    const antes = useRef<EstadoDaCobranca | undefined>(estado);
    const [pagaAgora, setPagaAgora] = useState(false);
    const [anuncio, setAnuncio] = useState('');
    useEffect(() => {
        const anterior = antes.current;
        antes.current = estado;
        if (!anterior || !estado || anterior === estado) return;
        if (anterior === 'pendente' && estado === 'paga') {
            setPagaAgora(true);
            setAnuncio(
                `Pagamento recebido: ${reais(c?.valorCentavos ?? 0)}. A cobrança está paga.`,
            );
        } else {
            setAnuncio(`A cobrança agora está ${NOME_DO_ESTADO[estado]}.`);
        }
    }, [estado, c?.valorCentavos]);
    return { pagaAgora, anuncio };
}

/** O selo de "paga" que se desenha sobre o QR, uma vez */
function Carimbo() {
    return (
        <div className="carimbo" aria-hidden="true">
            <svg viewBox="0 0 64 64">
                <circle cx="32" cy="32" r="28" />
                <path d="M20 33l8 8 16-17" />
            </svg>
        </div>
    );
}

const FRASE_DO_ESTADO: Record<EstadoDaCobranca, string> = {
    pendente: '',
    paga: 'O dinheiro já está na sua carteira.',
    parcialmente_estornada: 'Parte do valor já voltou para quem pagou.',
    estornada: 'Todo o valor voltou para quem pagou.',
    expirada: 'Venceu sem pagamento. O QR não vale mais.',
    cancelada: 'Cancelada. O QR não vale mais.',
};

/** No lugar do QR, quando ele não serve mais */
function Selo({ cobranca }: { cobranca: CobrancaDoLojista }) {
    return (
        <div className="selo">
            <span className="selo-nome">{NOME_DO_ESTADO[cobranca.estado]}</span>
            <span className="suave">{FRASE_DO_ESTADO[cobranca.estado]}</span>
        </div>
    );
}

function AcoesDoQr({ qr }: { qr: string }) {
    const [copia, copiar] = useCopiar();
    return (
        <>
            <p className="suave dica-qr">
                Mostre este QR a quem vai pagar, ou mande o código.
            </p>
            <div className="botoes">
                <button
                    type="button"
                    className="botao vazado pequeno"
                    onClick={() => copiar(qr)}
                >
                    Copiar o código
                </button>
                <Link
                    className="botao vazado pequeno"
                    to={`/pagar?qr=${encodeURIComponent(qr)}`}
                    target="_blank"
                    rel="noreferrer"
                >
                    Abrir como quem paga
                </Link>
            </div>
            <p className="suave dica-qr" aria-live="polite">
                {copia === 'copiado'
                    ? 'Código copiado.'
                    : copia === 'falhou'
                      ? 'Não deu para copiar. Selecione o código abaixo.'
                      : 'Para testar, abra num navegador anônimo e entre com outra conta.'}
            </p>
            {copia === 'falhou' && (
                <textarea
                    className="codigo"
                    readOnly
                    value={qr}
                    aria-label="Código do QR"
                    onFocus={(e) => e.target.select()}
                />
            )}
        </>
    );
}

function Cancelar({
    id,
    aoTerminar,
}: {
    id: number;
    aoTerminar: (aviso: Aviso) => void;
}) {
    const [confirmando, setConfirmando] = useState(false);
    const [ocupado, setOcupado] = useState(false);
    const botao = useRef<HTMLButtonElement>(null);

    async function cancelar() {
        setOcupado(true);
        try {
            await api(`/cobrancas/${id}/cancelamento`, { metodo: 'POST' });
            aoTerminar({
                ok: true,
                texto: 'Cobrança cancelada. O QR não vale mais.',
            });
        } catch (erro) {
            aoTerminar({ ok: false, texto: mensagemDe(erro) });
        } finally {
            setOcupado(false);
            setConfirmando(false);
        }
    }

    return (
        <div className="acao">
            <button
                ref={botao}
                type="button"
                className="botao perigo"
                aria-expanded={confirmando}
                onClick={() => setConfirmando(true)}
            >
                Cancelar cobrança
            </button>
            {confirmando && (
                <Confirmar
                    titulo="Cancelar esta cobrança?"
                    texto="Quem tiver o QR não vai mais conseguir pagar. Não dá para desfazer."
                    confirmar="Sim, cancelar"
                    ocupado={ocupado}
                    aoConfirmar={() => void cancelar()}
                    aoVoltar={() => {
                        setConfirmando(false);
                        botao.current?.focus();
                    }}
                />
            )}
        </div>
    );
}

function Estornar({
    cobranca,
    aoFeito,
    aoErro,
}: {
    cobranca: CobrancaDoLojista;
    aoFeito: (estorno: EstornoFeito) => void;
    aoErro: () => void;
}) {
    const [aberto, setAberto] = useState(false);
    const botao = useRef<HTMLButtonElement>(null);
    // Fica aqui, fora do formulário: fechar e abrir de novo depois de uma queda de rede ainda
    // reaproveita a chave do pedido que pode ter chegado (veja o Estornar.tsx)
    const pedido = useRef<PedidoDeEstorno | null>(null);
    return (
        <div className="acao">
            {!aberto && (
                <button
                    ref={botao}
                    type="button"
                    className="botao perigo"
                    onClick={() => setAberto(true)}
                >
                    Estornar
                </button>
            )}
            {aberto && (
                <FormularioDeEstorno
                    cobranca={cobranca}
                    pedido={pedido}
                    aoFeito={(estorno) => {
                        setAberto(false);
                        aoFeito(estorno);
                    }}
                    aoErro={aoErro}
                    aoFechar={() => {
                        setAberto(false);
                        requestAnimationFrame(() => botao.current?.focus());
                    }}
                />
            )}
        </div>
    );
}

function ListaDeEstornos({
    estornos,
    agora,
}: {
    estornos: EstornoFeito[];
    agora: Date;
}) {
    return (
        <section className="estornos" aria-labelledby="titulo-estornos">
            <h2 id="titulo-estornos" className="rotulo">
                Estornos
            </h2>
            <ul>
                {estornos.map((e) => (
                    <li key={e.id}>
                        <span className="estorno-motivo">
                            {e.motivo ?? 'Sem motivo'}
                        </span>
                        <span className="suave estorno-quando">
                            {quando(e.em, agora)}
                        </span>
                        <span className="numero sai">
                            {reais(-e.valorCentavos)}
                        </span>
                    </li>
                ))}
            </ul>
        </section>
    );
}

function NaoAchada({ mensagem }: { mensagem: string }) {
    return (
        <section className="loja-vazio">
            <h1>Cobrança não encontrada</h1>
            <p className="suave">{mensagem}</p>
            <Link className="botao" to="/loja">
                Ver as cobranças
            </Link>
        </section>
    );
}
