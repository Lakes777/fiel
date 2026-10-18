// Antes de pintar: o tema salvo (ou o do aparelho), sem piscar o claro no escuro.
// Arquivo à parte (e não <script> dentro do index.html): a política de segurança do site (CSP)
// só aceita script vindo de arquivo do próprio endereço.
try {
    var t = localStorage.getItem('fiel-tema');
    if (t !== 'claro' && t !== 'escuro')
        t = matchMedia('(prefers-color-scheme: dark)').matches ? 'escuro' : 'claro';
    document.documentElement.dataset.tema = t;
} catch (e) {
    document.documentElement.dataset.tema = 'claro';
}
