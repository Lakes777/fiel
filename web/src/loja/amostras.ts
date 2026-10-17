import type { Aviso, CobrancaDoLojista } from '../api/tipos';

/** Dados de exemplo para os testes da loja, com datas em volta de agora ("hoje" de verdade) */

const MINUTO = 60_000;

export function haMinutos(minutos: number): string {
    return new Date(Date.now() - minutos * MINUTO).toISOString();
}

export function daquiA(minutos: number): string {
    return new Date(Date.now() + minutos * MINUTO).toISOString();
}

export function umaCobranca(
    parcial: Partial<CobrancaDoLojista> = {},
): CobrancaDoLojista {
    return {
        id: 132,
        valorCentavos: 3400,
        descricao: 'Caneca esmaltada',
        estado: 'pendente',
        criadaEm: haMinutos(1),
        expiraEm: daquiA(29),
        pagaEm: null,
        estornadoCentavos: 0,
        qr: `0004FIEL010112636qr-de-teste-${parcial.id ?? 132}5404340063041D3A`,
        ...parcial,
    };
}

export function umAviso(parcial: Partial<Aviso> = {}): Aviso {
    return {
        id: 7,
        eventoId: '3f2a9c1e-7b4d-4e8a-9f60-1c2d3e4f5a6b',
        tipo: 'cobranca.paga',
        estado: 'entregue',
        tentativas: 1,
        criadoEm: haMinutos(3),
        proximaTentativaEm: null,
        entregueEm: haMinutos(3),
        ultimaResposta: 'HTTP 200',
        ...parcial,
    };
}
