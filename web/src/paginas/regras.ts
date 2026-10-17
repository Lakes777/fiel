/**
 * Para onde ir depois de entrar. Só caminho deste site: "//outro.site" e "/\outro.site" o navegador
 * entende como outro endereço, e um ?volta= forjado mandaria a pessoa para lá já logada.
 */
export function destinoDaVolta(volta: string | null): string {
    if (
        !volta ||
        !volta.startsWith('/') ||
        volta.startsWith('//') ||
        volta.startsWith('/\\')
    )
        return '/carteira';
    return volta;
}

/** O mesmo ?volta= levado para a outra tela de acesso (Entrar <-> Criar conta), se é um caminho aceito */
export function comVolta(caminho: string, volta: string | null): string {
    if (!volta || destinoDaVolta(volta) !== volta) return caminho;
    return `${caminho}?volta=${encodeURIComponent(volta)}`;
}

/** O bcrypt só usa 72 bytes: acento e símbolo ocupam mais de um, então conta bytes, não letras */
export const MIN_SENHA = 10;
export const MAX_SENHA_BYTES = 72;

/** O problema da senha, como frase para o campo; null se está boa (as mesmas regras da API) */
export function problemaDaSenha(senha: string): string | null {
    if (senha.length < MIN_SENHA)
        return `A senha precisa ter pelo menos ${MIN_SENHA} caracteres.`;
    if (new TextEncoder().encode(senha).length > MAX_SENHA_BYTES)
        return `A senha pode ter no máximo ${MAX_SENHA_BYTES} bytes (cerca de 72 letras sem acento).`;
    return null;
}
