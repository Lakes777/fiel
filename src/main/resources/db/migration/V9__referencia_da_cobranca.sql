-- A referência da cobrança: um código do sistema do lojista (o id do pedido na loja dele).
--
-- Serve para criar a cobrança sem duplicar: um sistema que cria a cobrança e cai antes de guardar a
-- resposta pede de novo com a mesma referência e recebe a mesma cobrança. Por isso ela é única por
-- carteira que recebe (dois lojistas podem usar o mesmo código), e opcional: quem cria pela tela não
-- precisa dela. Vai também no aviso (webhook), para o lojista achar o pedido dele sem guardar o id
-- do Fiel, que recomeça do 1 quando a demonstração é recriada.

alter table cobranca
    add column referencia text check (referencia ~ '^[A-Za-z0-9_-]{1,64}$');

create unique index cobranca_referencia on cobranca (recebedor_conta, referencia) where referencia is not null;

-- A referência entra no que foi cobrado e nunca muda. Mesma função de V7, com a linha da referência;
-- o "set search_path" de V8 vai junto, porque o CREATE OR REPLACE troca as configurações da função
create or replace function cobranca_so_anda_para_a_frente() returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
    if new.id <> old.id or new.codigo <> old.codigo or new.recebedor_conta <> old.recebedor_conta
       or new.valor <> old.valor or new.descricao <> old.descricao
       or new.criada_em <> old.criada_em or new.expira_em <> old.expira_em
       or new.referencia is distinct from old.referencia then
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
