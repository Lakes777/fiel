import type { EstadoDaCobranca, EstadoDoAviso } from '../api/tipos';

const DINHEIRO = new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

/**
 * 123456 -> "R$ 1.234,56"; negativo com o sinal de menos tipográfico ("−R$ 25,50"). O espaço depois
 * do R$ não quebra: a linha não acaba em "R$" com o número embaixo
 */
export function reais(centavos: number): string {
    const texto = `R$\u00a0${DINHEIRO.format(Math.abs(centavos) / 100)}`;
    return centavos < 0 ? `−${texto}` : texto;
}

/** Com sinal sempre: "+R$ 25,50" ou "−R$ 25,50" (extrato) */
export function reaisComSinal(centavos: number): string {
    return centavos > 0 ? `+${reais(centavos)}` : reais(centavos);
}

/**
 * "R$ 12,34" ou "12,34" digitado -> 1234 centavos; null se não for um valor. O ponto é milhar só
 * em grupos de três ("1.234" = R$ 1.234,00); sem vírgula, ponto com 1 ou 2 dígitos no fim é a
 * casa decimal de quem digita como no teclado inglês ("12.50" = R$ 12,50, não R$ 1.250,00)
 */
export function lerReais(texto: string): number | null {
    const limpo = texto.replace(/R\$|\s/g, '');
    const partes =
        /^(\d+|\d{1,3}(?:\.\d{3})+)(?:,(\d{1,2}))?$/.exec(limpo) ??
        /^(\d+)\.(\d{1,2})$/.exec(limpo);
    if (!partes) return null;
    const inteiro = Number(partes[1].replace(/\./g, ''));
    const centavos = Number((partes[2] ?? '').padEnd(2, '0'));
    return inteiro * 100 + centavos;
}

const FUSO = 'America/Sao_Paulo';
const HORA = new Intl.DateTimeFormat('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: FUSO,
});
const DIA = new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    timeZone: FUSO,
});
const DIA_CHAVE = new Intl.DateTimeFormat('en-CA', { timeZone: FUSO });

/** "hoje, 14:32", "ontem, 21:10" ou "15/10, 18:02" (no horário de Brasília, como os limites) */
export function quando(iso: string, agora: Date = new Date()): string {
    const data = new Date(iso);
    const dia = DIA_CHAVE.format(data);
    const hoje = DIA_CHAVE.format(agora);
    const ontem = DIA_CHAVE.format(new Date(agora.getTime() - 86_400_000));
    const hora = HORA.format(data);
    if (dia === hoje) return `hoje, ${hora}`;
    if (dia === ontem) return `ontem, ${hora}`;
    return `${DIA.format(data)}, ${hora}`;
}

/** Se o instante cai no mesmo dia (de Brasília) que agora */
export function ehHoje(iso: string, agora: Date = new Date()): boolean {
    return DIA_CHAVE.format(new Date(iso)) === DIA_CHAVE.format(agora);
}

/** "em 12 min", "em 1 h 5 min", "venceu" */
export function falta(iso: string, agora: Date = new Date()): string {
    const minutos = Math.ceil(
        (new Date(iso).getTime() - agora.getTime()) / 60_000,
    );
    if (minutos <= 0) return 'venceu';
    if (minutos < 60) return `em ${minutos} min`;
    const h = Math.floor(minutos / 60);
    const m = minutos % 60;
    return m ? `em ${h} h ${m} min` : `em ${h} h`;
}

export const NOME_DO_ESTADO: Record<EstadoDaCobranca, string> = {
    pendente: 'pendente',
    paga: 'paga',
    expirada: 'expirada',
    cancelada: 'cancelada',
    parcialmente_estornada: 'estornada em parte',
    estornada: 'estornada',
};

export const NOME_DO_AVISO: Record<EstadoDoAviso, string> = {
    pendente: 'na fila',
    entregue: 'entregue',
    falhou: 'falhou',
};
