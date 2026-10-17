import {
    useEffect,
    useId,
    useRef,
    useState,
    type FormEvent,
    type RefObject,
} from 'react';
import { ErroDaApi, api, mensagemDe, novaIdempotencia } from '../api/cliente';
import type { CobrancaDoLojista, EstornoFeito } from '../api/tipos';
import { lerReais, reais } from '../comum/formatos';

const MAXIMO_DO_MOTIVO = 140;

/** Um estorno pedido que ainda não teve resposta certa: a chave e o que foi pedido com ela */
export interface PedidoDeEstorno {
    chave: string;
    assinatura: string;
}

/** "R$ 34,00" -> "34,00": o campo já vem com o que falta devolver, pronto para editar */
function semMoeda(centavos: number): string {
    return reais(centavos).replace('R$ ', '');
}

/**
 * Devolve a quem pagou, tudo ou uma parte. O valor começa no que falta devolver; mais do que isso a
 * tela barra antes (a API responderia 422).
 */
export function FormularioDeEstorno({
    cobranca,
    pedido,
    aoFeito,
    aoErro,
    aoFechar,
}: {
    cobranca: CobrancaDoLojista;
    pedido: RefObject<PedidoDeEstorno | null>;
    aoFeito: (estorno: EstornoFeito) => void;
    /** A cobrança pode ter mudado (estornada por outra aba): quem chama busca de novo */
    aoErro: () => void;
    aoFechar: () => void;
}) {
    const id = useId();
    const resta = cobranca.valorCentavos - cobranca.estornadoCentavos;
    const [valor, setValor] = useState(() => semMoeda(resta));
    const [motivo, setMotivo] = useState('');
    const [erroValor, setErroValor] = useState<string | null>(null);
    const [erroMotivo, setErroMotivo] = useState<string | null>(null);
    const [erroDaApi, setErroDaApi] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);
    const campoValor = useRef<HTMLInputElement>(null);
    const campoMotivo = useRef<HTMLInputElement>(null);
    useEffect(() => campoValor.current?.focus(), []);

    async function estornar(e: FormEvent) {
        e.preventDefault();
        if (enviando) return;
        const centavos = lerReais(valor);
        const texto = motivo.trim();
        const problemaValor =
            centavos === null
                ? 'Digite um valor, como 10,00.'
                : centavos <= 0
                  ? 'O valor precisa ser maior que zero.'
                  : centavos > resta
                    ? `Só falta devolver ${reais(resta)}.`
                    : null;
        const problemaMotivo =
            texto.length > MAXIMO_DO_MOTIVO
                ? `Use até ${MAXIMO_DO_MOTIVO} caracteres.`
                : null;
        setErroValor(problemaValor);
        setErroMotivo(problemaMotivo);
        setErroDaApi(null);
        if (problemaValor) return campoValor.current?.focus();
        if (problemaMotivo) return campoMotivo.current?.focus();

        const corpo = { valorCentavos: centavos, motivo: texto || null };
        const assinatura = JSON.stringify(corpo);
        // A Idempotency-Key é do PEDIDO, não do clique. Depois de uma queda de rede não dá para
        // saber se o estorno chegou ao servidor; tentar de novo com a MESMA chave faz o servidor
        // devolver o resultado do primeiro (Idempotent-Replayed) em vez de devolver o dinheiro duas
        // vezes. Se a pessoa mudou o valor ou o motivo, é outro pedido: chave nova (a velha com
        // outro corpo levaria 422).
        if (pedido.current?.assinatura !== assinatura)
            pedido.current = { chave: novaIdempotencia(), assinatura };
        setEnviando(true);
        try {
            const feito = await api<EstornoFeito>(
                `/cobrancas/${cobranca.id}/estornos`,
                {
                    metodo: 'POST',
                    corpo,
                    idempotencia: pedido.current.chave,
                },
            );
            // Feito: o próximo estorno é outro pedido
            pedido.current = null;
            aoFeito(feito);
        } catch (erro) {
            // O servidor respondeu (409, 422...): o pedido não aconteceu, e a chave pode ir embora.
            // Sem resposta (status 0): a chave fica para a próxima tentativa
            if (!(erro instanceof ErroDaApi && erro.status === 0))
                pedido.current = null;
            setErroDaApi(mensagemDe(erro));
            setEnviando(false);
            aoErro();
        }
    }

    return (
        <form
            className="estornar cartao entrar"
            onSubmit={estornar}
            noValidate
            aria-labelledby={`${id}-titulo`}
            onKeyDown={(e) => {
                if (e.key === 'Escape' && !enviando) aoFechar();
            }}
        >
            <h2 id={`${id}-titulo`} className="rotulo">
                Estornar
            </h2>
            <p className="suave estornar-texto">
                O dinheiro sai da sua carteira e volta para quem pagou. Falta
                devolver <b className="numero">{reais(resta)}</b>.
            </p>
            {erroDaApi && (
                <p className="alerta" role="alert">
                    {erroDaApi}
                </p>
            )}
            <div className="campo">
                <label htmlFor={`${id}-valor`}>Valor a devolver</label>
                <div className="com-prefixo">
                    <span aria-hidden="true">R$</span>
                    <input
                        ref={campoValor}
                        id={`${id}-valor`}
                        inputMode="decimal"
                        autoComplete="off"
                        value={valor}
                        onChange={(e) => setValor(e.target.value)}
                        aria-invalid={erroValor ? true : undefined}
                        aria-describedby={`${id}-valor-dica`}
                    />
                </div>
                <span
                    id={`${id}-valor-dica`}
                    className={erroValor ? 'erro-do-campo' : 'dica'}
                >
                    {erroValor ?? 'Pode ser só uma parte.'}
                </span>
            </div>
            <div className="campo">
                <label htmlFor={`${id}-motivo`}>Motivo (opcional)</label>
                <input
                    ref={campoMotivo}
                    id={`${id}-motivo`}
                    autoComplete="off"
                    placeholder="Produto com defeito"
                    maxLength={MAXIMO_DO_MOTIVO + 20}
                    value={motivo}
                    onChange={(e) => setMotivo(e.target.value)}
                    aria-invalid={erroMotivo ? true : undefined}
                    aria-describedby={`${id}-motivo-dica`}
                />
                <span
                    id={`${id}-motivo-dica`}
                    className={erroMotivo ? 'erro-do-campo' : 'dica'}
                >
                    {erroMotivo ?? `Até ${MAXIMO_DO_MOTIVO} caracteres.`}
                </span>
            </div>
            <div className="botoes">
                <button
                    type="submit"
                    className="botao perigo"
                    disabled={enviando}
                >
                    {enviando ? 'Estornando...' : 'Confirmar estorno'}
                </button>
                <button
                    type="button"
                    className="botao vazado"
                    disabled={enviando}
                    onClick={aoFechar}
                >
                    Voltar
                </button>
            </div>
        </form>
    );
}
