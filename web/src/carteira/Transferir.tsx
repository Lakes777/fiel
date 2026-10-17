import { useId, useState, type FormEvent, type RefObject } from 'react';
import { api, ErroDaApi, mensagemDe } from '../api/cliente';
import type { DonoDaChave, Limites, TransferenciaFeita } from '../api/tipos';
import { lerReais, quando, reais } from '../comum/formatos';
import { Dialogo } from './Dialogo';
import type { IdempotenciaDoPedido } from './idempotencia';

type Passo = 'chave' | 'valor' | 'confirmar' | 'feito';

const TITULO: Record<Passo, string> = {
    chave: 'Transferir',
    valor: 'Quanto mandar',
    confirmar: 'Confira antes de mandar',
    feito: 'Transferência feita',
};

/**
 * Transferir em passos curtos: a chave (e o nome do dono, para conferir), o valor, a confirmação
 * e o comprovante. Saldo e limite são conferidos aqui antes de mandar, para a pessoa saber na hora;
 * a API confere de novo (e é ela quem decide).
 */
export function Transferir({
    saldoCentavos,
    limites,
    minhasChaves,
    idempotencia,
    aoFechar,
    aoTransferir,
    voltarPara,
}: {
    saldoCentavos: number;
    limites: Limites | null;
    minhasChaves: string[];
    idempotencia: IdempotenciaDoPedido;
    aoFechar: () => void;
    aoTransferir: () => void;
    voltarPara: RefObject<HTMLElement | null>;
}) {
    const [passo, setPasso] = useState<Passo>('chave');
    const [chave, setChave] = useState('');
    const [dono, setDono] = useState<DonoDaChave | null>(null);
    const [valorTexto, setValorTexto] = useState('');
    const [erro, setErro] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);
    const [semRede, setSemRede] = useState(false);
    const [feita, setFeita] = useState<TransferenciaFeita | null>(null);
    const idChave = useId();
    const idValor = useId();

    const disponivel = limites?.disponivelCentavos ?? saldoCentavos;
    const maximo = Math.max(0, Math.min(disponivel, saldoCentavos));
    const valor = lerReais(valorTexto);

    async function consultar(e: FormEvent) {
        e.preventDefault();
        const limpa = chave.trim();
        if (!limpa) {
            setErro('Digite a chave de quem vai receber.');
            return;
        }
        if (minhasChaves.includes(limpa)) {
            setErro('Essa chave é sua. Use a chave de outra pessoa.');
            return;
        }
        setEnviando(true);
        setErro(null);
        try {
            const achado = await api<DonoDaChave>('/chaves/consulta', {
                metodo: 'POST',
                corpo: { valor: limpa },
            });
            setChave(limpa);
            setDono(achado);
            setPasso('valor');
        } catch (erroDaConsulta) {
            setErro(
                erroDaConsulta instanceof ErroDaApi &&
                    erroDaConsulta.status === 404
                    ? 'Nenhuma carteira tem essa chave. Confira e tente de novo.'
                    : mensagemDe(erroDaConsulta),
            );
        } finally {
            setEnviando(false);
        }
    }

    function conferirValor(e: FormEvent) {
        e.preventDefault();
        if (valor === null || valor <= 0) {
            setErro('Digite um valor maior que zero, como 25,00.');
            return;
        }
        if (valor > saldoCentavos) {
            setErro(`Seu saldo é ${reais(saldoCentavos)}.`);
            return;
        }
        if (valor > disponivel) {
            setErro(
                `Passa do seu limite: agora dá para mandar até ${reais(disponivel)}.`,
            );
            return;
        }
        setErro(null);
        setSemRede(false);
        setPasso('confirmar');
    }

    async function mandar() {
        if (valor === null) return;
        setEnviando(true);
        setErro(null);
        try {
            const feitaAgora = await api<TransferenciaFeita>(
                '/transferencias',
                {
                    metodo: 'POST',
                    corpo: { chave, valorCentavos: valor },
                    idempotencia: idempotencia.para(chave, valor),
                },
            );
            idempotencia.concluir();
            setFeita(feitaAgora);
            setPasso('feito');
            aoTransferir();
        } catch (erroDoEnvio) {
            // Status 0: a rede caiu e não se sabe se saiu. O "Tentar de novo" reaproveita a chave.
            setSemRede(
                erroDoEnvio instanceof ErroDaApi && erroDoEnvio.status === 0,
            );
            setErro(mensagemDe(erroDoEnvio));
        } finally {
            setEnviando(false);
        }
    }

    function recomecar() {
        setPasso('chave');
        setChave('');
        setDono(null);
        setValorTexto('');
        setFeita(null);
        setErro(null);
        setSemRede(false);
    }

    const alerta = erro && (
        <p className="alerta" role="alert">
            {erro}
        </p>
    );

    return (
        <Dialogo
            titulo={TITULO[passo]}
            passo={passo}
            aoFechar={aoFechar}
            voltarPara={voltarPara}
        >
            <ol className="passos" aria-label="Passos">
                {(['chave', 'valor', 'confirmar', 'feito'] as Passo[]).map(
                    (p, i) => (
                        <li
                            key={p}
                            className={p === passo ? 'atual' : undefined}
                            aria-current={p === passo ? 'step' : undefined}
                        >
                            <span className="so-leitor">Passo {i + 1}</span>
                        </li>
                    ),
                )}
            </ol>

            {passo === 'chave' && (
                <form onSubmit={(e) => void consultar(e)} noValidate>
                    <div className="campo">
                        <label htmlFor={idChave}>
                            Chave de quem vai receber
                        </label>
                        <input
                            id={idChave}
                            value={chave}
                            onChange={(e) => {
                                setChave(e.target.value);
                                setErro(null);
                            }}
                            autoComplete="off"
                            autoCapitalize="none"
                            spellCheck={false}
                            placeholder="e-mail ou chave aleatória"
                        />
                    </div>
                    {alerta}
                    <button
                        type="submit"
                        className="botao largo"
                        disabled={enviando}
                    >
                        {enviando ? 'Procurando…' : 'Continuar'}
                    </button>
                </form>
            )}

            {passo === 'valor' && dono && (
                <form onSubmit={conferirValor} noValidate>
                    <p className="para">
                        Para <b>{dono.nome}</b>
                        <span className="suave quebra"> · {chave}</span>
                    </p>
                    <div className="campo">
                        <label htmlFor={idValor}>Valor</label>
                        <input
                            id={idValor}
                            className="numero"
                            value={valorTexto}
                            onChange={(e) => {
                                setValorTexto(e.target.value);
                                setErro(null);
                            }}
                            inputMode="decimal"
                            autoComplete="off"
                            placeholder="0,00"
                        />
                        <span className="dica">
                            Você pode mandar até {reais(maximo)} agora
                            {maximo < disponivel ? ' (seu saldo)' : ''}.
                        </span>
                    </div>
                    {alerta}
                    <div className="botoes">
                        <button
                            type="button"
                            className="botao vazado"
                            onClick={() => {
                                setErro(null);
                                setPasso('chave');
                            }}
                        >
                            Voltar
                        </button>
                        <button type="submit" className="botao">
                            Continuar
                        </button>
                    </div>
                </form>
            )}

            {passo === 'confirmar' && dono && valor !== null && (
                <div>
                    <p className="numero-grande valor-confirmar">
                        {reais(valor)}
                    </p>
                    <p className="para">
                        Para <b>{dono.nome}</b>
                        <span className="suave quebra"> · {chave}</span>
                    </p>
                    {alerta}
                    <div className="botoes">
                        <button
                            type="button"
                            className="botao vazado"
                            disabled={enviando}
                            onClick={() => {
                                setErro(null);
                                setPasso('valor');
                            }}
                        >
                            Voltar
                        </button>
                        <button
                            type="button"
                            className="botao"
                            data-foco
                            disabled={enviando}
                            onClick={() => void mandar()}
                        >
                            {enviando
                                ? 'Mandando…'
                                : semRede
                                  ? 'Tentar de novo'
                                  : 'Confirmar'}
                        </button>
                    </div>
                    {semRede && (
                        <p className="dica-rede suave">
                            Pode tentar de novo sem medo: se a primeira chegou,
                            o dinheiro não sai duas vezes.
                        </p>
                    )}
                </div>
            )}

            {passo === 'feito' && feita && (
                <div className="comprovante entrar" role="status">
                    <p className="rotulo">Comprovante</p>
                    <p className="numero-grande">
                        {reais(feita.valorCentavos)}
                    </p>
                    <dl>
                        <div>
                            <dt>Para</dt>
                            <dd>{feita.para}</dd>
                        </div>
                        <div>
                            <dt>Quando</dt>
                            <dd>{quando(feita.em)}</dd>
                        </div>
                        <div>
                            <dt>Número</dt>
                            <dd className="numero">{feita.id}</dd>
                        </div>
                    </dl>
                    <div className="botoes">
                        <button
                            type="button"
                            className="botao vazado"
                            onClick={recomecar}
                        >
                            Nova transferência
                        </button>
                        <button
                            type="button"
                            className="botao"
                            data-foco
                            onClick={aoFechar}
                        >
                            Pronto
                        </button>
                    </div>
                </div>
            )}
        </Dialogo>
    );
}
