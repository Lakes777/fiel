import { useSyncExternalStore } from 'react';

export type Tema = 'claro' | 'escuro';

const CHAVE = 'fiel-tema';
const ouvintes = new Set<() => void>();

function atual(): Tema {
    return document.documentElement.dataset.tema === 'escuro'
        ? 'escuro'
        : 'claro';
}

/** Na primeira visita vale o tema do aparelho (o script do index.html já aplicou); depois, a escolha */
export function trocarTema(tema: Tema): void {
    document.documentElement.dataset.tema = tema;
    try {
        localStorage.setItem(CHAVE, tema);
    } catch {
        // aba anônima com armazenamento bloqueado: troca só nesta visita
    }
    ouvintes.forEach((o) => o());
}

export function useTema(): Tema {
    return useSyncExternalStore(
        (o) => {
            ouvintes.add(o);
            return () => ouvintes.delete(o);
        },
        atual,
        () => 'claro',
    );
}

const LUA = (
    <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        aria-hidden="true"
    >
        <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />
    </svg>
);
const SOL = (
    <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        aria-hidden="true"
    >
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
);

/** A lua (no claro) ou o sol (no escuro), no canto do topo */
export function BotaoTema() {
    const tema = useTema();
    const outro: Tema = tema === 'escuro' ? 'claro' : 'escuro';
    return (
        <button
            type="button"
            className="botao-icone"
            onClick={() => trocarTema(outro)}
            aria-label={`Usar tema ${outro}`}
            title={`Tema ${outro}`}
        >
            {tema === 'escuro' ? SOL : LUA}
        </button>
    );
}
