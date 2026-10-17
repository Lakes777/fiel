import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import { trocarEventosParaTeste } from '../api/eventos';
import { reiniciarSessaoParaTeste } from '../api/sessao';
import { EventosFalsos } from './eventos-falsos';

/**
 * Nenhum teste fala com um servidor de verdade: o EventSource é sempre o falso (`eventosFalsos()`
 * devolve o desta rodada, para o teste emitir eventos), e o fetch é o da apiFalsa.
 */
let falsos = new EventosFalsos();

export function eventosFalsos(): EventosFalsos {
    return falsos;
}

beforeEach(() => {
    falsos = new EventosFalsos();
    trocarEventosParaTeste(falsos.fabrica);
    document.cookie = 'XSRF-TOKEN=token-de-teste';
});

afterEach(() => {
    cleanup();
    reiniciarSessaoParaTeste();
    trocarEventosParaTeste(null);
    vi.unstubAllGlobals();
    vi.useRealTimers();
    document.documentElement.dataset.tema = 'claro';
    localStorage.clear();
});
