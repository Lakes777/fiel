import { useEffect, useRef } from 'react';

/**
 * Marca o elemento com .revelado quando ele entra na tela (o tema.css faz a entrada). Sem
 * IntersectionObserver (navegador antigo, testes), marca na hora: o conteúdo nunca fica escondido.
 */
export function useRevelar<T extends HTMLElement>() {
    const ref = useRef<T>(null);
    useEffect(() => {
        const elemento = ref.current;
        if (!elemento) return;
        elemento.classList.add('revelar');
        if (typeof IntersectionObserver === 'undefined') {
            elemento.classList.add('revelado');
            return;
        }
        const observador = new IntersectionObserver(
            (entradas) => {
                if (entradas.some((e) => e.isIntersecting)) {
                    elemento.classList.add('revelado');
                    observador.disconnect();
                }
            },
            { rootMargin: '0px 0px -10% 0px' },
        );
        observador.observe(elemento);
        return () => observador.disconnect();
    }, []);
    return ref;
}
