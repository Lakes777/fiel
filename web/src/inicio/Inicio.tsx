import type { CSSProperties, MouseEvent, ReactNode } from 'react';
import { Link } from 'react-router';
import { EntrarComoVisitante } from '../comum/EntrarComoVisitante';
import { useDemoAtiva } from '../api/demo';
import { useSessao } from '../api/hooks';
import { VisaoDaCarteira } from '../carteira/VisaoDaCarteira';
import { menosMovimento } from '../comum/movimento';
import { Qr } from '../comum/Qr';
import { useRevelar } from '../comum/revelar';
import { useTitulo } from '../comum/titulo';
import {
    AGORA_DO_EXEMPLO,
    EXTRATO_DO_EXEMPLO,
    LIMITES_DO_EXEMPLO,
    NOME_DO_EXEMPLO,
    QR_DO_EXEMPLO,
    SALDO_DO_EXEMPLO,
} from './exemplo';
import './inicio.css';

const GITHUB = 'https://github.com/Lakes777/fiel';

/**
 * A tela inicial ("Na mão", L3 da amostra aprovada): o título à esquerda e, à direita, um celular
 * com a carteira de verdade (o mesmo componente da /carteira) cheia de dados de exemplo.
 */
export function Inicio() {
    useTitulo();
    const { usuario } = useSessao();
    // Com a demonstração, o botão principal é entrar sem cadastro; sem ela, criar a conta
    const demo = useDemoAtiva();
    return (
        <main className="inicio">
            <section className="inicio-heroi">
                <div className="inicio-heroi-texto">
                    <h1>
                        Pagamentos onde a conta <em>sempre fecha</em>.
                    </h1>
                    <p className="inicio-sub">
                        Carteira digital, transferências e cobranças com QR.
                        Cada centavo que sai de uma conta entra em outra, e o
                        próprio banco de dados recusa o que não fechar.
                    </p>
                    <div className="inicio-chamadas">
                        {usuario ? (
                            <Link className="botao" to="/carteira">
                                Ir para a carteira{' '}
                                <span className="seta">→</span>
                            </Link>
                        ) : (
                            <>
                                <EntrarComoVisitante>
                                    Experimentar sem cadastro{' '}
                                    <span className="seta">→</span>
                                </EntrarComoVisitante>
                                <Link
                                    className={demo ? 'botao vazado' : 'botao'}
                                    to="/cadastro"
                                >
                                    Criar conta com R$&nbsp;1.000 de mentira
                                </Link>
                            </>
                        )}
                        <a
                            className="botao vazado"
                            href="#recursos"
                            onClick={rolarAteRecursos}
                        >
                            Como funciona
                        </a>
                    </div>
                    <p className="inicio-aviso">
                        Projeto de estudo. Nada aqui é dinheiro de verdade.
                    </p>
                </div>
                <Celular />
            </section>

            <Recursos />
            <Lancamento />

            <footer className="inicio-faixa">
                <div className="inicio-rodape">
                    <span>
                        Fiel · projeto de estudo de André Lagos. Não movimenta
                        dinheiro de verdade.
                    </span>
                    <span>
                        Kotlin · Spring Boot · PostgreSQL · React ·{' '}
                        <a href={GITHUB} target="_blank" rel="noreferrer">
                            código no GitHub
                        </a>
                    </span>
                </div>
            </footer>
        </main>
    );
}

/** O link funciona sem JavaScript; com ele, a rolagem é suave (menos para quem pediu menos movimento) */
function rolarAteRecursos(evento: MouseEvent<HTMLAnchorElement>) {
    const alvo = document.getElementById('recursos');
    if (!alvo) return;
    evento.preventDefault();
    alvo.scrollIntoView?.({
        behavior: menosMovimento() ? 'auto' : 'smooth',
        block: 'start',
    });
}

/**
 * O celular é só ilustração: o leitor de tela ouve uma frase curta no lugar dele, em vez de um
 * saldo, botões e um QR que não são de ninguém.
 */
function Celular() {
    // Parecem os botões da carteira, mas não fazem nada: por isso nem são <button>
    const acoes = (
        <>
            <span className="botao pequeno" aria-hidden="true">
                Transferir
            </span>
            <span className="botao pequeno vazado" aria-hidden="true">
                Pagar QR
            </span>
            <span className="botao pequeno vazado" aria-hidden="true">
                Cobrar
            </span>
        </>
    );
    return (
        <div className="inicio-lado-celular">
            <p className="so-leitor">
                Exemplo da carteira num celular: saldo de R$ 1.366,00, um bônus
                de boas-vindas, um pagamento e uma transferência recebida.
            </p>
            <div className="inicio-celular" aria-hidden="true">
                <div className="inicio-tela">
                    <VisaoDaCarteira
                        nome={NOME_DO_EXEMPLO}
                        saldoCentavos={SALDO_DO_EXEMPLO}
                        limites={LIMITES_DO_EXEMPLO}
                        extrato={EXTRATO_DO_EXEMPLO}
                        acoes={acoes}
                        contarDe={0}
                        linhas={3}
                        agora={AGORA_DO_EXEMPLO}
                    />
                    <div className="inicio-qr">
                        <Qr texto={QR_DO_EXEMPLO} tamanho={96} desenhar />
                    </div>
                </div>
            </div>
        </div>
    );
}

