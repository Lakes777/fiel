import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * Em dev, o Vite repassa /api para a API (./gradlew bootRun na raiz, porta 8080), tirando o /api.
 * Em produção o proxy (Caddy) faz o mesmo: as rotas da tela (/carteira, /loja) não colidem com as
 * da API (/contas, /cobrancas), e o cookie da sessão fica na mesma origem.
 */
const API = process.env.FIEL_API ?? 'http://localhost:8080';

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5190,
        proxy: {
            '/api': {
                target: API,
                rewrite: (caminho) => caminho.replace(/^\/api/, ''),
            },
        },
    },
    test: {
        environment: 'jsdom',
        globals: true,
        setupFiles: ['./src/teste/preparar.ts'],
        include: ['src/**/*.test.{ts,tsx}'],
    },
});
