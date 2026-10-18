import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router';
import { EntrarComoVisitante } from '../comum/EntrarComoVisitante';
import { ErroDaApi, mensagemDe } from '../api/cliente';
import { useSessao } from '../api/hooks';
import { entrar } from '../api/sessao';
import { Balanca } from '../comum/Marca';
import { useTitulo } from '../comum/titulo';
import { comVolta, destinoDaVolta } from './regras';
import './paginas.css';

export function Entrar() {
    useTitulo('Entrar');
    const { usuario } = useSessao();
    const [busca] = useSearchParams();
    const volta = busca.get('volta');
    const destino = destinoDaVolta(volta);
    // Veio do cadastro: a conta foi criada, mas entrar falhou (o e-mail já vem preenchido)
    const contaCriada = (useLocation().state as { contaCriada?: string } | null)
        ?.contaCriada;
    const [email, setEmail] = useState(contaCriada ?? '');
    const [senha, setSenha] = useState('');
    const [erro, setErro] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);

    // Já logado (ou acabou de entrar: a sessão muda e esta tela some sozinha)
    if (usuario) return <Navigate to={destino} replace />;

    async function enviar(evento: FormEvent<HTMLFormElement>) {
        evento.preventDefault();
        setErro(null);
        setEnviando(true);
        try {
            await entrar(email.trim(), senha);
        } catch (e) {
            // A API não diz qual dos dois errou (de propósito); a tela também não
            setErro(
                e instanceof ErroDaApi && e.status === 401
                    ? 'E-mail ou senha incorretos.'
                    : mensagemDe(e),
            );
            setEnviando(false);
        }
    }

    return (
        <main className="acesso">
            <div className="cartao acesso-cartao">
                <Balanca className="acesso-balanca" />
                <h1>Entrar</h1>
                <p className="acesso-sub">
                    Sua carteira de mentira está esperando.
                </p>
                {erro ? (
                    <p className="alerta" role="alert">
                        {erro}
                    </p>
                ) : (
                    contaCriada && (
                        <p className="alerta ok" role="status">
                            Conta criada. Entre com sua senha.
                        </p>
                    )
                )}
                <form onSubmit={(e) => void enviar(e)}>
                    <div className="campo">
                        <label htmlFor="entrar-email">E-mail</label>
                        <input
                            id="entrar-email"
                            type="email"
                            autoComplete="email"
                            required
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                        />
                    </div>
                    <div className="campo">
                        <label htmlFor="entrar-senha">Senha</label>
                        <input
                            id="entrar-senha"
                            type="password"
                            autoComplete="current-password"
                            required
                            value={senha}
                            onChange={(e) => setSenha(e.target.value)}
                        />
                    </div>
                    <button
                        type="submit"
                        className="botao acesso-enviar"
                        disabled={enviando}
                    >
                        {enviando ? 'Entrando…' : 'Entrar'}
                    </button>
                </form>
                <div className="acesso-visitante">
                    <EntrarComoVisitante className="botao vazado acesso-enviar">
                        Entrar como visitante
                    </EntrarComoVisitante>
                </div>
                <p className="acesso-troca">
                    Ainda não tem conta?{' '}
                    <Link to={comVolta('/cadastro', volta)}>Criar conta</Link>
                </p>
            </div>
        </main>
    );
}
