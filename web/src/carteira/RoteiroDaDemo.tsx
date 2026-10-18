import { useState } from 'react';
import { Link } from 'react-router';
import { ehVisitante, guardarRoteiro, lerRoteiro } from '../api/demo';
import { useSessao } from '../api/hooks';

/**
 * Só para o visitante da demonstração: o que experimentar, na ordem em que a coisa faz sentido.
 * O roteiro veio do POST /demo/visitante e fica nesta aba; "Esconder" tira ele daqui.
 */
export function RoteiroDaDemo() {
    const { usuario } = useSessao();
    const [roteiro, setRoteiro] = useState(lerRoteiro);
    if (!roteiro || !ehVisitante(usuario)) return null;

    function esconder() {
        guardarRoteiro(null);
        setRoteiro(null);
    }

    return (
        <section
            className="cartao ajuda roteiro"
            aria-labelledby="titulo-roteiro"
        >
            <h2 className="rotulo" id="titulo-roteiro">
                Experimente
            </h2>
            <ol>
                <li>
                    Pague a camiseta da Loja Exemplo com o QR:{' '}
                    <Link
                        to={`/pagar?qr=${encodeURIComponent(roteiro.qrDaLoja)}`}
                    >
                        pagar a cobrança
                    </Link>
                    .
                </li>
                <li>
                    Transfira para a Ana pela chave{' '}
                    <code>{roteiro.chaveDaAna}</code>.
                </li>
                <li>
                    Na <Link to="/loja">loja</Link>, crie uma cobrança e peça
                    para o Bob pagar: o pagamento aparece na hora.
                </li>
            </ol>
            <button
                type="button"
                className="botao vazado pequeno"
                onClick={esconder}
            >
                Esconder
            </button>
        </section>
    );
}
