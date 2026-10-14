# Fiel

**Fiel · uma plataforma de pagamentos onde a conta sempre fecha.**

[![CI](https://github.com/Lakes777/fiel/actions/workflows/ci.yml/badge.svg)](https://github.com/Lakes777/fiel/actions/workflows/ci.yml)

Carteiras digitais, transferências por chave, cobranças com QR e avisos para o lojista, com
**dinheiro de mentira**. O nome vem do fiel da balança: cada centavo que sai de uma conta entra
em outra, e o próprio banco de dados recusa um lançamento que não feche em zero.

É o meu primeiro projeto em **Kotlin**.

> Projeto de estudo. Não movimenta dinheiro de verdade, não usa CPF nem dado real, e o QR tem
> formato próprio, de propósito incompatível com aplicativos de banco.

## Fases

- [x] **1. Esqueleto:** Kotlin + Spring Boot 4, Gradle (Kotlin DSL), PostgreSQL 17 com Flyway, `/saude`, erros no formato RFC 9457, testes com Testcontainers, ktlint e detekt no CI
- [x] **2. Razão contábil:** partidas dobradas, valores em centavos (`Long`), lançamentos imutáveis e soma zero conferida pelo banco; testes de propriedade
- [ ] **3. Usuários e chaves:** Spring Security, cada um só enxerga as próprias contas
- [ ] **4. Transferências:** `Idempotency-Key`, saldo nunca negativo, limite diário e noturno, teste com 100 transferências simultâneas
- [ ] **5. Cobranças com QR:** formato TLV com CRC16, estados e expiração
- [ ] **6. Avisos ao lojista (webhooks):** outbox, novas tentativas com espera crescente, assinatura HMAC com carimbo de tempo, fila de falhas
- [ ] **7. Estorno e conciliação noturna:** com aviso no Telegram
- [ ] **8. Interface:** React (carteira e painel do lojista) com atualização ao vivo
- [ ] **9. Publicação:** demonstração no ar

## Razão contábil

Todo dinheiro vive num livro-razão de **partidas dobradas**. Um *lançamento* é um fato ("Ana
pagou R$ 10,50 à Loja"); cada *partida* diz quanto ele muda o saldo de uma conta (positivo entra,
negativo sai). O saldo de uma conta é a soma das partidas dela; não existe coluna de saldo que
possa ficar fora de sincronia.

| Ana paga R$ 10,50 à Loja | Conta | Valor (centavos) |
| --- | --- | --- |
| partida 1 | Ana | -1050 |
| partida 2 | Loja | +1050 |
| **soma** | | **0** |

As regras valem **dentro do Postgres** (gatilhos em
[`V1__razao_contabil.sql`](src/main/resources/db/migration/V1__razao_contabil.sql)), não só no
Kotlin, então nem um bug na API nem um SQL escrito à mão as quebram (para qualquer usuário do
banco que não seja o dono das tabelas: o dono pode desligar gatilhos, e por isso a API em produção
vai usar um usuário sem esse poder):

1. **As partidas de um lançamento somam zero.** Conferido no `COMMIT` por um gatilho *deferido*:
   no meio da transação a soma ainda não fecha, e tudo bem; se não fechar no fim, nada é gravado.
2. **Pelo menos duas partidas, em contas diferentes.**
3. **Nada muda nem some:** `UPDATE`, `DELETE` e `TRUNCATE` são recusados. Erro se corrige com um
   lançamento novo, como na contabilidade de verdade.
4. **Lançamento fechado não ganha partida nova.** Sem esta regra, um par +X/−X somado a um
   lançamento antigo manteria a soma zero e criaria dinheiro em uma conta. Cada lançamento guarda
   a transação que o criou (`pg_current_xact_id()`), e só ela pode pôr partidas nele.
5. **Ninguém escolhe id, data nem transação.** O banco recusa id escolhido à mão (que mudaria a
   ordem do histórico) e carimba ele mesmo a data e a transação de cada lançamento.

Os valores são `bigint` em centavos (nada de ponto flutuante com dinheiro). No Kotlin viram um
tipo próprio, `Centavos`, que estoura com erro em vez de dar a volta.

**Testes de propriedade** ([kotest-property](https://kotest.io/docs/proptest/property-based-testing.html)):
sequências aleatórias de lançamentos contra o Postgres de verdade, conferindo cada saldo com uma
conta feita à parte, e centenas de lançamentos que não fecham, escritos direto em SQL, que o banco
precisa recusar. Cada regra foi conferida com uma contraprova: desligada de propósito, algum teste
quebra.

A razão ainda não tem rotas HTTP: elas chegam com os usuários (fase 3), para nenhuma rota de
dinheiro existir sem login.

## Rodando

Precisa do Java 21 e do Docker.

```bash
docker compose up -d        # Postgres 17 de desenvolvimento (porta 5436)
./gradlew bootRun           # http://localhost:8080/saude
```

Sem o compose, `./gradlew bootTestRun` sobe a API com um Postgres temporário do Testcontainers.

```bash
./gradlew build             # ktlint, detekt e testes
./gradlew ktlintFormat      # corrige o estilo
```

## Configuração

| Variável | Padrão (desenvolvimento) | Para quê |
| --- | --- | --- |
| `FIEL_BANCO_URL` | `jdbc:postgresql://localhost:5436/fiel` | Endereço do Postgres |
| `FIEL_BANCO_USUARIO` | `fiel` | Usuário do banco |
| `FIEL_BANCO_SENHA` | `fiel` | Senha do banco |
| `FIEL_PORTA` | `8080` | Porta da API |

## `/saude`

`GET /saude` responde `200 {"status":"ok","banco":"ok"}` quando a API alcança o banco e
`503 {"status":"erro","banco":"fora do ar"}` quando não alcança, sem mostrar o erro (que pode ter
endereço interno); o motivo vai para o log. Nunca fica em cache (`Cache-Control: no-store`).

Com o banco inalcançável, o 503 pode levar até uns 7 s (até 5 s esperando uma conexão do pool e
2 s para ela responder): um monitor precisa esperar mais do que isso (o Vigil espera 10 s).
