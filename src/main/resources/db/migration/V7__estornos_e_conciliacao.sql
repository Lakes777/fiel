-- Estornos de cobranças e o registro da conciliação noturna.
--
-- Estorno: o lojista devolve a quem pagou uma cobrança, tudo ou em partes, sem prazo. Cada estorno
-- é um lançamento novo, da carteira do lojista para a do pagador: o pagamento original nunca muda.
--
-- Regras que o PRÓPRIO BANCO garante:
--   1. só cobrança paga (ou já estornada em parte) recebe estorno;
--   2. a soma dos estornos de uma cobrança nunca passa do valor dela, nem com dois ao mesmo tempo;
--   3. o estado da cobrança segue a soma: só o gatilho do estorno leva a parcialmente_estornada ou
--      a estornada (como o saldo, que só o gatilho das partidas mexe), e um UPDATE à mão é recusado;
--   4. o lançamento do estorno move exatamente o valor da carteira do recebedor para a do pagador
--      (conferido no COMMIT, como a transferência e o pagamento), e não é o de outro fato;
--   5. estorno não muda nem é apagado.

create table estorno (
    id                 bigint generated always as identity primary key,
    cobranca_id        bigint      not null references cobranca (id),
    -- A Idempotency-Key vale por cobrança; o resumo (SHA-256) do pedido recusa a mesma chave com outro pedido
    chave_idempotencia text        not null check (length(chave_idempotencia) between 1 and 100),
    resumo_do_pedido   text        not null check (length(resumo_do_pedido) = 64),
    valor              bigint      not null check (valor > 0),
    motivo             text check (length(motivo) between 1 and 140),
    lancamento_id      bigint      not null unique references lancamento (id),
    -- Do relógio da API, como o resto
    criado_em          timestamptz not null,
    unique (cobranca_id, chave_idempotencia)
);

create index estorno_da_cobranca on estorno (cobranca_id, id);

create trigger estorno_id_da_sequencia
    before insert on estorno
    for each row execute function id_da_sequencia();

-- Regras 1 e 2, na hora. A cobrança é travada antes de somar: dois estornos ao mesmo tempo, o
-- segundo espera o primeiro terminar e soma com ele.
create function estorno_cabe_na_cobranca() returns trigger
language plpgsql as $$
declare
    cobrada bigint;
    estado_atual text;
    ja_estornado bigint;
begin
    select valor, estado into cobrada, estado_atual from cobranca where id = new.cobranca_id for update;
    if estado_atual not in ('paga', 'parcialmente_estornada') then
        raise exception 'só cobrança paga pode ser estornada; esta está %', estado_atual
            using errcode = 'check_violation';
    end if;
    select coalesce(sum(valor), 0) into ja_estornado from estorno where cobranca_id = new.cobranca_id;
    if ja_estornado + new.valor > cobrada then
        raise exception 'os estornos passam do valor da cobrança %', new.cobranca_id using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger estorno_cabe_na_cobranca
    before insert on estorno
    for each row execute function estorno_cabe_na_cobranca();

-- Regra 3: o estado da cobrança acompanha a soma dos estornos
create function estorno_no_estado_da_cobranca() returns trigger
language plpgsql as $$
begin
    update cobranca c
    set estado = case when (select sum(valor) from estorno where cobranca_id = c.id) = c.valor
                      then 'estornada' else 'parcialmente_estornada' end
    where c.id = new.cobranca_id;
    return null;
end;
$$;

create trigger estorno_no_estado_da_cobranca
    after insert on estorno
    for each row execute function estorno_no_estado_da_cobranca();

create or replace function cobranca_so_anda_para_a_frente() returns trigger
language plpgsql as $$
begin
    if new.id <> old.id or new.codigo <> old.codigo or new.recebedor_conta <> old.recebedor_conta
       or new.valor <> old.valor or new.descricao <> old.descricao
       or new.criada_em <> old.criada_em or new.expira_em <> old.expira_em then
        raise exception 'o que foi cobrado não muda' using errcode = 'restrict_violation';
    end if;
    -- Pagamento gravado não muda (vale também para os estados de estorno, que vêm depois de paga)
    if old.lancamento_id is not null and (new.pagador_conta is distinct from old.pagador_conta
       or new.lancamento_id is distinct from old.lancamento_id or new.paga_em is distinct from old.paga_em) then
        raise exception 'o pagamento de uma cobrança não muda' using errcode = 'restrict_violation';
    end if;
    if new.estado is distinct from old.estado
       and not (old.estado = 'pendente' and new.estado in ('paga', 'expirada', 'cancelada'))
       -- pg_trigger_depth() >= 2: o UPDATE vem de dentro de um gatilho; escrito à mão seria 1. Hoje
       -- só o gatilho do estorno mexe em cobrança: um gatilho novo que mexa também passaria por aqui
       and not (old.estado in ('paga', 'parcialmente_estornada')
                and new.estado in ('parcialmente_estornada', 'estornada') and pg_trigger_depth() >= 2) then
        raise exception 'cobrança % não vai de % para %', old.id, old.estado, new.estado
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

