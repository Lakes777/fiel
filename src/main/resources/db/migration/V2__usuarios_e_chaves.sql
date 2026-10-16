-- Usuários, donos das contas e chaves de transferência.
--
-- Regras que o PRÓPRIO BANCO garante (como as do razão, em V1):
--   1. uma conta é "carteira" (tem dono), "emissao" (a única de onde nasce o dinheiro de mentira)
--      ou "interna" (do sistema); só a carteira tem dono, e cada usuário tem no máximo uma;
--   2. dono e tipo de uma conta nunca mudam: trocar o dono seria mover o dinheiro sem lançamento;
--   3. o e-mail de um usuário nunca muda (a chave do tipo e-mail depende dele);
--   4. chave só em carteira, no máximo 5 por conta, e a chave do tipo e-mail é o e-mail do dono
--      (ninguém cadastra o e-mail de outra pessoa para receber no lugar dela). O limite é um
--      contador na própria conta, não um count(*): duas transações que inserem ao mesmo tempo
--      atualizam a mesma linha, então uma espera a outra (ou leva erro de serialização, em
--      REPEATABLE READ, onde um count(*) enxergaria a "foto" antiga e deixaria as duas passarem);
--   5. chave não muda: pode ser apagada e criada de novo.

create table usuario (
    id        bigint generated always as identity primary key,
    nome      text        not null check (length(nome) between 1 and 100),
    -- Sempre minúsculo: "Ana@x.com" e "ana@x.com" são a mesma pessoa
    email     text        not null check (length(email) between 3 and 254 and email = lower(email) and email like '%_@_%'),
    -- Hash da senha (bcrypt, com o prefixo {bcrypt}); a senha mesmo nunca chega ao banco
    senha     text        not null,
    criado_em timestamptz not null default now(),
    unique (email)
);

create trigger usuario_id_da_sequencia
    before insert on usuario
    for each row execute function id_da_sequencia();

alter table conta
    add column tipo text not null default 'interna' check (tipo in ('carteira', 'emissao', 'interna')),
    add column dono_id bigint references usuario (id),
    -- Quantas chaves a conta tem; só os gatilhos da tabela chave mexem nele
    add column chaves int not null default 0 constraint conta_no_maximo_5_chaves check (chaves between 0 and 5),
    add constraint so_carteira_tem_dono check ((tipo = 'carteira') = (dono_id is not null));

create unique index uma_carteira_por_usuario on conta (dono_id) where tipo = 'carteira';
create unique index uma_conta_de_emissao on conta (tipo) where tipo = 'emissao';

-- De onde sai o bônus de quem se cadastra. O saldo dela é sempre negativo: menos todo o dinheiro
-- em circulação, o que fecha a conta do sistema inteiro em zero.
insert into conta (nome, tipo) values ('Emissão', 'emissao');

create function conta_dono_imutavel() returns trigger
language plpgsql as $$
begin
    if new.dono_id is distinct from old.dono_id or new.tipo is distinct from old.tipo or new.id <> old.id then
        raise exception 'o dono e o tipo de uma conta não mudam'
            using errcode = 'restrict_violation';
    end if;
    -- pg_trigger_depth() = 1: um UPDATE escrito à mão; os gatilhos de chave rodam um nível abaixo
    if new.chaves is distinct from old.chaves and pg_trigger_depth() < 2 then
        raise exception 'o número de chaves de uma conta só muda criando ou apagando chaves'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger conta_dono_imutavel
    before update on conta
    for each row execute function conta_dono_imutavel();

create function usuario_email_imutavel() returns trigger
language plpgsql as $$
begin
    if new.email is distinct from old.email or new.id <> old.id then
        raise exception 'o e-mail de um usuário não muda'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger usuario_email_imutavel
    before update on usuario
    for each row execute function usuario_email_imutavel();

create table chave (
    id        bigint generated always as identity primary key,
    conta_id  bigint      not null references conta (id),
    tipo      text        not null check (tipo in ('email', 'aleatoria')),
    valor     text        not null,
    criada_em timestamptz not null default now(),
    -- Aleatória: um UUID versão 4 em minúsculas, como o que a API gera
    check (
        (tipo = 'email' and valor = lower(valor) and valor like '%_@_%')
        or (tipo = 'aleatoria' and valor ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
    ),
    -- Uma chave leva a uma conta só
    unique (valor)
);

create index chave_conta on chave (conta_id);

create trigger chave_id_da_sequencia
    before insert on chave
    for each row execute function id_da_sequencia();

create function conferir_chave() returns trigger
language plpgsql as $$
declare
    tipo_da_conta  text;
    email_do_dono  text;
begin
    select c.tipo, u.email into tipo_da_conta, email_do_dono
    from conta c left join usuario u on u.id = c.dono_id
    where c.id = new.conta_id;

    if tipo_da_conta is distinct from 'carteira' then
        raise exception 'chave só pode levar a uma carteira' using errcode = 'check_violation';
    end if;
    if new.tipo = 'email' and new.valor is distinct from email_do_dono then
        raise exception 'a chave do tipo e-mail precisa ser o e-mail do dono da conta' using errcode = 'check_violation';
    end if;
    -- A 6ª esbarra em conta_no_maximo_5_chaves. O UPDATE trava a linha da conta: outra chave
    -- criada ao mesmo tempo espera esta transação terminar e soma em cima do valor novo
    update conta set chaves = chaves + 1 where id = new.conta_id;
    new.criada_em := now();
    return new;
end;
$$;

create trigger conferir_chave
    before insert on chave
    for each row execute function conferir_chave();

create function chave_imutavel() returns trigger
language plpgsql as $$
begin
    raise exception 'chave não muda: apague e crie outra' using errcode = 'restrict_violation';
end;
$$;

create trigger chave_imutavel
    before update on chave
    for each row execute function chave_imutavel();

create function chave_apagada() returns trigger
language plpgsql as $$
begin
    update conta set chaves = chaves - 1 where id = old.conta_id;
    return old;
end;
$$;

create trigger chave_apagada
    after delete on chave
    for each row execute function chave_apagada();

-- TRUNCATE não dispara os gatilhos por linha: deixaria o contador das contas errado
create trigger chave_sem_truncate
    before truncate on chave
    for each statement execute function chave_imutavel();
