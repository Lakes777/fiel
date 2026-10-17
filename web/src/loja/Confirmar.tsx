import { useEffect, useId, useRef } from 'react';

/**
 * Uma confirmação no lugar, logo abaixo do botão que a abriu (cancelar a cobrança, apagar o
 * endereço). Não é um modal: no celular, um modal cobre o que a pessoa estava lendo. Ao abrir, o
 * foco vai para "Voltar" (o caminho seguro: um Enter distraído não apaga nada); Esc também volta.
 * Quem abre devolve o foco ao botão de origem ao fechar.
 */
export function Confirmar({
    titulo,
    texto,
    confirmar,
    ocupado = false,
    aoConfirmar,
    aoVoltar,
}: {
    titulo: string;
    texto: string;
    /** O texto do botão que faz a ação ("Cancelar cobrança") */
    confirmar: string;
    ocupado?: boolean;
    aoConfirmar: () => void;
    aoVoltar: () => void;
}) {
    const id = useId();
    const voltar = useRef<HTMLButtonElement>(null);
    useEffect(() => voltar.current?.focus(), []);
    return (
        <div
            className="confirmar cartao entrar"
            role="alertdialog"
            aria-labelledby={`${id}-titulo`}
            aria-describedby={`${id}-texto`}
            onKeyDown={(e) => {
                if (e.key === 'Escape') aoVoltar();
            }}
        >
            <p id={`${id}-titulo`} className="confirmar-titulo">
                {titulo}
            </p>
            <p id={`${id}-texto`} className="suave">
                {texto}
            </p>
            <div className="botoes">
                <button
                    type="button"
                    className="botao perigo pequeno"
                    disabled={ocupado}
                    onClick={aoConfirmar}
                >
                    {ocupado ? 'Um momento…' : confirmar}
                </button>
                <button
                    ref={voltar}
                    type="button"
                    className="botao vazado pequeno"
                    disabled={ocupado}
                    onClick={aoVoltar}
                >
                    Voltar
                </button>
            </div>
        </div>
    );
}
