import { useId, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api, mensagemDe } from '../api/cliente';
import type { CobrancaDoLojista } from '../api/tipos';
import { lerReais, reais } from '../comum/formatos';
import { useTitulo } from '../comum/titulo';

/** O máximo de uma cobrança, igual ao da API */
export const MAXIMO_DA_COBRANCA = 500_000;
const MAXIMO_DA_DESCRICAO = 140;

const VALIDADES = [
    { minutos: 15, nome: '15 minutos' },
    { minutos: 30, nome: '30 minutos' },
    { minutos: 60, nome: '1 hora' },
    { minutos: 1440, nome: '1 dia' },
];

interface Erros {
    valor?: string;
    descricao?: string;
}

/** O que a API recusaria com um 400 genérico, dito antes e com a frase certa */
function conferir(valorDigitado: string, descricao: string): Erros {
    const erros: Erros = {};
    const valor = lerReais(valorDigitado);
    if (valor === null) erros.valor = 'Digite um valor, como 34,00.';
    else if (valor <= 0) erros.valor = 'O valor precisa ser maior que zero.';
    else if (valor > MAXIMO_DA_COBRANCA)
        erros.valor = `O máximo de uma cobrança é ${reais(MAXIMO_DA_COBRANCA)}.`;
    if (descricao.trim() === '')
        erros.descricao = 'Escreva o que está sendo cobrado.';
    else if (descricao.trim().length > MAXIMO_DA_DESCRICAO)
        erros.descricao = `Use até ${MAXIMO_DA_DESCRICAO} caracteres.`;
    return erros;
}

/** /loja/nova: cria a cobrança e abre o detalhe, com o QR se desenhando */
export function NovaCobranca() {
    useTitulo('Nova cobrança');
    const id = useId();
    const navegar = useNavigate();
    const [valor, setValor] = useState('');
    const [descricao, setDescricao] = useState('');
    const [validade, setValidade] = useState(30);
    const [erros, setErros] = useState<Erros>({});
    const [erroDaApi, setErroDaApi] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);
    const campoValor = useRef<HTMLInputElement>(null);
    const campoDescricao = useRef<HTMLInputElement>(null);

    async function criar(e: FormEvent) {
        e.preventDefault();
        if (enviando) return;
        const achados = conferir(valor, descricao);
        setErros(achados);
        setErroDaApi(null);
        if (achados.valor) return campoValor.current?.focus();
        if (achados.descricao) return campoDescricao.current?.focus();
        setEnviando(true);
        try {
            const criada = await api<CobrancaDoLojista>('/cobrancas', {
                metodo: 'POST',
                corpo: {
                    valorCentavos: lerReais(valor),
                    descricao: descricao.trim(),
                    validadeMinutos: validade,
                },
            });
            // A cobrança vai junto: o detalhe já abre com o QR, sem esperar outra busca
            navegar(`/loja/cobrancas/${criada.id}`, {
                state: { criada },
            });
        } catch (erro) {
            setErroDaApi(mensagemDe(erro));
            setEnviando(false);
        }
    }

    const restam = MAXIMO_DA_DESCRICAO - descricao.trim().length;
    return (
        <section className="nova" aria-labelledby={`${id}-titulo`}>
            <h1 id={`${id}-titulo`}>Nova cobrança</h1>
            <p className="suave">
                Quem for pagar lê o QR na carteira dele. O dinheiro cai na sua
                carteira na hora.
            </p>
            <form className="cartao" onSubmit={criar} noValidate>
                {erroDaApi && (
                    <p className="alerta" role="alert">
                        {erroDaApi}
                    </p>
                )}
                <div className="campo">
                    <label htmlFor={`${id}-valor`}>Valor</label>
                    <div className="com-prefixo">
                        <span aria-hidden="true">R$</span>
                        <input
                            ref={campoValor}
                            id={`${id}-valor`}
                            inputMode="decimal"
                            autoComplete="off"
                            placeholder="0,00"
                            value={valor}
                            onChange={(e) => setValor(e.target.value)}
                            aria-invalid={erros.valor ? true : undefined}
                            aria-describedby={`${id}-valor-dica`}
                        />
                    </div>
                    <span
                        id={`${id}-valor-dica`}
                        className={erros.valor ? 'erro-do-campo' : 'dica'}
                    >
                        {erros.valor ?? `Até ${reais(MAXIMO_DA_COBRANCA)}.`}
                    </span>
                </div>
                <div className="campo">
                    <label htmlFor={`${id}-descricao`}>Descrição</label>
                    <input
                        ref={campoDescricao}
                        id={`${id}-descricao`}
                        autoComplete="off"
                        placeholder="Caneca esmaltada"
                        maxLength={MAXIMO_DA_DESCRICAO + 20}
                        value={descricao}
                        onChange={(e) => setDescricao(e.target.value)}
                        aria-invalid={erros.descricao ? true : undefined}
                        aria-describedby={`${id}-descricao-dica`}
                    />
                    <span
                        id={`${id}-descricao-dica`}
                        className={erros.descricao ? 'erro-do-campo' : 'dica'}
                    >
                        {erros.descricao ??
                            `Quem paga vê este texto. ${restam >= 0 ? `Restam ${restam} caracteres.` : ''}`}
                    </span>
                </div>
                <div className="campo">
                    <label htmlFor={`${id}-validade`}>Vale por</label>
                    <select
                        id={`${id}-validade`}
                        value={validade}
                        onChange={(e) => setValidade(Number(e.target.value))}
                    >
                        {VALIDADES.map((v) => (
                            <option key={v.minutos} value={v.minutos}>
                                {v.nome}
                            </option>
                        ))}
                    </select>
                    <span className="dica">
                        Depois disso o QR deixa de valer.
                    </span>
                </div>
                <button type="submit" className="botao" disabled={enviando}>
                    {enviando ? 'Criando...' : 'Criar cobrança'}
                </button>
            </form>
        </section>
    );
}
