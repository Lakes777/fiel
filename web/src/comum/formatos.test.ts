import {
    ehHoje,
    falta,
    lerReais,
    quando,
    reais,
    reaisComSinal,
} from './formatos';

describe('dinheiro', () => {
    it('formata centavos em reais, com milhar e sinal de menos tipográfico', () => {
        expect(reais(123456)).toBe('R$ 1.234,56');
        expect(reais(5)).toBe('R$ 0,05');
        expect(reais(-2550)).toBe('−R$ 25,50');
        expect(reaisComSinal(2550)).toBe('+R$ 25,50');
        expect(reaisComSinal(-2550)).toBe('−R$ 25,50');
    });

    it('lê o que a pessoa digita', () => {
        expect(lerReais('12,34')).toBe(1234);
        expect(lerReais('R$ 1.000,00')).toBe(100000);
        expect(lerReais('7')).toBe(700);
        expect(lerReais('0,5')).toBe(50);
        expect(lerReais('abc')).toBeNull();
        expect(lerReais('1,234')).toBeNull();
        expect(lerReais('')).toBeNull();
    });
});

describe('datas no horário de Brasília', () => {
    const agora = new Date('2026-10-17T17:00:00Z'); // 14h em Brasília

    it('diz hoje, ontem ou o dia', () => {
        expect(quando('2026-10-17T17:32:00Z', agora)).toBe('hoje, 14:32');
        expect(quando('2026-10-17T00:10:00Z', agora)).toBe('ontem, 21:10');
        expect(quando('2026-10-15T21:02:00Z', agora)).toBe('15/10, 18:02');
        expect(ehHoje('2026-10-17T03:30:00Z', agora)).toBe(true);
        expect(ehHoje('2026-10-17T02:30:00Z', agora)).toBe(false);
    });

    it('conta quanto falta para vencer', () => {
        expect(falta('2026-10-17T17:12:00Z', agora)).toBe('em 12 min');
        expect(falta('2026-10-17T18:05:00Z', agora)).toBe('em 1 h 5 min');
        expect(falta('2026-10-17T16:59:00Z', agora)).toBe('venceu');
    });
});
