import {
    useEffect,
    useId,
    useRef,
    useState,
    type FormEvent,
    type RefObject,
} from 'react';
import { ErroDaApi, api, mensagemDe } from '../api/cliente';
import { useDados } from '../api/hooks';
import type { Aviso, EstadoDoAviso, Webhook, WebhookNovo } from '../api/tipos';
import { NOME_DO_AVISO, falta, quando } from '../comum/formatos';
import { useMudou } from '../comum/movimento';
import { useTitulo } from '../comum/titulo';
import { Confirmar } from './Confirmar';
import { useAgora, useCopiar } from './ganchos';

const EXEMPLO =
    'https://github.com/Lakes777/fiel/blob/main/exemplos/lojista.py';

const NOME_DO_TIPO: Record<string, string> = {
    'cobranca.paga': 'Cobrança paga',
    'cobranca.expirada': 'Cobrança expirada',
    'cobranca.estornada': 'Cobrança estornada',
    'transferencia.recebida': 'Transferência recebida',
};

type Filtro = '' | EstadoDoAviso;

const FILTROS: { chave: Filtro; nome: string }[] = [
    { chave: '', nome: 'Todos' },
    { chave: 'pendente', nome: 'Na fila' },
    { chave: 'entregue', nome: 'Entregues' },
    { chave: 'falhou', nome: 'Falharam' },
];

/** /loja/avisos: o endereço do webhook e o histórico de entregas */
export function Avisos() {
    useTitulo('Avisos');
    return (
        <section aria-labelledby="titulo-avisos">
            <h1 id="titulo-avisos">Avisos</h1>
            <p className="suave avisos-explica">
                Os avisos contam ao sistema da sua loja, na hora, quando uma
                cobrança é paga, vence ou é estornada, ou quando chega uma
                transferência. Há um receptor pronto, que confere a assinatura,
                em{' '}
                <a href={EXEMPLO} target="_blank" rel="noreferrer">
                    exemplos/lojista.py
                </a>
                .
            </p>
            <Endereco />
            <Historico />
        </section>
    );
}

/** GET /webhook responde 404 quando não há endereço: aqui isso é "nenhum", não um erro */
async function buscarEndereco(sinal: AbortSignal): Promise<{
    webhook: Webhook | null;
}> {
    try {
        return { webhook: await api<Webhook>('/webhook', { sinal }) };
    } catch (erro) {
        if (erro instanceof ErroDaApi && erro.status === 404)
            return { webhook: null };
        throw erro;
    }
}

