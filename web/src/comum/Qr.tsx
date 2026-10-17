import qrcode from 'qrcode-generator';
import { useMemo } from 'react';

/**
 * O QR de verdade de uma cobrança (o texto TLV que a API devolve em `qr`). Sempre escuro sobre
 * claro, também no tema escuro: leitor de QR não lê invertido. `desenhar` faz os módulos surgirem
 * em diagonal, uma vez (tela inicial, cobrança nova).
 */
export function Qr({
    texto,
    tamanho = 180,
    desenhar = false,
}: {
    texto: string;
    tamanho?: number;
    desenhar?: boolean;
}) {
    const modulos = useMemo(() => {
        const codigo = qrcode(0, 'M');
        codigo.addData(texto);
        codigo.make();
        const n = codigo.getModuleCount();
        const lista: { x: number; y: number }[] = [];
        for (let y = 0; y < n; y++)
            for (let x = 0; x < n; x++)
                if (codigo.isDark(y, x)) lista.push({ x, y });
        return { n, lista };
    }, [texto]);
    const margem = 2;
    const lado = modulos.n + margem * 2;
    return (
        <svg
            className={`qr${desenhar ? ' qr-desenhar' : ''}`}
            viewBox={`0 0 ${lado} ${lado}`}
            width={tamanho}
            height={tamanho}
            role="img"
            aria-label="QR da cobrança"
            shapeRendering="crispEdges"
        >
            <rect width={lado} height={lado} fill="var(--qr-fundo)" rx="1.5" />
            {modulos.lista.map(({ x, y }) => (
                <rect
                    key={`${x}-${y}`}
                    x={x + margem}
                    y={y + margem}
                    width="1"
                    height="1"
                    fill="var(--qr)"
                    style={
                        desenhar
                            ? { animationDelay: `${(x + y) * 14}ms` }
                            : undefined
                    }
                />
            ))}
        </svg>
    );
}
