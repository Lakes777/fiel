import {
    useEffect,
    useId,
    useRef,
    type KeyboardEvent as EventoDeTecla,
    type ReactNode,
    type RefObject,
} from 'react';

const FOCAVEIS =
    'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Um diálogo por cima da tela (no celular, sobe de baixo). Ao abrir, e a cada `passo` novo, o foco
 * vai para o primeiro campo (ou para o que tiver `data-foco`), para quem usa teclado ou leitor de
 * tela não ficar perdido atrás do fundo. Esc e o fundo fecham; o Tab não sai de dentro; ao fechar,
 * o foco volta para `voltarPara` (o botão que abriu).
 */
export function Dialogo({
    titulo,
    passo,
    aoFechar,
    voltarPara,
    children,
}: {
    titulo: string;
    passo?: string;
    aoFechar: () => void;
    voltarPara: RefObject<HTMLElement | null>;
    children: ReactNode;
}) {
    const caixa = useRef<HTMLDivElement>(null);
    const idTitulo = useId();
    const fechar = useRef(aoFechar);
    useEffect(() => {
        fechar.current = aoFechar;
    });

    useEffect(() => {
        const alvo = voltarPara.current;
        const tecla = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                e.preventDefault();
                fechar.current();
            }
        };
        document.addEventListener('keydown', tecla);
        return () => {
            document.removeEventListener('keydown', tecla);
            alvo?.focus();
        };
    }, [voltarPara]);

    useEffect(() => {
        const dentro = caixa.current;
        if (!dentro) return;
        const alvo =
            dentro.querySelector<HTMLElement>('[data-foco]') ??
            [...dentro.querySelectorAll<HTMLElement>(FOCAVEIS)].find(
                (el) => !el.hasAttribute('data-fechar'),
            );
        alvo?.focus();
    }, [passo]);

    function prenderTab(e: EventoDeTecla) {
        if (e.key !== 'Tab' || !caixa.current) return;
        const lista = [
            ...caixa.current.querySelectorAll<HTMLElement>(FOCAVEIS),
        ];
        if (lista.length === 0) return;
        const primeiro = lista[0];
        const ultimo = lista[lista.length - 1];
        if (e.shiftKey && document.activeElement === primeiro) {
            e.preventDefault();
            ultimo.focus();
        } else if (!e.shiftKey && document.activeElement === ultimo) {
            e.preventDefault();
            primeiro.focus();
        }
    }

    return (
        <div
            className="dialogo-fundo"
            onMouseDown={(e) => {
                if (e.target === e.currentTarget) aoFechar();
            }}
        >
            <div
                ref={caixa}
                className="dialogo cartao"
                role="dialog"
                aria-modal="true"
                aria-labelledby={idTitulo}
                onKeyDown={prenderTab}
            >
                <div className="dialogo-topo">
                    <h2 id={idTitulo}>{titulo}</h2>
                    <button
                        type="button"
                        className="botao-icone"
                        aria-label="Fechar"
                        data-fechar
                        onClick={aoFechar}
                    >
                        <svg
                            viewBox="0 0 20 20"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            aria-hidden="true"
                        >
                            <path d="M5 5l10 10M15 5L5 15" />
                        </svg>
                    </button>
                </div>
                {children}
            </div>
        </div>
    );
}
