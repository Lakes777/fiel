-- Razão contábil em partidas dobradas.
--
-- Um lançamento é um fato ("Ana pagou R$ 10,00 à Loja"); cada partida diz quanto ele muda o
-- saldo de uma conta: positivo entra, negativo sai. Regras que o PRÓPRIO BANCO garante, mesmo
-- que a aplicação tenha um bug ou alguém escreva SQL à mão:
--   1. as partidas de um lançamento somam zero (dinheiro não nasce nem some);
--   2. um lançamento tem pelo menos duas partidas, em contas diferentes;
--   3. lançamentos e partidas nunca mudam nem são apagados (erro se corrige com outro lançamento);
--   4. não se acrescenta partida a um lançamento de outra transação.
-- Valores em centavos (bigint): nada de ponto flutuante com dinheiro.
-- O saldo de uma conta é a soma das partidas dela.

create table conta (
    id        bigint generated always as identity primary key,
    nome      text        not null check (length(nome) between 1 and 100),
    criada_em timestamptz not null default now()
);

create table lancamento (
    id        bigint generated always as identity primary key,
    descricao text        not null check (length(descricao) between 1 and 200),
    criado_em timestamptz not null default now(),
    -- Transação que criou o lançamento: só ela pode pôr partidas nele (regra 4)
    transacao xid8        not null default pg_current_xact_id()
);

create table partida (
    id            bigint generated always as identity primary key,
    lancamento_id bigint not null references lancamento (id),
    conta_id      bigint not null references conta (id),
    valor         bigint not null check (valor <> 0),
    -- Regra 2: a mesma conta não aparece duas vezes no mesmo lançamento
    unique (lancamento_id, conta_id)
);

-- Saldo e extrato de uma conta
create index partida_conta on partida (conta_id, id);

-- Ninguém escolhe o próprio id nem a própria data. Sem isto, um INSERT com OVERRIDING SYSTEM VALUE
-- poria uma partida "antes" das outras (mudando o histórico do extrato) ou ocuparia um id que a
-- sequência ainda vai gerar (travando os próximos lançamentos), e um lançamento poderia nascer
-- com data falsa ou com a transação de outro.
create function id_da_sequencia() returns trigger
language plpgsql as $$
begin
    -- Num INSERT normal o id acabou de sair da sequência, nesta sessão
    if new.id is distinct from currval(pg_get_serial_sequence(tg_table_name, 'id')) then
        raise exception '% não aceita id escolhido à mão', tg_table_name
            using errcode = 'restrict_violation';
    end if;
    return new;
exception
    -- currval sem nextval antes na sessão: o id veio de fora
    when object_not_in_prerequisite_state then
        raise exception '% não aceita id escolhido à mão', tg_table_name
            using errcode = 'restrict_violation';
end;
$$;

create trigger conta_id_da_sequencia
    before insert on conta
    for each row execute function id_da_sequencia();

create trigger lancamento_id_da_sequencia
    before insert on lancamento
    for each row execute function id_da_sequencia();

create trigger partida_id_da_sequencia
    before insert on partida
    for each row execute function id_da_sequencia();

create function lancamento_carimbado() returns trigger
language plpgsql as $$
begin
    new.criado_em := now();
    new.transacao := pg_current_xact_id();
    return new;
end;
$$;

create trigger lancamento_carimbado
    before insert on lancamento
    for each row execute function lancamento_carimbado();

-- Regras 1 e 2, conferidas no COMMIT (gatilho deferido): durante a transação a aplicação insere o
-- lançamento e depois as partidas, e no meio do caminho a soma ainda não fecha.
create function conferir_lancamento() returns trigger
language plpgsql as $$
declare
    alvo     bigint;
    partidas int;
    soma     numeric;
begin
    -- NEW é a linha de lancamento ou de partida, conforme a tabela do gatilho
    if tg_table_name = 'lancamento' then
        alvo := new.id;
    else
        alvo := new.lancamento_id;
    end if;

    select count(*), coalesce(sum(valor), 0) into partidas, soma
    from partida where lancamento_id = alvo;

    if partidas < 2 then
        raise exception 'lançamento % tem % partida(s); são precisas pelo menos duas', alvo, partidas
            using errcode = 'check_violation';
    end if;
    if soma <> 0 then
        raise exception 'lançamento % não fecha: as partidas somam % centavos', alvo, soma
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger lancamento_fecha
    after insert on lancamento
    deferrable initially deferred
    for each row execute function conferir_lancamento();

create constraint trigger partidas_fecham
    after insert on partida
    deferrable initially deferred
    for each row execute function conferir_lancamento();

-- Regra 4: sem ela, alguém poderia somar um par +X/-X a um lançamento antigo e a soma continuaria zero
create function partida_na_mesma_transacao() returns trigger
language plpgsql as $$
begin
    -- is distinct from: um lançamento que não aparece também é recusado (não depende só da FK)
    if (select transacao from lancamento where id = new.lancamento_id) is distinct from pg_current_xact_id() then
        raise exception 'lançamento % já foi fechado; não aceita partida nova', new.lancamento_id
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger partida_na_mesma_transacao
    before insert on partida
    for each row execute function partida_na_mesma_transacao();

-- Regra 3: o razão só cresce
create function razao_imutavel() returns trigger
language plpgsql as $$
begin
    raise exception 'o razão contábil não aceita % em %: corrija com um lançamento novo', tg_op, tg_table_name
        using errcode = 'restrict_violation';
end;
$$;

create trigger lancamento_imutavel
    before update or delete on lancamento
    for each row execute function razao_imutavel();

create trigger partida_imutavel
    before update or delete on partida
    for each row execute function razao_imutavel();

-- TRUNCATE não passa pelos gatilhos de linha; este fecha a porta
create trigger lancamento_sem_truncate
    before truncate on lancamento
    for each statement execute function razao_imutavel();

create trigger partida_sem_truncate
    before truncate on partida
    for each statement execute function razao_imutavel();
