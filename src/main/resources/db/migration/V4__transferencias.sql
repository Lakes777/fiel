-- Saldo guardado na conta e transferências entre carteiras.
--
-- Regras que o PRÓPRIO BANCO garante:
--   1. conta.saldo é sempre a soma das partidas da conta: só o gatilho das partidas mexe nele
--      (um UPDATE à mão é recusado, como o contador de chaves em V2);
--   2. carteira nunca fica negativa. Só a emissão (menos todo o dinheiro em circulação) e as
--      contas internas do sistema podem;
--   3. uma transferência aponta para o lançamento que moveu o dinheiro, e nunca muda;
--   4. a mesma Idempotency-Key do mesmo usuário vale uma transferência só.
--
-- Antes, o saldo era um sum() de todas as partidas da conta: ficava mais lento a cada movimento.
-- Guardado, ele também vira a trava natural da conta: o gatilho dá UPDATE na linha dela.

alter table conta add column saldo bigint not null default 0;

-- Contas que já existem: o saldo começa na soma de hoje. Roda antes de conta_dono_imutavel passar a
-- vigiar o saldo (logo abaixo), senão este UPDATE seria recusado.
update conta c set saldo = coalesce((select sum(p.valor) from partida p where p.conta_id = c.id), 0);

alter table conta add constraint carteira_nunca_negativa check (saldo >= 0 or tipo in ('emissao', 'interna'));

create or replace function conta_dono_imutavel() returns trigger
language plpgsql as $$
begin
    if new.dono_id is distinct from old.dono_id or new.tipo is distinct from old.tipo or new.id <> old.id then
        raise exception 'o dono e o tipo de uma conta não mudam'
            using errcode = 'restrict_violation';
    end if;
    -- pg_trigger_depth() = 1: um UPDATE escrito à mão; os gatilhos de chave e de partida rodam um nível abaixo
    if new.chaves is distinct from old.chaves and pg_trigger_depth() < 2 then
        raise exception 'o número de chaves de uma conta só muda criando ou apagando chaves'
            using errcode = 'restrict_violation';
    end if;
    if new.saldo is distinct from old.saldo and pg_trigger_depth() < 2 then
        raise exception 'o saldo de uma conta só muda com lançamento'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

-- Conta nasce zerada: o saldo só vem de lançamento, e o contador de chaves só de chave criada.
-- Sem isto, um INSERT à mão criaria uma carteira com saldo que o razão não conhece.
create function conta_nasce_zerada() returns trigger
language plpgsql as $$
begin
    if new.saldo <> 0 or new.chaves <> 0 then
        raise exception 'conta nova começa com saldo e chaves zerados' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger conta_nasce_zerada
    before insert on conta
    for each row execute function conta_nasce_zerada();

-- Cada partida soma no saldo da conta dela. Uma saída maior que o saldo da carteira esbarra em
-- carteira_nunca_negativa na hora, e o lançamento inteiro é desfeito.
create function partida_no_saldo() returns trigger
language plpgsql as $$
begin
    update conta set saldo = saldo + new.valor where id = new.conta_id;
    return null;
end;
$$;

create trigger partida_no_saldo
    after insert on partida
    for each row execute function partida_no_saldo();

create table transferencia (
    id                  bigint generated always as identity primary key,
    usuario_id          bigint      not null references usuario (id),
    -- O que o cliente mandou no cabeçalho Idempotency-Key, e o resumo (SHA-256) do pedido: a mesma
    -- chave com outro pedido é recusada, em vez de devolver uma transferência que não é a pedida
    chave_idempotencia  text        not null check (length(chave_idempotencia) between 1 and 100),
    resumo_do_pedido    text        not null check (length(resumo_do_pedido) = 64),
    de_conta            bigint      not null references conta (id),
    para_conta          bigint      not null references conta (id),
    valor               bigint      not null check (valor > 0),
    lancamento_id       bigint      not null unique references lancamento (id),
    -- Vem do relógio da API, não de now(): os limites diário e noturno contam por ele, e os testes
    -- precisam mudar a hora
    criada_em           timestamptz not null,
    check (de_conta <> para_conta),
    unique (usuario_id, chave_idempotencia)
);

-- Quanto saiu de uma conta desde o início do dia (ou da noite): é a soma dos limites
create index transferencia_de_conta on transferencia (de_conta, criada_em);

create trigger transferencia_id_da_sequencia
    before insert on transferencia
    for each row execute function id_da_sequencia();

-- A transferência diz a verdade: vai de carteira a carteira, sai da de quem pediu, e o lançamento dela
-- move exatamente esse valor entre essas duas contas (e nada mais). Conferido no COMMIT (gatilho
-- deferido, como lancamento_fecha): até lá ainda dá para pôr partidas no lançamento (regra 4 de V1),
-- e uma conferência na hora do INSERT deixaria passar uma partida acrescentada depois.
create function conferir_transferencia() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from conta where id = new.de_conta and dono_id = new.usuario_id and tipo = 'carteira') then
        raise exception 'transferência só sai da carteira de quem pediu' using errcode = 'check_violation';
    end if;
    if not exists (select 1 from conta where id = new.para_conta and tipo = 'carteira') then
        raise exception 'transferência só chega a uma carteira' using errcode = 'check_violation';
    end if;
    if (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = new.lancamento_id)
       is distinct from (select array_agg(c || ':' || v order by c)
                         from (values (new.de_conta, -new.valor), (new.para_conta, new.valor)) as p (c, v)) then
        raise exception 'o lançamento % não é esta transferência', new.lancamento_id using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger conferir_transferencia
    after insert on transferencia
    deferrable initially deferred
    for each row execute function conferir_transferencia();

-- Regra 3: a transferência registra um fato, como o razão
create function transferencia_imutavel() returns trigger
language plpgsql as $$
begin
    raise exception 'transferência não muda nem é apagada: o estorno é outro lançamento'
        using errcode = 'restrict_violation';
end;
$$;

create trigger transferencia_imutavel
    before update or delete on transferencia
    for each row execute function transferencia_imutavel();

create trigger transferencia_sem_truncate
    before truncate on transferencia
    for each statement execute function transferencia_imutavel();
