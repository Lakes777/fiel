import { useState } from 'react';
import { useNavigate } from 'react-router';
import { mensagemDe } from '../api/cliente';
import { useDemoAtiva } from '../api/demo';
import { entrarComoVisitante } from '../api/sessao';

/**
 * Demonstração: entra numa conta descartável, sem inventar e-mail e senha, e vai para a carteira.
 * Num servidor sem demonstração, não aparece.
 */
export function EntrarComoVisitante({
    className = 'botao',
    children = 'Entrar como visitante',
}: {
    className?: string;
    children?: React.ReactNode;
}) {
    const ativa = useDemoAtiva();
    const navegar = useNavigate();
    const [enviando, setEnviando] = useState(false);
    const [erro, setErro] = useState<string | null>(null);
    if (!ativa) return null;

    async function entrar() {
        setErro(null);
        setEnviando(true);
        try {
            await entrarComoVisitante();
            navegar('/carteira');
        } catch (e) {
            setErro(mensagemDe(e));
            setEnviando(false);
        }
    }

    return (
        <>
            <button
                type="button"
                className={className}
                disabled={enviando}
                onClick={() => void entrar()}
            >
                {enviando ? 'Abrindo a conta…' : children}
            </button>
            {erro && (
                <p className="alerta" role="alert">
                    {erro}
                </p>
            )}
        </>
    );
}