function Endereco() {
    const id = useId();
    const { dados, erro, recarregar } = useDados(buscarEndereco);
    const [url, setUrl] = useState('');
    const [erroDoCampo, setErroDoCampo] = useState<string | null>(null);
    const [erroDaApi, setErroDaApi] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);
    const [novo, setNovo] = useState<WebhookNovo | null>(null);
    const [apagando, setApagando] = useState(false);
    const [confirmando, setConfirmando] = useState(false);
    const [mensagem, setMensagem] = useState<string | null>(null);
    const campo = useRef<HTMLInputElement>(null);
    const botaoApagar = useRef<HTMLButtonElement>(null);
    const caixaDoSegredo = useRef<HTMLDivElement>(null);
    const atual = dados?.webhook ?? null;

    // O segredo acabou de aparecer: o foco vai para ele, que é o que a pessoa precisa guardar agora
    useEffect(() => {
        if (novo) caixaDoSegredo.current?.focus();
    }, [novo]);

    async function salvar(e: FormEvent) {
        e.preventDefault();
        if (enviando) return;
        const endereco = url.trim();
        const problema = conferirEndereco(endereco);
        setErroDoCampo(problema);
        setErroDaApi(null);
        setMensagem(null);
        if (problema) return campo.current?.focus();
        setEnviando(true);
        try {
            const feito = await api<WebhookNovo>('/webhook', {
                metodo: 'PUT',
                corpo: { url: endereco },
            });
            setNovo(feito);
            setUrl('');
            recarregar();
        } catch (erro) {
            // Endereço que não é https público: a API diz o motivo, e a frase dela é a melhor
            setErroDaApi(mensagemDe(erro));
            campo.current?.focus();
        } finally {
            setEnviando(false);
        }
    }

    async function apagar() {
        setApagando(true);
        try {
            await api('/webhook', { metodo: 'DELETE' });
            setNovo(null);
            setMensagem(
                'Endereço apagado. Os avisos na fila foram para a fila de falhas.',
            );
        } catch (erro) {
            setMensagem(mensagemDe(erro));
        } finally {
            setApagando(false);
            setConfirmando(false);
            recarregar();
            campo.current?.focus();
        }
    }

    return (
        <section className="cartao endereco" aria-labelledby={`${id}-titulo`}>
            <h2 id={`${id}-titulo`} className="rotulo">
                Endereço de aviso
            </h2>
            {dados === null && erro === null && (
                <div className="esqueleto" style={{ height: 24 }} />
            )}
            {erro !== null && (
                <p className="alerta" role="alert">
                    {erro}
                </p>
            )}
            {dados !== null &&
                (atual ? (
                    <div className="endereco-atual">
                        <code className="url">{atual.url}</code>
                        <button
                            ref={botaoApagar}
                            type="button"
                            className="botao perigo pequeno"
                            aria-expanded={confirmando}
                            onClick={() => setConfirmando(true)}
                        >
                            Apagar
                        </button>
                    </div>
                ) : (
                    <p className="suave">
                        Nenhum endereço cadastrado: a loja não recebe avisos.
                    </p>
                ))}
            {confirmando && (
                <Confirmar
                    titulo="Apagar o endereço?"
                    texto="Os avisos param de ser enviados, e os que estão na fila vão para a fila de falhas."
                    confirmar="Sim, apagar"
                    ocupado={apagando}
                    aoConfirmar={() => void apagar()}
                    aoVoltar={() => {
                        setConfirmando(false);
                        botaoApagar.current?.focus();
                    }}
                />
            )}
            <p className={mensagem ? 'alerta ok' : 'vazio'} role="status">
                {mensagem}
            </p>

            {novo && (
                <Segredo
                    novo={novo}
                    caixa={caixaDoSegredo}
                    aoGuardar={() => setNovo(null)}
                />
            )}

            <form className="endereco-form" onSubmit={salvar} noValidate>
                {erroDaApi && (
                    <p className="alerta" role="alert">
                        {erroDaApi}
                    </p>
                )}
                <div className="campo">
                    <label htmlFor={`${id}-url`}>
                        {atual ? 'Trocar o endereço' : 'Endereço (https)'}
                    </label>
                    <input
                        ref={campo}
                        id={`${id}-url`}
                        type="url"
                        inputMode="url"
                        autoComplete="off"
                        spellCheck={false}
                        placeholder="https://minhaloja.com.br/fiel"
                        value={url}
                        onChange={(e) => setUrl(e.target.value)}
                        aria-invalid={erroDoCampo ? true : undefined}
                        aria-describedby={`${id}-url-dica`}
                    />
                    <span
                        id={`${id}-url-dica`}
                        className={erroDoCampo ? 'erro-do-campo' : 'dica'}
                    >
                        {erroDoCampo ??
                            (atual
                                ? 'Trocar gera um segredo novo; o antigo deixa de valer.'
                                : 'Precisa ser https e público.')}
                    </span>
                </div>
                <button type="submit" className="botao" disabled={enviando}>
                    {enviando
                        ? 'Conferindo...'
                        : atual
                          ? 'Trocar endereço'
                          : 'Cadastrar endereço'}
                </button>
            </form>
        </section>
    );
}

/** O que a API recusaria com um 400 genérico; "https público" quem confere é ela */
function conferirEndereco(endereco: string): string | null {
    if (endereco === '') return 'Digite o endereço.';
    try {
        const url = new URL(endereco);
        if (url.protocol !== 'https:')
            return 'O endereço precisa começar com https://.';
    } catch {
        return 'Digite o endereço completo, como https://minhaloja.com.br/fiel.';
    }
    return null;
}

/** O segredo da assinatura, uma única vez: o servidor não mostra de novo */
function Segredo({
    novo,
    caixa,
    aoGuardar,
}: {
    novo: WebhookNovo;
    caixa: RefObject<HTMLDivElement | null>;
    aoGuardar: () => void;
}) {
    const [copia, copiar] = useCopiar();
    return (
        <div
            ref={caixa}
            tabIndex={-1}
            className="segredo entrar"
            role="region"
            aria-label="Segredo da assinatura"
        >
            <p className="segredo-titulo">
                Guarde o segredo agora: ele não aparece de novo.
            </p>
            <p className="suave">
                Ele assina cada aviso para {novo.url}. Seu sistema usa o segredo
                para conferir que o aviso veio do Fiel.
            </p>
            <code className="segredo-valor">{novo.segredo}</code>
            <div className="botoes">
                <button
                    type="button"
                    className="botao pequeno"
                    onClick={() => copiar(novo.segredo)}
                >
                    Copiar o segredo
                </button>
                <button
                    type="button"
                    className="botao vazado pequeno"
                    onClick={aoGuardar}
                >
                    Já guardei
                </button>
            </div>
            <p className="suave dica-qr" aria-live="polite">
                {copia === 'copiado'
                    ? 'Segredo copiado.'
                    : copia === 'falhou'
                      ? 'Não deu para copiar. Selecione o texto acima.'
                      : ''}
            </p>
        </div>
    );
}

