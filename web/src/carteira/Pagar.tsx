import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, ErroDaApi, mensagemDe } from '../api/cliente';
import type {
    CobrancaParaPagar,
    EstadoDaCobranca,
    PagamentoFeito,
} from '../api/tipos';
import { falta, NOME_DO_ESTADO, quando, reais } from '../comum/formatos';
import { useTitulo } from '../comum/titulo';
import './carteira.css';

/** Por que uma cobrança que não está pendente não pode ser paga, numa frase */
const POR_QUE_NAO: Record<Exclude<EstadoDaCobranca, 'pendente'>, string> = {
    paga: 'Esta cobrança já foi paga.',
    parcialmente_estornada: 'Esta cobrança já foi paga (e estornada em parte).',
    estornada: 'Esta cobrança já foi paga e depois estornada.',
    expirada: 'Esta cobrança venceu. Peça um QR novo a quem cobrou.',
    cancelada: 'Quem cobrou cancelou esta cobrança.',
};

/**
 * Pagar um QR: a pessoa cola o texto do QR (não há câmera), confere os dados que o SERVIDOR
 * devolve (não os escritos no QR, que qualquer um pode montar) e paga. Pagar de novo é seguro sem
 * Idempotency-Key: a API trava a cobrança, e quem já pagou recebe o mesmo pagamento.
 */
