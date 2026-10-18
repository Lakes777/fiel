import { useEffect, useState } from 'react';
import { api } from './cliente';
import type { Usuario } from './tipos';

/** O que a demonstração sugere ao visitante (vem do POST /demo/visitante). */
export interface Roteiro {
    qrDaLoja: string;
    chaveDaAna: string;
    chaveDoBob: string;
}

export interface VisitanteCriado {
    eu: Usuario;
    roteiro: Roteiro;
}

const CHAVE = 'fiel.roteiro';

/**
 * Conta de visitante da demonstração: o e-mail que a API dá a ela, com a mesma regra dela. O
 * cadastro recusa e-mails @demo.fiel, então só um visitante de verdade tem um assim.
 */
const VISITANTE = /^visitante-[0-9a-f]{8}@demo\.fiel$/;

export function ehVisitante(usuario: Usuario | null): boolean {
    return usuario ? VISITANTE.test(usuario.email) : false;
}

/** O roteiro fica nesta aba: some ao fechar, como a conta descartável. Sem armazenamento, não há roteiro. */
export function guardarRoteiro(roteiro: Roteiro | null): void {
    try {
        if (roteiro) sessionStorage.setItem(CHAVE, JSON.stringify(roteiro));
        else sessionStorage.removeItem(CHAVE);
    } catch {
        // Navegador sem sessionStorage (modo restrito): a demonstração funciona sem o roteiro
    }
}

export function lerRoteiro(): Roteiro | null {
    try {
        const guardado = sessionStorage.getItem(CHAVE);
        return guardado ? (JSON.parse(guardado) as Roteiro) : null;
    } catch {
        return null;
    }
}

let consulta: Promise<boolean> | null = null;

/**
 * Se este servidor tem a demonstração. Uma pergunta por aba; erro conta como "não tem" só desta
 * vez: a próxima tela que perguntar tenta de novo (a API pode ter piscado).
 */
export function demoAtiva(): Promise<boolean> {
    consulta ??= api<{ ativa: boolean }>('/demo')
        .then((r) => r.ativa)
        .catch(() => {
            consulta = null;
            return false;
        });
    return consulta;
}

export function useDemoAtiva(): boolean {
    const [ativa, setAtiva] = useState(false);
    useEffect(() => {
        let viva = true;
        void demoAtiva().then((v) => {
            if (viva) setAtiva(v);
        });
        return () => {
            viva = false;
        };
    }, []);
    return ativa;
}

/** O Bob do visitante paga uma cobrança que ele criou. O painel atualiza sozinho, pelo ao vivo. */
export function pedirParaOBobPagar(cobrancaId: number): Promise<unknown> {
    return api(`/demo/cobrancas/${cobrancaId}/bob-paga`, { metodo: 'POST' });
}

/** Só para os testes */
export function reiniciarDemoParaTeste(): void {
    consulta = null;
    guardarRoteiro(null);
}
