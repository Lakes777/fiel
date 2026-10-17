import { useEffect } from 'react';

/** O título da aba: "Carteira · Fiel" */
export function useTitulo(titulo?: string): void {
    useEffect(() => {
        document.title = titulo
            ? `${titulo} · Fiel`
            : 'Fiel · pagamentos onde a conta sempre fecha';
    }, [titulo]);
}