export function Pagar() {
    useTitulo('Pagar com QR');
    const [busca] = useSearchParams();
    const doLink = busca.get('qr')?.trim() ?? '';
    const [qr, setQr] = useState(doLink);
    const [cobranca, setCobranca] = useState<CobrancaParaPagar | null>(null);
    // O QR que gerou a `cobranca` na tela: é ele que se paga, não o texto que está no campo agora
    const [qrConferido, setQrConferido] = useState('');
    const consulta = useRef(0);
    const [paga, setPaga] = useState<PagamentoFeito | null>(null);
    const [erro, setErro] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);
    const idCampo = useId();
    const campo = useRef<HTMLTextAreaElement>(null);

    async function consultar(texto: string) {
        const limpo = texto.trim();
        if (!limpo) {
            setErro('Cole o código do QR para continuar.');
            return;
        }
        if (!limpo.startsWith('0004FIEL')) {
            setErro(
                'Este código não é de uma cobrança do Fiel. Confira se copiou o texto inteiro.',
            );
            return;
        }
        // Só a resposta da última consulta vale: uma mais velha que chegue atrasada é descartada
        const esta = ++consulta.current;
        setEnviando(true);
        setErro(null);
        try {
            const achada = await api<CobrancaParaPagar>(
                '/pagamentos/consulta',
                {
                    metodo: 'POST',
                    corpo: { qr: limpo },
                },
            );
            if (esta !== consulta.current) return;
            setCobranca(achada);
            setQrConferido(limpo);
        } catch (e) {
            if (esta !== consulta.current) return;
            setErro(
                e instanceof ErroDaApi && e.status === 404
                    ? 'Nenhuma cobrança com este código.'
                    : mensagemDe(e),
            );
        } finally {
            setEnviando(false);
        }
    }

    // Veio de um link com ?qr= (o painel do lojista tem um, para testar com outra conta): já confere
    const conferiuDoLink = useRef(false);
    useEffect(() => {
        if (doLink && !conferiuDoLink.current) {
            conferiuDoLink.current = true;
            void consultar(doLink);
        }
    }, [doLink]);

    async function pagar() {
        setEnviando(true);
        setErro(null);
        try {
            setPaga(
                await api<PagamentoFeito>('/pagamentos', {
                    metodo: 'POST',
                    corpo: { qr: qrConferido },
                }),
            );
        } catch (e) {
            if (e instanceof ErroDaApi && e.status === 409) {
                // Mudou de estado depois da conferência: paga por outra pessoa, cancelada ou vencida.
                // Consulta de novo para mostrar o estado de verdade
                setErro(
                    'A cobrança mudou enquanto você conferia. Nada foi pago.',
                );
                try {
                    setCobranca(
                        await api<CobrancaParaPagar>('/pagamentos/consulta', {
                            metodo: 'POST',
                            corpo: { qr: qrConferido },
                        }),
                    );
                } catch {
                    // Sem o estado novo, volta para o "Conferir" em vez de chutar um
                    setCobranca(null);
                }
            } else {
                setErro(mensagemDe(e));
            }
        } finally {
            setEnviando(false);
        }
    }

    function outro() {
        setQr('');
        setQrConferido('');
        setCobranca(null);
        setPaga(null);
        setErro(null);
        // Depois de trocar a tela, o foco vai para o campo vazio
        setTimeout(() => campo.current?.focus());
    }

    const vencida =
        cobranca?.estado === 'pendente' &&
        falta(cobranca.expiraEm) === 'venceu';
    const podePagar = cobranca?.estado === 'pendente' && !vencida;

    return (
        <main className="pagina tela-pagar">
            <h1>Pagar com QR</h1>
            <div className="cartao caixa-pagar">
                {paga ? (
                    <div className="comprovante entrar" role="status">
                        <p className="rotulo">Pago</p>
                        <p className="numero-grande">
                            {reais(paga.valorCentavos)}
                        </p>
                        <dl>
                            <div>
                                <dt>Para</dt>
                                <dd>{paga.recebedor}</dd>
                            </div>
                            <div>
                                <dt>O quê</dt>
                                <dd>{paga.descricao}</dd>
                            </div>
                            <div>
                                <dt>Quando</dt>
                                <dd>{quando(paga.pagaEm)}</dd>
                            </div>
                        </dl>
                        <div className="botoes esticados">
                            <button
                                type="button"
                                className="botao vazado"
                                onClick={outro}
                            >
                                Pagar outro
                            </button>
                            <Link className="botao" to="/carteira">
                                Ver a carteira
                            </Link>
                        </div>
                    </div>
                ) : (
                    <>
                        <form
                            onSubmit={(e: FormEvent) => {
                                e.preventDefault();
                                void consultar(qr);
                            }}
                            noValidate
                        >
                            <div className="campo">
                                <label htmlFor={idCampo}>
                                    Cole o código do QR
                                </label>
                                <textarea
                                    id={idCampo}
                                    ref={campo}
                                    className="codigo-qr"
                                    rows={3}
                                    value={qr}
                                    readOnly={enviando}
                                    onChange={(e) => {
                                        consulta.current++;
                                        setQr(e.target.value);
                                        setCobranca(null);
                                        setErro(null);
                                    }}
                                    spellCheck={false}
                                    autoCapitalize="none"
                                    autoComplete="off"
                                />
                                <span className="dica">
                                    Ainda não dá para ler com a câmera. Quem
                                    cobra pode copiar o texto do QR e mandar
                                    para você.
                                </span>
                            </div>
                            {!cobranca && (
                                <button
                                    type="submit"
                                    className="botao"
                                    disabled={enviando}
                                >
                                    {enviando ? 'Conferindo…' : 'Conferir'}
                                </button>
                            )}
                        </form>
                        <div aria-live="polite">
                            {cobranca && (
                                <section
                                    className="conferir entrar"
                                    aria-label="Cobrança"
                                >
                                    <p className="numero-grande">
                                        {reais(cobranca.valorCentavos)}
                                    </p>
                                    <p className="descricao-cobranca">
                                        {cobranca.descricao}
                                    </p>
                                    <p className="para">
                                        Para <b>{cobranca.recebedor}</b>
                                    </p>
                                    <p className="linha-estado">
                                        <span
                                            className={`estado ${vencida ? 'expirada' : cobranca.estado}`}
                                        >
                                            {vencida
                                                ? NOME_DO_ESTADO.expirada
                                                : NOME_DO_ESTADO[
                                                      cobranca.estado
                                                  ]}
                                        </span>
                                        {podePagar && (
                                            <span className="suave">
                                                vence {falta(cobranca.expiraEm)}
                                            </span>
                                        )}
                                    </p>
                                    {!podePagar && (
                                        <p className="alerta">
                                            {vencida ||
                                            cobranca.estado === 'pendente'
                                                ? POR_QUE_NAO.expirada
                                                : POR_QUE_NAO[cobranca.estado]}
                                        </p>
                                    )}
                                </section>
                            )}
                        </div>
                        {erro && (
                            <p className="alerta" role="alert">
                                {erro}
                            </p>
                        )}
                        {podePagar && cobranca && (
                            <button
                                type="button"
                                className="botao largo"
                                disabled={enviando}
                                onClick={() => void pagar()}
                            >
                                {enviando
                                    ? 'Pagando…'
                                    : `Pagar ${reais(cobranca.valorCentavos)}`}
                            </button>
                        )}
                    </>
                )}
            </div>
            <p className="suave volta">
                <Link to="/carteira">Voltar para a carteira</Link>
            </p>
        </main>
    );
}
