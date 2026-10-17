import { avisarSessaoEncerrada } from './sessao';

/** Um erro da API pronto para mostrar: `message` já é uma frase para a pessoa ler */
export class ErroDaApi extends Error {
    constructor(
        readonly status: number,
        mensagem: string,
    ) {
        super(mensagem);
        this.name = 'ErroDaApi';
    }
}

export interface Opcoes {
    metodo?: 'GET' | 'POST' | 'PUT' | 'DELETE';
    corpo?: unknown;
    /** Para transferência e estorno: o mesmo valor em todas as tentativas do mesmo pedido */
    idempotencia?: string;
    sinal?: AbortSignal;
}

/** Tudo da API passa por /api (o proxy tira o prefixo) */
export const BASE = '/api';

/**
 * Chama a API na mesma origem, com o cookie da sessão. Pedido que muda algo leva o token CSRF
 * (cookie XSRF-TOKEN devolvido no cabeçalho X-XSRF-TOKEN); sem o cookie ainda, busca antes em
 * /csrf. Resposta de erro (problem+json) vira ErroDaApi.
 */
export async function api<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
    const metodo = opcoes.metodo ?? 'GET';
    const cabecalhos: Record<string, string> = {};
    if (metodo !== 'GET') {
        cabecalhos['X-XSRF-TOKEN'] = await tokenCsrf();
        cabecalhos['Content-Type'] = 'application/json';
    }
    if (opcoes.idempotencia)
        cabecalhos['Idempotency-Key'] = opcoes.idempotencia;
    let resposta: Response;
    try {
        resposta = await fetch(BASE + caminho, {
            method: metodo,
            headers: cabecalhos,
            body:
                opcoes.corpo === undefined
                    ? undefined
                    : JSON.stringify(opcoes.corpo),
            credentials: 'same-origin',
            signal: opcoes.sinal,
        });
    } catch (erro) {
        if (erro instanceof DOMException && erro.name === 'AbortError')
            throw erro;
        throw new ErroDaApi(0, 'Sem conexão com o servidor. Tente de novo.');
    }
    if (!resposta.ok) {
        // A sessão acabou (15 min parada, ou saiu em outro aparelho): a tela volta para o login
        if (
            resposta.status === 401 &&
            caminho !== '/sessao' &&
            caminho !== '/eu'
        )
            avisarSessaoEncerrada();
        throw await erroDe(resposta);
    }
    if (resposta.status === 204) return undefined as T;
    return (await resposta.json()) as T;
}

/** Um identificador novo para um pedido que não pode acontecer duas vezes */
export function novaIdempotencia(): string {
    return crypto.randomUUID();
}

function lerCookie(nome: string): string | null {
    const achado = document.cookie
        .split('; ')
        .find((c) => c.startsWith(`${nome}=`));
    return achado ? decodeURIComponent(achado.slice(nome.length + 1)) : null;
}

async function tokenCsrf(): Promise<string> {
    const atual = lerCookie('XSRF-TOKEN');
    if (atual) return atual;
    try {
        const { token } = (await (
            await fetch(`${BASE}/csrf`, { credentials: 'same-origin' })
        ).json()) as {
            token: string;
        };
        return lerCookie('XSRF-TOKEN') ?? token;
    } catch {
        throw new ErroDaApi(0, 'Sem conexão com o servidor. Tente de novo.');
    }
}

const PADRAO: Record<number, string> = {
    400: 'Confira os dados e tente de novo.',
    401: 'Sua sessão terminou. Entre de novo.',
    403: 'Pedido recusado. Recarregue a página e tente de novo.',
    404: 'Não encontrado.',
    429: 'Muitas tentativas. Espere um pouco e tente de novo.',
};

/** As mensagens do Fiel vêm em minúscula ("saldo insuficiente: ..."); a tela mostra como frase */
function comoFrase(texto: string): string {
    const frase = texto.charAt(0).toUpperCase() + texto.slice(1);
    return /[.!?]$/.test(frase) ? frase : `${frase}.`;
}

async function erroDe(resposta: Response): Promise<ErroDaApi> {
    let detalhe: unknown;
    try {
        detalhe = ((await resposta.json()) as { detail?: unknown }).detail;
    } catch {
        // corpo vazio ou não JSON: fica a mensagem padrão
    }
    // O Spring responde validação com uma frase em inglês ("Invalid request content."): não serve
    const dele =
        typeof detalhe === 'string' && !/^Invalid |^Failed to /.test(detalhe)
            ? comoFrase(detalhe)
            : null;
    const mensagem =
        dele ?? PADRAO[resposta.status] ?? 'Algo deu errado. Tente de novo.';
    return new ErroDaApi(resposta.status, mensagem);
}

/** A mensagem para mostrar de qualquer erro pego num catch */
export function mensagemDe(erro: unknown): string {
    if (erro instanceof ErroDaApi) return erro.message;
    return 'Algo deu errado. Tente de novo.';
}
