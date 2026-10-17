import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useSessao } from '../api/hooks';

/** Só com login: sem ele, vai para /entrar e volta para cá depois */
export function Exige({ children }: { children: ReactNode }) {
    const { carregando, usuario } = useSessao();
    const local = useLocation();
    if (carregando) return <div className="pagina" aria-busy="true" />;
    if (!usuario) {
        const volta = encodeURIComponent(local.pathname + local.search);
        return <Navigate to={`/entrar?volta=${volta}`} replace />;
    }
    return <>{children}</>;
}
