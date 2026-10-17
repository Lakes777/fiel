-- Cobranças: o lojista pede um valor, quem paga lê o QR e paga uma vez.
--
-- Regras que o PRÓPRIO BANCO garante:
--   1. uma cobrança nasce pendente, sem pagador, e recebe numa carteira;
--   2. o que foi cobrado (código, recebedor, valor, descrição, datas) nunca muda;
--   3. o estado só anda para a frente: pendente -> paga, expirada ou cancelada. Os estados de
--      estorno já estão na lista, mas nenhuma transição leva a eles ainda (vêm com o estorno);
--   4. paga uma vez só: quem pagou, o lançamento e a hora não mudam depois. O lançamento move
--      exatamente o valor da carteira do pagador para a do recebedor (conferido no COMMIT,
--      como a transferência em V4);
--   5. cobrança não é apagada.

create table cobranca (
    id              bigint generated always as identity primary key,
    -- O que vai no QR: aleatório, para ninguém adivinhar a cobrança dos outros contando ids
    codigo          uuid        not null unique,
    recebedor_conta bigint      not null references conta (id),
    valor           bigint      not null check (valor > 0),
    descricao       text        not null check (length(descricao) between 1 and 140),
    estado          text        not null default 'pendente' check (
        estado in ('pendente', 'paga', 'expirada', 'cancelada', 'estornada', 'parcialmente_estornada')
    ),
    -- Do relógio da API, como em transferencia: os limites e a expiração contam por ele
    criada_em       timestamptz not null,
    expira_em       timestamptz not null,
    pagador_conta   bigint references conta (id),
    lancamento_id   bigint unique references lancamento (id),
    paga_em         timestamptz,
    check (expira_em > criada_em),
    check (pagador_conta <> recebedor_conta),
    -- Pago dentro da validade: nem cobrança vencida, nem paga_em "de ontem" para fugir da soma do limite
    check (paga_em is null or (paga_em >= criada_em and paga_em < expira_em)),
    -- Ou tem tudo do pagamento, ou nada
    check ((pagador_conta is null) = (lancamento_id is null) and (lancamento_id is null) = (paga_em is null)),
    -- Só as que foram pagas (inclusive as estornadas depois) têm pagamento
    check ((estado in ('pendente', 'expirada', 'cancelada')) = (lancamento_id is null))
);

create index cobranca_recebedor on cobranca (recebedor_conta, id);
-- Quanto saiu de uma carteira desde o início do dia (ou da noite): entra na soma dos limites
create index cobranca_pagador on cobranca (pagador_conta, paga_em) where pagador_conta is not null;
-- A tarefa de expiração procura só as pendentes
create index cobranca_pendente on cobranca (expira_em) where estado = 'pendente';

create trigger cobranca_id_da_sequencia
    before insert on cobranca
    for each row execute function id_da_sequencia();

create function cobranca_nasce_pendente() returns trigger
language plpgsql as $$
begin
    if new.estado <> 'pendente' then
        raise exception 'cobrança nasce pendente' using errcode = 'check_violation';
    end if;
    if not exists (select 1 from conta where id = new.recebedor_conta and tipo = 'carteira') then
        raise exception 'cobrança só recebe numa carteira' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger cobranca_nasce_pendente
    before insert on cobranca
    for each row execute function cobranca_nasce_pendente();

create function cobranca_so_anda_para_a_frente() returns trigger
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
       and not (old.estado = 'pendente' and new.estado in ('paga', 'expirada', 'cancelada')) then
        raise exception 'cobrança % não vai de % para %', old.id, old.estado, new.estado
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger cobranca_so_anda_para_a_frente
    before update on cobranca
    for each row execute function cobranca_so_anda_para_a_frente();

-- Regra 4, no COMMIT: até lá ainda dá para pôr partidas no lançamento (regra 4 de V1)
create function conferir_pagamento() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from conta where id = new.pagador_conta and tipo = 'carteira') then
        raise exception 'cobrança só é paga por uma carteira' using errcode = 'check_violation';
    end if;
    -- Um lançamento é de um fato só: o de uma transferência não serve de pagamento
    if exists (select 1 from transferencia where lancamento_id = new.lancamento_id) then
        raise exception 'o lançamento % já é de uma transferência', new.lancamento_id using errcode = 'check_violation';
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

create constraint trigger conferir_pagamento
    after update on cobranca
    deferrable initially deferred
    for each row
    when (old.estado = 'pendente' and new.estado = 'paga')
    execute function conferir_pagamento();

-- O outro lado da mesma regra: a transferência (V4) também recusa o lançamento de um pagamento
create or replace function conferir_transferencia() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from conta where id = new.de_conta and dono_id = new.usuario_id and tipo = 'carteira') then
        raise exception 'transferência só sai da carteira de quem pediu' using errcode = 'check_violation';
    end if;
    if not exists (select 1 from conta where id = new.para_conta and tipo = 'carteira') then
        raise exception 'transferência só chega a uma carteira' using errcode = 'check_violation';
    end if;
    if exists (select 1 from cobranca where lancamento_id = new.lancamento_id) then
        raise exception 'o lançamento % já é do pagamento de uma cobrança', new.lancamento_id
            using errcode = 'check_violation';
    end if;
    if (select array_agg(conta_id || ':' || valor order by conta_id) from partida where lancamento_id = new.lancamento_id)
       is distinct from (select array_agg(c || ':' || v order by c)
                         from (values (new.de_conta, -new.valor), (new.para_conta, new.valor)) as p (c, v)) then
        raise exception 'o lançamento % não é esta transferência', new.lancamento_id using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create function cobranca_nao_some() returns trigger
language plpgsql as $$
begin
    raise exception 'cobrança não é apagada: cancele' using errcode = 'restrict_violation';
end;
$$;

create trigger cobranca_nao_some
    before delete on cobranca
    for each row execute function cobranca_nao_some();

create trigger cobranca_sem_truncate
    before truncate on cobranca
    for each statement execute function cobranca_nao_some();
