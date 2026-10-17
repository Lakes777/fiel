import { render, screen } from '@testing-library/react';
import { useContagem } from './movimento';

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
