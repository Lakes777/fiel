import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { mensagemDe } from '../api/cliente';
import { useSessao } from '../api/hooks';
import { cadastrar, entrar } from '../api/sessao';
import { Balanca } from '../comum/Marca';
import { useTitulo } from '../comum/titulo';
import {
    MAX_SENHA_BYTES,
    MIN_SENHA,
    comVolta,
    destinoDaVolta,
    problemaDaSenha,
} from './regras';
import './paginas.css';

export function Cadastro() {
    useTitulo('Criar conta');
    const { usuario } = useSessao();
    const navegar = useNavigate();
    const [busca] = useSearchParams();
    const volta = busca.get('volta');
    const [nome, setNome] = useState('');
    const [email, setEmail] = useState('');
    const [senha, setSenha] = useState('');
    // O aviso da senha só aparece depois da primeira tentativa: antes disso, é a pessoa digitando
    const [tentou, setTentou] = useState(false);
    const [erro, setErro] = useState<string | null>(null);
    const [enviando, setEnviando] = useState(false);

    // Cadastrou e entrou, ou já estava logado: vai para onde ia (a carteira, se nada foi pedido)
    if (usuario) return <Navigate to={destinoDaVolta(volta)} replace />;

    const problema = tentou ? problemaDaSenha(senha) : null;

    async function enviar(evento: FormEvent<HTMLFormElement>) {
        evento.preventDefault();
        setTentou(true);
        setErro(null);
        // Confere aqui o que a API recusaria: a resposta vem na hora, junto do campo
        if (problemaDaSenha(senha)) return;
        setEnviando(true);
        try {
            await cadastrar(nome.trim(), email.trim(), senha);
        } catch (e) {
            // 409 (e-mail já usado), 429 e o resto: a mensagem da API já é uma frase
            setErro(mensagemDe(e));
            setEnviando(false);
            return;
        }
        try {
            await entrar(email.trim(), senha);
        } catch {
            // A conta já existe: mostrar o erro aqui faria a pessoa reenviar e levar um 409
            navegar(comVolta('/entrar', volta), {
                replace: true,
                state: { contaCriada: email.trim() },
            });
        }
    }

    return (
        <main className="acesso">
            <div className="cartao acesso-cartao">
                <Balanca className="acesso-balanca" />
                <h1>Criar conta</h1>
                <p className="acesso-sub">
                    A conta nasce com R$ 1.000 de bônus para testar à vontade.
                </p>
                {erro && (
                    <p className="alerta" role="alert">
                        {erro}
                    </p>
                )}
                <form onSubmit={(e) => void enviar(e)}>
                    <div className="campo">
                        <label htmlFor="cadastro-nome">Nome</label>
                        <input
                            id="cadastro-nome"
                            autoComplete="name"
                            required
                            maxLength={100}
                            value={nome}
                            onChange={(e) => setNome(e.target.value)}
                        />
                    </div>
                    <div className="campo">
                        <label htmlFor="cadastro-email">E-mail</label>
                        <input
                            id="cadastro-email"
                            type="email"
                            autoComplete="email"
                            required
                            maxLength={254}
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                        />
                    </div>
                    <div className="campo">
                        <label htmlFor="cadastro-senha">Senha</label>
                        <input
                            id="cadastro-senha"
                            type="password"
                            autoComplete="new-password"
                            required
                            value={senha}
                            onChange={(e) => setSenha(e.target.value)}
                            aria-invalid={problema ? true : undefined}
                            aria-describedby={
                                problema
                                    ? 'cadastro-senha-erro'
                                    : 'cadastro-senha-dica'
                            }
                        />
                        {problema ? (
                            <span
                                id="cadastro-senha-erro"
                                className="erro-do-campo"
                            >
                                {problema}
                            </span>
                        ) : (
                            <span id="cadastro-senha-dica" className="dica">
                                De {MIN_SENHA} caracteres a {MAX_SENHA_BYTES}{' '}
                                bytes (acentos contam em dobro).
                            </span>
                        )}
                    </div>
                    <p className="acesso-aviso">
                        Nada aqui é dinheiro de verdade, e não pedimos CPF nem
                        dados de banco.
                    </p>
                    <button
                        type="submit"
                        className="botao acesso-enviar"
                        disabled={enviando}
                    >
                        {enviando
                            ? 'Criando a conta…'
                            : 'Criar conta e ganhar R$ 1.000 de mentira'}
                    </button>
                </form>
                <p className="acesso-troca">
                    Já tem conta?{' '}
                    <Link to={comVolta('/entrar', volta)}>Entrar</Link>
                </p>
            </div>
        </main>
    );
}
