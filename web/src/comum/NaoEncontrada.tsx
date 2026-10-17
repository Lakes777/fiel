import { Link } from 'react-router';
import { useTitulo } from './titulo';

export function NaoEncontrada() {
    useTitulo('Página não encontrada');
    return (
        <main className="pagina">
            <h1>Esta página não existe</h1>
            <p className="suave">
                O endereço pode estar errado, ou a página mudou de lugar.
            </p>
            <Link className="botao" to="/">
                Ir para o início
            </Link>
        </main>
    );
}
