import { useState } from 'react';
import { api, mensagemDe } from '../api/cliente';
import type { Chave, TipoDeChave } from '../api/tipos';
import { useCopiar } from '../comum/ganchos';

/** O mesmo limite do banco (check de 0 a 5 no contador de chaves da conta) */
export const MAXIMO_DE_CHAVES = 5;

const NOME_DO_TIPO: Record<TipoDeChave, string> = {
    email: 'E-mail',
    aleatoria: 'Aleatória',
};

/**
 * Minhas chaves: os apelidos da carteira que quem manda dinheiro usa. Quem busca a lista é a
 * Carteira (o diálogo de transferir também precisa dela, para avisar "essa chave é sua").
 */
export function Chaves({
    chaves,
    erroAoCarregar,
    aoMudar,
}: {
    chaves: Chave[] | null;
    erroAoCarregar: string | null;
    aoMudar: () => void;
}) {
    const [erro, setErro] = useState<string | null>(null);
    const [ocupado, setOcupado] = useState(false);
    const [apagando, setApagando] = useState<number | null>(null);
    // O gancho diz se copiou (e some sozinho) e qual das chaves foi
    const [copia, copiarTexto, qualCopiou] = useCopiar<number>();
    const copiada = copia === 'copiado' ? qualCopiou : null;

    const lotado = (chaves?.length ?? 0) >= MAXIMO_DE_CHAVES;
    const temEmail = chaves?.some((c) => c.tipo === 'email') ?? false;

    async function criar(tipo: TipoDeChave) {
        setOcupado(true);
        setErro(null);
        try {
            await api<Chave>('/chaves', { metodo: 'POST', corpo: { tipo } });
            aoMudar();
        } catch (e) {
            setErro(mensagemDe(e));
        } finally {
            setOcupado(false);
        }
    }

    async function apagar(id: number) {
        setOcupado(true);
        setErro(null);
        try {
            await api<void>(`/chaves/${id}`, { metodo: 'DELETE' });
            setApagando(null);
            aoMudar();
        } catch (e) {
            setErro(mensagemDe(e));
        } finally {
            setOcupado(false);
        }
    }

    function copiar(chave: Chave) {
        setErro(null);
        copiarTexto(chave.valor, chave.id);
    }

    return (
        <section className="cartao chaves" aria-labelledby="titulo-chaves">
            <h2 className="rotulo" id="titulo-chaves">
                Minhas chaves
            </h2>
            <p className="suave explica">
                Quem quiser mandar dinheiro para você usa uma destas.
            </p>
            {erroAoCarregar && !chaves && (
                <p className="alerta" role="alert">
                    {erroAoCarregar}
                </p>
            )}
            {chaves === null ? (
                !erroAoCarregar && (
                    <div
                        className="esqueleto"
                        style={{ height: 96 }}
                        aria-busy="true"
                    />
                )
            ) : chaves.length === 0 ? (
                <p className="suave">Nenhuma chave ainda. Crie uma abaixo.</p>
            ) : (
                <ul className="lista-chaves">
                    {chaves.map((c) => (
                        <li key={c.id} className="entrar">
                            <span className="tipo">{NOME_DO_TIPO[c.tipo]}</span>
                            <span className="valor-chave">{c.valor}</span>
                            {apagando === c.id ? (
                                <div
                                    className="confirmar-apagar"
                                    role="group"
                                    aria-label="Confirmar"
                                >
                                    <span>
                                        Apagar? Quem guardou esta chave não
                                        consegue mais mandar por ela.
                                    </span>
                                    <button
                                        type="button"
                                        className="botao perigo pequeno"
                                        disabled={ocupado}
                                        onClick={() => void apagar(c.id)}
                                    >
                                        Apagar
                                    </button>
                                    <button
                                        type="button"
                                        className="botao vazado pequeno"
                                        onClick={() => setApagando(null)}
                                    >
                                        Manter
                                    </button>
                                </div>
                            ) : (
                                <div className="acoes-chave">
                                    <button
                                        type="button"
                                        className="botao vazado pequeno"
                                        onClick={() => copiar(c)}
                                        aria-label={`Copiar a chave ${c.valor}`}
                                    >
                                        {copiada === c.id
                                            ? 'Copiada'
                                            : 'Copiar'}
                                    </button>
                                    <button
                                        type="button"
                                        className="botao perigo pequeno"
                                        onClick={() => setApagando(c.id)}
                                        aria-label={`Apagar a chave ${c.valor}`}
                                    >
                                        Apagar
                                    </button>
                                </div>
                            )}
                        </li>
                    ))}
                </ul>
            )}
            <p className="so-leitor" aria-live="polite">
                {copiada != null ? 'Chave copiada.' : ''}
            </p>
            {(erro || copia === 'falhou') && (
                <p className="alerta" role="alert">
                    {erro ??
                        'Não deu para copiar. Selecione a chave e copie à mão.'}
                </p>
            )}
            {chaves && (
                <>
                    <div className="criar-chave">
                        <button
                            type="button"
                            className="botao vazado pequeno"
                            disabled={ocupado || lotado || temEmail}
                            onClick={() => void criar('email')}
                        >
                            Criar chave de e-mail
                        </button>
                        <button
                            type="button"
                            className="botao vazado pequeno"
                            disabled={ocupado || lotado}
                            onClick={() => void criar('aleatoria')}
                        >
                            Criar chave aleatória
                        </button>
                    </div>
                    {lotado ? (
                        <p className="dica-chaves suave">
                            Você já tem {MAXIMO_DE_CHAVES} chaves, o máximo.
                            Apague uma para criar outra.
                        </p>
                    ) : (
                        temEmail && (
                            <p className="dica-chaves suave">
                                Seu e-mail já é uma chave. A aleatória serve
                                para não mostrar o e-mail.
                            </p>
                        )
                    )}
                </>
            )}
        </section>
    );
}
