import { api } from './cliente';
import type { Usuario } from './tipos';

/** Quem está logado nesta aba. `carregando` enquanto a primeira consulta a /eu não voltou. */
export interface EstadoDaSessao {
    carregando: boolean;
    usuario: Usuario | null;
}

let estado: EstadoDaSessao = { carregando: true, usuario: null };
const ouvintes = new Set<() => void>();
let consulta: Promise<void> | null = null;

function mudar(novo: EstadoDaSessao): void {
    estado = novo;
    ouvintes.forEach((o) => o());
}

export function assinarSessao(ouvinte: () => void): () => void {
    ouvintes.add(ouvinte);
    if (estado.carregando) void carregarSessao();
    return () => ouvintes.delete(ouvinte);
}

export function estadoDaSessao(): EstadoDaSessao {
    return estado;
}

/** Pergunta ao servidor quem está logado (o cookie é HttpOnly: só ele sabe). Uma vez por aba. */
export function carregarSessao(): Promise<void> {
    consulta ??= api<Usuario>('/eu')
        .then((usuario) => mudar({ carregando: false, usuario }))
        // Se a pessoa entrou enquanto esta consulta voltava, o login dela vale (não desloga a aba)
        .catch(() =>
            mudar({
                carregando: false,
                usuario: estado.carregando ? null : estado.usuario,
            }),
        );
    return consulta;
}

export async function entrar(email: string, senha: string): Promise<Usuario> {
    const usuario = await api<Usuario>('/sessao', {
        metodo: 'POST',
        corpo: { email, senha },
    });
    mudar({ carregando: false, usuario });
    return usuario;
}

/** Só cria a conta: o cadastro não abre sessão, quem chama entra depois (e trata se isso falhar) */
export async function cadastrar(
    nome: string,
    email: string,
    senha: string,
): Promise<Usuario> {
    return api<Usuario>('/cadastro', {
        metodo: 'POST',
        corpo: { nome, email, senha },
    });
}

export async function sair(): Promise<void> {
    try {
        await api<void>('/sessao', { metodo: 'DELETE' });
    } finally {
        mudar({ carregando: false, usuario: null });
    }
}

/** Uma resposta 401 no meio do uso: a sessão acabou no servidor */
export function avisarSessaoEncerrada(): void {
    if (estado.usuario) mudar({ carregando: false, usuario: null });
}

/** A próxima carregarSessao pergunta ao servidor de novo (o canal ao vivo foi recusado) */
export function reiniciarConsultaDaSessao(): void {
    consulta = null;
}

/** Só para os testes: deixa a aba logada (ou não) sem falar com o servidor */
export function aplicar(usuario: Usuario | null): void {
    consulta = Promise.resolve();
    mudar({ carregando: false, usuario });
}

/** Só para os testes */
export function reiniciarSessaoParaTeste(): void {
    consulta = null;
    estado = { carregando: true, usuario: null };
    ouvintes.clear();
}