/** Um bloco que surge ao entrar na tela (uma vez) */
function Surge({
    children,
    className,
}: {
    children: ReactNode;
    className?: string;
}) {
    const ref = useRevelar<HTMLDivElement>();
    return (
        <div ref={ref} className={className}>
            {children}
        </div>
    );
}

const ICONE = {
    width: 24,
    height: 24,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.6,
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
    'aria-hidden': true,
} as const;

const RECURSOS: { titulo: string; texto: string; icone: ReactNode }[] = [
    {
        titulo: 'Transferências por chave',
        texto: 'Mandar duas vezes o mesmo pedido cobra uma vez só. Limite de dia e de noite.',
        icone: (
            <svg {...ICONE}>
                <path d="M4 8h13l-3-3M20 16H7l3 3" />
            </svg>
        ),
    },
    {
        titulo: 'Cobranças com QR',
        texto: 'O lojista cobra, quem paga lê o QR. Paga uma vez, vence sozinha.',
        icone: (
            <svg {...ICONE}>
                <rect x="4" y="4" width="6" height="6" />
                <rect x="14" y="4" width="6" height="6" />
                <rect x="4" y="14" width="6" height="6" />
                <path d="M14 14h2v2h-2zM18 18h2v2h-2zM14 18h2" />
            </svg>
        ),
    },
    {
        titulo: 'Estornos',
        texto: 'Quem recebeu devolve, tudo ou em partes. O pagamento original continua no razão.',
        icone: (
            <svg {...ICONE}>
                <path d="M9 14l-4-4 4-4" />
                <path d="M5 10h9a5 5 0 0 1 0 10h-2" />
            </svg>
        ),
    },
    {
        titulo: 'Avisos ao lojista',
        texto: 'Webhooks assinados, com nova tentativa e fila de falhas.',
        icone: (
            <svg {...ICONE}>
                <path d="M4 12h4l2-5 4 10 2-5h4" />
            </svg>
        ),
    },
    {
        titulo: 'Conciliação às 3h',
        texto: 'Toda noite o razão inteiro é conferido, e o resultado chega pelo Telegram.',
        icone: (
            <svg {...ICONE}>
                <circle cx="12" cy="12" r="8" />
                <path d="M12 7v5l3 2" />
            </svg>
        ),
    },
    {
        titulo: 'O banco é a última palavra',
        texto: 'As regras estão no PostgreSQL: nem um bug na API cria dinheiro.',
        icone: (
            <svg {...ICONE}>
                <path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z" />
            </svg>
        ),
    },
];

function Recursos() {
    return (
        <section
            className="inicio-faixa"
            id="recursos"
            aria-labelledby="titulo-recursos"
        >
            <div className="inicio-secao">
                <Surge>
                    <h2 id="titulo-recursos">
                        Dinheiro de mentira, regras de verdade
                    </h2>
                    <p className="inicio-explica">
                        Carteira, transferências, cobranças com QR e avisos para
                        o lojista. Tudo com R$&nbsp;1.000 de bônus que não valem
                        nada fora daqui, e com as mesmas preocupações de um
                        sistema de pagamentos de verdade.
                    </p>
                </Surge>
                {/* A grade inteira é observada; os cartões entram um depois do outro (--i) */}
                <Surge className="inicio-recursos">
                    {RECURSOS.map((r, i) => (
                        <article
                            key={r.titulo}
                            className="inicio-recurso"
                            style={{ '--i': i } as CSSProperties}
                        >
                            {r.icone}
                            <h3>{r.titulo}</h3>
                            <p>{r.texto}</p>
                        </article>
                    ))}
                </Surge>
            </div>
        </section>
    );
}

function Lancamento() {
    return (
        <section className="inicio-faixa" aria-labelledby="titulo-lancamento">
            <div className="inicio-secao">
                <Surge>
                    <h2 id="titulo-lancamento">
                        Cada centavo que sai, entra em outro lugar
                    </h2>
                    <p className="inicio-explica">
                        Um pagamento é um lançamento com duas partidas. Se a
                        soma não fechar em zero, o banco recusa e nada é
                        gravado.
                    </p>
                </Surge>
                <Surge className="inicio-lancamento">
                    <p className="inicio-lancamento-titulo">
                        Ana paga a caneca do Bob
                    </p>
                    <dl>
                        <div>
                            <dt>Carteira da Ana</dt>
                            <dd className="numero sai">−R$&nbsp;34,00</dd>
                        </div>
                        <div>
                            <dt>Carteira do Bob</dt>
                            <dd className="numero entra">+R$&nbsp;34,00</dd>
                        </div>
                        <div className="inicio-soma">
                            <dt>Soma</dt>
                            <dd className="numero">R$&nbsp;0,00</dd>
                        </div>
                    </dl>
                </Surge>
            </div>
        </section>
    );
}
