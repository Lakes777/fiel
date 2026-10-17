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
- [x] **3. Usuários e chaves:** Spring Security com sessão no Postgres e CSRF, cada um só enxerga as próprias contas
- [x] **4. Transferências:** `Idempotency-Key`, saldo nunca negativo, limite diário e noturno, teste com 100 transferências simultâneas
- [ ] **5. Cobranças com QR:** formato TLV com CRC16, estados e expiração
- [ ] **6. Avisos ao lojista (webhooks):** outbox, novas tentativas com espera crescente, assinatura HMAC com carimbo de tempo, fila de falhas
- [ ] **7. Estorno e conciliação noturna:** com aviso no Telegram
- [ ] **8. Interface:** React (carteira e painel do lojista) com atualização ao vivo
- [ ] **9. Publicação:** demonstração no ar

## Razão contábil

Todo dinheiro vive num livro-razão de **partidas dobradas**. Um *lançamento* é um fato ("Ana
pagou R$ 10,50 à Loja"); cada *partida* diz quanto ele muda o saldo de uma conta (positivo entra,
negativo sai). O saldo de uma conta é a soma das partidas dela. Ele fica guardado na conta para
ler rápido, mas só um gatilho das partidas mexe nele (veja [Transferências](#transferências)), e
os testes de propriedade conferem que ele bate com a soma.

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

## Usuários, login e chaves

Cada pessoa tem uma **carteira**. No cadastro, a conta **Emissão** do sistema paga R$ 1.000,00 de
mentira a ela, num lançamento normal do razão: a soma de todo o sistema continua zero, e o saldo
da Emissão é sempre menos todo o dinheiro em circulação.

**Login por sessão, não por JWT.** O login devolve um cookie `FIEL_SESSAO` (HttpOnly, SameSite
Strict, só HTTPS) com o id de uma sessão guardada no Postgres (Spring Session JDBC). Por isso dá
para **sair de verdade**: `DELETE /sessao` apaga a sessão na hora, e `DELETE /sessoes` derruba
todos os aparelhos. Um JWT continuaria valendo até vencer.

Como o navegador manda o cookie sozinho, todo pedido que muda algo exige também o **token CSRF**
(cookie `XSRF-TOKEN`, devolvido no cabeçalho `X-XSRF-TOKEN`), que um site de fora não consegue
ler. Vale até para o login, senão um site poderia logar você na conta dele.

| Proteção | Como |
| --- | --- |
| Senha | bcrypt (`{bcrypt}` no hash, dá para trocar de algoritmo depois); de 10 caracteres a 72 bytes, o limite do bcrypt, em vez de cortar sem aviso |
| Força bruta | 5 tentativas de login por e-mail a cada 15 min (429 com `Retry-After`), contadas **antes** de conferir a senha: 20 pedidos ao mesmo tempo ainda testam só 5 senhas (um teste conta as conferências do bcrypt). Senha certa zera a contagem |
| Quem tem conta | No login, senha errada e e-mail sem cadastro dão a mesma resposta, no mesmo tempo. **O cadastro ainda revela** (409 para e-mail já usado); o certo é confirmar por e-mail, que o projeto não manda. Por isso o cadastro tem limite: 10 por hora por IP |
| Fixação de sessão | O id da sessão e o token CSRF mudam no login |
| Conta de outra pessoa | **Autorização por objeto:** a conta (ou chave) de outra pessoa responde 404, igual a uma que não existe |
| Varredura de chaves | 30 consultas por minuto por usuário; a consulta mostra só "Ana S." |
| A sessão no banco | Guarda o usuário sem o hash da senha |

**Chaves** são apelidos para a carteira: o **e-mail do próprio dono** (ninguém cadastra o e-mail de
outra pessoa para receber no lugar dela) e **aleatórias** (UUID). Nada de CPF ou telefone. No
máximo 5 por conta.

Como no razão, as regras que importam também estão **no banco**
([`V2__usuarios_e_chaves.sql`](src/main/resources/db/migration/V2__usuarios_e_chaves.sql)): o dono
e o tipo de uma conta nunca mudam (trocar o dono seria mover o dinheiro sem lançamento), só
carteira tem dono, uma por pessoa, o e-mail de um usuário não muda, e o limite de 5 chaves vale
mesmo com duas criadas ao mesmo tempo, em qualquer nível de isolamento: a conta guarda um
contador de chaves (com `check` de 0 a 5) que só os gatilhos mexem. Duas inserções simultâneas
atualizam a mesma linha, então uma espera a outra. Um `count(*)` com `for update` (a primeira
versão) falhava em `REPEATABLE READ`, onde a contagem enxerga a "foto" do começo da transação; os
testes seguram uma transação aberta nos dois níveis para provar que a segunda não passa.

| Rota | O que faz |
| --- | --- |
| `GET /csrf` | Entrega o token CSRF (chamar antes do primeiro POST) |
| `POST /cadastro` | `{nome, email, senha}`: cria usuário, carteira e bônus |
| `POST /sessao` | `{email, senha}`: entra |
| `GET /eu` | Quem está logado |
| `DELETE /sessao` / `DELETE /sessoes` | Sai deste aparelho / de todos |
| `GET /contas` | Minhas contas, com saldo em centavos |
| `GET /contas/{id}/extrato?limite=50` | Extrato, mais recente primeiro, com o saldo depois de cada movimento |
| `GET /chaves`, `POST /chaves` `{tipo}`, `DELETE /chaves/{id}` | Minhas chaves (`email` ou `aleatoria`) |
| `POST /chaves/consulta` `{valor}` | De quem é a chave ("Ana S."); POST para a chave não ir parar na URL |

## Transferências

`POST /transferencias` com `{chave, valorCentavos}` manda dinheiro da carteira de quem está logado
para a carteira dona da chave. A resposta mostra o recebedor só como "Bob L.".

**`Idempotency-Key` obrigatória.** Se a rede cai depois do envio, o app não sabe se a
transferência foi feita e manda de novo. Com a mesma chave, a API devolve a **mesma**
transferência (cabeçalho `Idempotent-Replayed: true`), sem cobrar duas vezes. A mesma chave com
outro pedido (outro valor, outro recebedor) é recusada com 422. Vinte cópias do mesmo pedido
mandadas ao mesmo tempo fazem uma transferência só: a chave é travada com
`pg_advisory_xact_lock` e as cópias esperam a primeira terminar. A chave vale por usuário, e uma
transferência recusada (saldo, limite) não fica guardada, então repetir tenta de novo.

**Ninguém gasta o mesmo dinheiro duas vezes.** Dentro de uma transação, as duas contas são
travadas (`SELECT ... FOR UPDATE`) **sempre em ordem de id**, e só então saldo e limites são
conferidos. Ana -> Bob e Bob -> Ana ao mesmo tempo travam na mesma ordem: uma espera a outra, sem
deadlock. Testes: 100 transferências simultâneas de R$ 15 com R$ 1.000 de saldo dão exatamente 66
aceitas e 34 recusadas; 60 cruzadas entre Ana e Bob terminam todas, com os saldos de volta ao
começo.

**O banco é a última palavra** ([`V4__transferencias.sql`](src/main/resources/db/migration/V4__transferencias.sql)):

- o saldo fica guardado na conta, e só o gatilho das partidas mexe nele. Ler o saldo deixou de
  ser uma soma de todo o histórico, e os testes de propriedade conferem que ele bate com a soma;
- `check (saldo >= 0)` em toda carteira: nem um lançamento escrito à mão no razão deixa uma
  carteira negativa. Na contraprova (sem a trava da aplicação), as 100 simultâneas deram 500 em
  algumas, mas nenhum centavo a mais saiu;
- conta nasce com saldo zero: um INSERT à mão não cria carteira com dinheiro que o razão não conhece;
- a transferência confere o próprio lançamento (mesmas contas, mesmo valor, nada mais), no
  **commit**, para nem uma partida acrescentada depois passar; sai só da carteira de quem pediu e
  nunca muda nem é apagada.

**Limites**, no horário de Brasília, como a regra do Banco Central para a noite:

| Período | Limite |
| --- | --- |
| Por dia (a partir da meia-noite) | R$ 5.000,00 |
| À noite (20h às 6h, atravessando a meia-noite) | R$ 1.000,00 |

Os dois valem ao mesmo tempo; a mensagem do 422 diz qual estourou e quanto ainda dá para mandar.
A hora vem de um `Clock` injetado: os testes param o relógio às 23h, à 1h, às 6h. Também há um
limite de 30 pedidos por minuto por usuário, para transferências de 1 centavo não servirem para
varrer chaves.

**Risco aceito: juntar bônus.** Cada cadastro ganha R$ 1.000, e com transferências dá para criar
contas e mandar tudo para uma só. O freio é o limite de 10 cadastros por hora por IP. Um banco de
verdade fecharia a porta com verificação de identidade, que este projeto não faz de propósito
(nada de CPF nem dado real). Travar o bônus só para pagamentos não resolveria: o dinheiro sairia
pela cobrança de uma "loja" da mesma pessoa.

| Status | Quando |
| --- | --- |
| 201 | Feita (ou repetida, com `Idempotent-Replayed`) |
| 400 | Sem `Idempotency-Key`, valor zero ou negativo, chave da própria carteira |
| 404 | Chave que não existe |
| 422 | Saldo insuficiente, limite passado, `Idempotency-Key` já usada em outro pedido |
| 429 | Mais de 30 pedidos por minuto |

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