-- Regra 4, no COMMIT: até lá ainda dá para pôr partidas no lançamento (regra 4 de V1)
create function conferir_estorno() returns trigger
language plpgsql as $$
declare
    pagador bigint;
    recebedor bigint;
begin
    select pagador_conta, recebedor_conta into pagador, recebedor from cobranca where id = new.cobranca_id;
    if exists (select 1 from transferencia where lancamento_id = new.lancamento_id)
       or exists (select 1 from cobranca where lancamento_id = new.lancamento_id) then
        raise exception 'o lançamento % já é de outro fato', new.lancamento_id using errcode = 'check_violation';
    end if;
    if (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = new.lancamento_id)
       is distinct from (select array_agg(c || ':' || v order by c)
                         from (values (recebedor, -new.valor), (pagador, new.valor)) as p (c, v)) then
        raise exception 'o lançamento % não é o estorno %', new.lancamento_id, new.id using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger conferir_estorno
    after insert on estorno
    deferrable initially deferred
    for each row execute function conferir_estorno();

-- O outro lado da regra 4: transferência e pagamento também recusam o lançamento de um estorno
create or replace function conferir_transferencia() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from conta where id = new.de_conta and dono_id = new.usuario_id and tipo = 'carteira') then
        raise exception 'transferência só sai da carteira de quem pediu' using errcode = 'check_violation';
    end if;
    if not exists (select 1 from conta where id = new.para_conta and tipo = 'carteira') then
        raise exception 'transferência só chega a uma carteira' using errcode = 'check_violation';
    end if;
    if exists (select 1 from cobranca where lancamento_id = new.lancamento_id)
       or exists (select 1 from estorno where lancamento_id = new.lancamento_id) then
        raise exception 'o lançamento % já é de outro fato', new.lancamento_id using errcode = 'check_violation';
    end if;
    if (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = new.lancamento_id)
       is distinct from (select array_agg(c || ':' || v order by c)
                         from (values (new.de_conta, -new.valor), (new.para_conta, new.valor)) as p (c, v)) then
        raise exception 'o lançamento % não é esta transferência', new.lancamento_id using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create or replace function conferir_pagamento() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from conta where id = new.pagador_conta and tipo = 'carteira') then
        raise exception 'cobrança só é paga por uma carteira' using errcode = 'check_violation';
    end if;
    if exists (select 1 from transferencia where lancamento_id = new.lancamento_id)
       or exists (select 1 from estorno where lancamento_id = new.lancamento_id) then
        raise exception 'o lançamento % já é de outro fato', new.lancamento_id using errcode = 'check_violation';
    end if;
    if (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = new.lancamento_id)
       is distinct from (select array_agg(c || ':' || v order by c)
                         from (values (new.pagador_conta, -new.valor), (new.recebedor_conta, new.valor)) as p (c, v)) then
        raise exception 'o lançamento % não é o pagamento da cobrança %', new.lancamento_id, new.id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

-- Regra 5
create function estorno_imutavel() returns trigger
language plpgsql as $$
begin
    raise exception 'estorno não muda nem é apagado' using errcode = 'restrict_violation';
end;
$$;

create trigger estorno_imutavel
    before update or delete on estorno
    for each row execute function estorno_imutavel();

create trigger estorno_sem_truncate
    before truncate on estorno
    for each statement execute function estorno_imutavel();

-- O lojista também é avisado do estorno
alter table aviso drop constraint aviso_tipo_check;
alter table aviso add constraint aviso_tipo_check
    check (tipo in ('cobranca.paga', 'cobranca.expirada', 'cobranca.estornada', 'transferencia.recebida'));

-- Uma conciliação por dia (no horário de Brasília): com duas instâncias da API, a segunda a gravar
-- encontra o dia ocupado e não manda o aviso de novo.
create table conciliacao (
    id           bigint generated always as identity primary key,
    dia          date        not null unique,
    feita_em     timestamptz not null,
    -- O que não fechou, uma frase por problema (vazio = tudo fecha)
    divergencias jsonb       not null check (jsonb_typeof(divergencias) = 'array'),
    -- Os números do dia, para o aviso e para comparar um dia com o outro
    totais       jsonb       not null check (jsonb_typeof(totais) = 'object'),
    -- Quando o Telegram aceitou a mensagem; nula = ainda não chegou, e a tarefa tenta de novo
    avisada_em   timestamptz
);
