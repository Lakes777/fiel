import type { LinhaDoExtrato, Limites } from '../api/tipos';

/**
 * A carteira de exemplo que aparece no celular da tela inicial. O "agora" é fixo para as datas do
 * extrato saírem sempre iguais ("hoje, 14:32"), seja qual for o dia da visita.
 */
export const AGORA_DO_EXEMPLO = new Date('2026-10-17T17:40:00Z');

export const NOME_DO_EXEMPLO = 'Ana Souza';

export const SALDO_DO_EXEMPLO = 136600;

/** Do mais novo para o mais antigo, como o extrato de verdade */
export const EXTRATO_DO_EXEMPLO: LinhaDoExtrato[] = [
    {
        lancamentoId: 3,
        descricao: 'Transferência de Davi R. para Ana S.',
        em: '2026-10-17T17:32:00Z',
        valorCentavos: 40000,
        saldoDepoisCentavos: 136600,
    },
    {
        lancamentoId: 2,
        descricao: 'Pagamento de Ana S. a Bob L.: Caneca',
        em: '2026-10-17T15:05:00Z',
        valorCentavos: -3400,
        saldoDepoisCentavos: 96600,
    },
    {
        lancamentoId: 1,
        descricao: 'Bônus de boas-vindas',
        em: '2026-10-16T21:10:00Z',
        valorCentavos: 100000,
        saldoDepoisCentavos: 100000,
    },
];

export const LIMITES_DO_EXEMPLO: Limites = {
    diaLimiteCentavos: 500000,
    diaUsadoCentavos: 3400,
    noiteLimiteCentavos: 100000,
    noiteUsadoCentavos: 0,
    ehNoite: false,
    disponivelCentavos: 496600,
};

/** Só para o QR se desenhar: não é uma cobrança de verdade */
export const QR_DO_EXEMPLO = 'fiel:exemplo:cobranca:caneca:3400';
