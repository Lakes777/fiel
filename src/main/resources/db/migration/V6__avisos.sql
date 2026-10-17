-- Avisos ao lojista (webhooks): o Fiel avisa o sistema do lojista quando algo acontece.
--
-- Outbox: o aviso é gravado na MESMA transação do fato (pagamento, transferência, expiração).
-- Se o fato foi gravado, o aviso também foi; se a transação desfez, nenhum dos dois existe.
-- Quem entrega lê esta tabela depois, fora da transação do fato, e tenta de novo até conseguir.
--
-- Regras que o PRÓPRIO BANCO garante:
--   1. o que o aviso diz (tipo, dados, para quem, quando) nunca muda: reenviar manda o mesmo aviso;
--   2. pendente sempre tem hora da próxima tentativa; entregue sempre tem hora da entrega;
--   3. aviso não é apagado (é o histórico de entregas que o lojista consulta).

-- Para onde mandar os avisos de cada usuário (um endereço por usuário).
create table webhook (
    usuario_id bigint      primary key references usuario (id),
    url        text        not null check (length(url) between 10 and 500 and url ~ '^https?://'),
    -- 32 bytes aleatórios em hexadecimal. Fica em claro porque o Fiel precisa dele para assinar
    -- (HMAC); um hash não serviria, ao contrário de uma senha
    segredo    text        not null check (segredo ~ '^[0-9a-f]{64}$'),
    criado_em  timestamptz not null default now()
);

create table aviso (
    id              bigint generated always as identity primary key,
    -- O Fiel-Evento-Id: com ele o lojista descarta um aviso que chegou duas vezes
    codigo          uuid        not null unique,
    usuario_id      bigint      not null references usuario (id),
    tipo            text        not null check (tipo in ('cobranca.paga', 'cobranca.expirada', 'transferencia.recebida')),
    dados           jsonb       not null,
    -- Do relógio da API, como o resto: as esperas entre tentativas contam por ele
    criado_em       timestamptz not null,
    estado          text        not null default 'pendente' check (estado in ('pendente', 'entregue', 'falhou')),
    tentativas      int         not null default 0 check (tentativas >= 0),
    proxima_em      timestamptz,
    ultima_resposta text check (length(ultima_resposta) <= 300),
    entregue_em     timestamptz,
    check ((estado = 'pendente') = (proxima_em is not null)),
    check ((estado = 'entregue') = (entregue_em is not null))
);

-- O que a tarefa de entrega procura
create index aviso_a_entregar on aviso (proxima_em) where estado = 'pendente';
create index aviso_do_usuario on aviso (usuario_id, id);

create trigger aviso_id_da_sequencia
    before insert on aviso
    for each row execute function id_da_sequencia();

create function aviso_imutavel() returns trigger
language plpgsql as $$
begin
    if tg_op in ('DELETE', 'TRUNCATE') then
        raise exception 'aviso não é apagado: é o histórico de entregas' using errcode = 'restrict_violation';
    end if;
    if new.id <> old.id or new.codigo <> old.codigo or new.usuario_id <> old.usuario_id or new.tipo <> old.tipo
       or new.dados <> old.dados or new.criado_em <> old.criado_em then
        raise exception 'o que um aviso diz não muda' using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger aviso_imutavel
    before update or delete on aviso
    for each row execute function aviso_imutavel();

create trigger aviso_sem_truncate
    before truncate on aviso
    for each statement execute function aviso_imutavel();