function Historico() {
    const id = useId();
    const [filtro, setFiltro] = useState<Filtro>('');
    const { dados, erro, recarregar } = useDados(
        (sinal) =>
            api<Aviso[]>(`/avisos${filtro ? `?estado=${filtro}` : ''}`, {
                sinal,
            }),
        ['avisos'],
    );
    // O useDados busca de novo só quando pedem: trocar o filtro é um pedido
    const primeira = useRef(true);
    useEffect(() => {
        if (primeira.current) {
            primeira.current = false;
            return;
        }
        recarregar();
    }, [filtro, recarregar]);
    const agora = useAgora(30_000);
    const [reenviando, setReenviando] = useState<number | null>(null);
    const [resultado, setResultado] = useState<{
        ok: boolean;
        texto: string;
    } | null>(null);

    async function reenviar(aviso: Aviso) {
        setReenviando(aviso.id);
        setResultado(null);
        try {
            await api(`/avisos/${aviso.id}/reenvio`, { metodo: 'POST' });
            setResultado({
                ok: true,
                texto: `Aviso #${aviso.id} voltou para a fila e sai em instantes.`,
            });
        } catch (erro) {
            setResultado({ ok: false, texto: mensagemDe(erro) });
        } finally {
            setReenviando(null);
            recarregar();
        }
    }

    return (
        <section className="historico" aria-labelledby={`${id}-titulo`}>
            <div className="loja-cabeca">
                <h2 id={`${id}-titulo`}>Histórico de entregas</h2>
            </div>
            <div className="filtros" role="group" aria-label="Mostrar avisos">
                {FILTROS.map((f) => (
                    <button
                        key={f.chave || 'todos'}
                        type="button"
                        className="filtro"
                        aria-pressed={filtro === f.chave}
                        onClick={() => setFiltro(f.chave)}
                    >
                        {f.nome}
                    </button>
                ))}
            </div>
            <p
                className={
                    resultado ? `alerta${resultado.ok ? ' ok' : ''}` : 'vazio'
                }
                role="status"
            >
                {resultado?.texto}
            </p>
            {dados === null && erro === null && (
                <div aria-busy="true" aria-label="Carregando os avisos">
                    {[0, 1, 2].map((i) => (
                        <div
                            key={i}
                            className="esqueleto"
                            style={{ height: 64, marginBottom: 8 }}
                        />
                    ))}
                </div>
            )}
            {erro !== null && (
                <p className="alerta" role="alert">
                    {erro}
                </p>
            )}
            {dados !== null && dados.length === 0 && (
                <p className="suave lista-vazia">
                    {filtro
                        ? 'Nenhum aviso com este filtro.'
                        : 'Nenhum aviso ainda. Eles aparecem aqui quando algo acontece na loja.'}
                </p>
            )}
            {dados !== null && dados.length > 0 && (
                <ul className="avisos" aria-label="Avisos">
                    {dados.map((a, i) => (
                        <LinhaDoAviso
                            key={a.id}
                            aviso={a}
                            i={i}
                            agora={agora}
                            reenviando={reenviando === a.id}
                            aoReenviar={() => void reenviar(a)}
                        />
                    ))}
                </ul>
            )}
        </section>
    );
}

function LinhaDoAviso({
    aviso: a,
    i,
    agora,
    reenviando,
    aoReenviar,
}: {
    aviso: Aviso;
    i: number;
    agora: Date;
    reenviando: boolean;
    aoReenviar: () => void;
}) {
    const mudou = useMudou(`${a.estado}-${a.tentativas}`);
    const proxima =
        a.estado === 'pendente' && a.proximaTentativaEm
            ? falta(a.proximaTentativaEm, agora)
            : null;
    return (
        <li className="entrar" style={{ ['--i' as string]: Math.min(i, 8) }}>
            <div className={`linha-aviso${mudou ? ' mudou' : ''}`}>
                <div className="aviso-cabeca">
                    <span className="aviso-tipo">
                        {NOME_DO_TIPO[a.tipo] ?? a.tipo}
                    </span>
                    <span className={`estado ${a.estado}`}>
                        {NOME_DO_AVISO[a.estado]}
                    </span>
                </div>
                <p className="aviso-detalhes suave">
                    <span>#{a.id}</span>
                    <span>{quando(a.criadoEm, agora)}</span>
                    <span>
                        {a.tentativas === 1
                            ? '1 tentativa'
                            : `${a.tentativas} tentativas`}
                    </span>
                    {a.ultimaResposta && (
                        <span>última resposta: {a.ultimaResposta}</span>
                    )}
                    {a.entregueEm && (
                        <span>entregue {quando(a.entregueEm, agora)}</span>
                    )}
                    {proxima && (
                        <span>
                            próxima tentativa{' '}
                            {proxima === 'venceu' ? 'em instantes' : proxima}
                        </span>
                    )}
                </p>
                {a.estado !== 'pendente' && (
                    <button
                        type="button"
                        className="botao vazado pequeno"
                        disabled={reenviando}
                        onClick={aoReenviar}
                        aria-label={`Reenviar o aviso #${a.id}`}
                    >
                        {reenviando ? 'Reenviando...' : 'Reenviar'}
                    </button>
                )}
            </div>
        </li>
    );
}
