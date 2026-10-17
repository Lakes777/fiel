import { render, screen, within } from '@testing-library/react';
import { VisaoDaCarteira } from './VisaoDaCarteira';

const AGORA = new Date('2026-10-17T17:00:00Z');

describe('VisaoDaCarteira', () => {
    it('mostra saldo, limites e extrato com entrada e saída', () => {
        render(
            <VisaoDaCarteira
                nome="Ana Souza"
                saldoCentavos={125000}
                limites={{
                    diaLimiteCentavos: 500000,
                    diaUsadoCentavos: 2550,
                    noiteLimiteCentavos: 100000,
                    noiteUsadoCentavos: 0,
                    ehNoite: false,
                    disponivelCentavos: 497450,
                }}
                extrato={[
                    {
                        lancamentoId: 2,
                        descricao: 'Estorno de Bob L. a Ana S.: Caneca',
                        em: '2026-10-17T17:32:00Z',
                        valorCentavos: 2550,
                        saldoDepoisCentavos: 125000,
                    },
                    {
                        lancamentoId: 1,
                        descricao: 'Pagamento de Ana S. a Bob L.: Caneca',
                        em: '2026-10-17T17:05:00Z',
                        valorCentavos: -2550,
                        saldoDepoisCentavos: 122450,
                    },
                ]}
                acoes={<button>Transferir</button>}
                agora={AGORA}
            />,
        );
        expect(screen.getByText('R$ 1.250,00')).toBeInTheDocument();
        expect(screen.getByText('R$ 4.974,50')).toBeInTheDocument();
        const [estorno, pagamento] = screen.getAllByRole('listitem');
        expect(within(estorno).getByText('+R$ 25,50')).toHaveClass('entra');
        expect(within(pagamento).getByText('−R$ 25,50')).toHaveClass('sai');
        expect(within(estorno).getByText('hoje, 14:32')).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: 'Transferir' }),
        ).toBeInTheDocument();
    });

    it('mostra esqueleto enquanto carrega e um aviso se não há movimento', () => {
        const { rerender } = render(
            <VisaoDaCarteira
                nome="Ana"
                saldoCentavos={null}
                limites={null}
                extrato={null}
                acoes={null}
            />,
        );
        expect(document.querySelectorAll('[aria-busy="true"]')).toHaveLength(2);
        rerender(
            <VisaoDaCarteira
                nome="Ana"
                saldoCentavos={0}
                limites={null}
                extrato={[]}
                acoes={null}
            />,
        );
        expect(screen.getByText('Nenhum movimento ainda.')).toBeInTheDocument();
    });
});
