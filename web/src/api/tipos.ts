/** As respostas da API do Fiel, como o servidor manda (valores sempre em centavos, datas em ISO 8601). */

export interface Usuario {
    id: number;
    nome: string;
    email: string;
}

export interface Conta {
    id: number;
    nome: string;
    saldoCentavos: number;
}

/** Uma linha do extrato: positivo entrou, negativo saiu */
export interface LinhaDoExtrato {
    lancamentoId: number;
    descricao: string;
    em: string;
    valorCentavos: number;
    saldoDepoisCentavos: number;
}

export interface Limites {
    diaLimiteCentavos: number;
    diaUsadoCentavos: number;
    noiteLimiteCentavos: number;
    noiteUsadoCentavos: number;
    /** Se agora é noite (20h às 6h, Brasília): só então o limite noturno vale */
    ehNoite: boolean;
    disponivelCentavos: number;
}

export type TipoDeChave = 'email' | 'aleatoria';

export interface Chave {
    id: number;
    tipo: TipoDeChave;
    valor: string;
    criadaEm: string;
}

/** Quem é o dono de uma chave, mascarado ("Ana S.") */
export interface DonoDaChave {
    nome: string;
    tipo: TipoDeChave;
}

export interface TransferenciaFeita {
    id: number;
    valorCentavos: number;
    para: string;
    em: string;
}

export type EstadoDaCobranca =
    | 'pendente'
    | 'paga'
    | 'expirada'
    | 'cancelada'
    | 'parcialmente_estornada'
    | 'estornada';

export interface CobrancaDoLojista {
    id: number;
    valorCentavos: number;
    descricao: string;
    estado: EstadoDaCobranca;
    criadaEm: string;
    expiraEm: string;
    pagaEm: string | null;
    estornadoCentavos: number;
    /** O texto que vai dentro do QR */
    qr: string;
}

/** O que quem vai pagar vê antes de confirmar (dados do servidor, não do QR) */
export interface CobrancaParaPagar {
    valorCentavos: number;
    descricao: string;
    recebedor: string;
    estado: EstadoDaCobranca;
    expiraEm: string;
}

export interface PagamentoFeito {
    valorCentavos: number;
    descricao: string;
    recebedor: string;
    pagaEm: string;
}

export interface EstornoFeito {
    id: number;
    cobrancaId: number;
    valorCentavos: number;
    motivo: string | null;
    em: string;
}

export interface Webhook {
    url: string;
}

/** Só na hora de cadastrar: o segredo não aparece de novo */
export interface WebhookNovo {
    url: string;
    segredo: string;
}

export type EstadoDoAviso = 'pendente' | 'entregue' | 'falhou';

export interface Aviso {
    id: number;
    eventoId: string;
    tipo: string;
    estado: EstadoDoAviso;
    tentativas: number;
    criadoEm: string;
    proximaTentativaEm: string | null;
    entregueEm: string | null;
    ultimaResposta: string | null;
}
