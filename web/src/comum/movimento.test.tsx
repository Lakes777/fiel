import { render, screen } from '@testing-library/react';
import { useContagem, useMudou } from './movimento';

function Numero({ alvo, de }: { alvo: number | null; de?: number }) {
    return <p>{useContagem(alvo, { de })}</p>;
}

describe('useContagem', () => {
    it('o primeiro valor que chega aparece direto, sem contar do zero', () => {
        const { rerender } = render(<Numero alvo={null} />);
        expect(screen.getByText('0')).toBeInTheDocument();
        rerender(<Numero alvo={125000} />);
        expect(screen.getByText('125000')).toBeInTheDocument();
    });

    it('com `de`, começa de lá (a tela inicial conta do zero)', () => {
        render(<Numero alvo={136600} de={0} />);
        expect(screen.getByText('0')).toBeInTheDocument();
    });
});

function Selo({ valor }: { valor: string | undefined }) {
    return <p>{useMudou(valor) ? 'mudou' : 'parado'}</p>;
}

describe('useMudou', () => {
    it('o primeiro valor depois de carregar não pisca; a mudança seguinte sim', () => {
        const { rerender } = render(<Selo valor={undefined} />);
        rerender(<Selo valor="pendente" />);
        expect(screen.getByText('parado')).toBeInTheDocument();
        rerender(<Selo valor="pendente" />);
        expect(screen.getByText('parado')).toBeInTheDocument();
        rerender(<Selo valor="paga" />);
        expect(screen.getByText('mudou')).toBeInTheDocument();
    });
});
