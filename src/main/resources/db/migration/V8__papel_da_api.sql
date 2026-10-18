-- Usuário próprio para a API, sem poder de dono, e os gatilhos blindados contra tabela temporária.
--
-- As regras do razão moram em gatilhos, e o DONO das tabelas pode desligar gatilho (ALTER TABLE
-- ... DISABLE TRIGGER), apagar tabela ou mudar função. Em produção, então, as migrações rodam com
-- o dono (numa execução à parte, ver Migrar.kt) e a API entra com outro usuário, que só lê e grava
-- linhas: nada de TRUNCATE, ALTER, DROP ou CREATE. Mudar saldo e contador de chaves à mão continua
-- barrado pelos gatilhos, para ele também.
--
-- O nome vem do placeholder papel_api (FIEL_BANCO_PAPEL_API). Vazio, como no desenvolvimento, não
-- dá permissão a ninguém: a API usa o próprio dono. Em produção quem roda esta migração é sempre o
-- Migrar.kt, que exige o nome; ela roda uma vez só, então trocar o usuário depois pede migração nova.
-- O usuário é criado fora daqui (o deploy/preparar-vm.sh da publicação), porque senha não vai para o
-- histórico de migrações.

-- 1. Tabela temporária com o nome de uma tabela real. Qualquer usuário podia criar tabela temporária,
-- e na busca por nome o esquema pg_temp vem ANTES do public. Os gatilhos usam nomes sem esquema
-- ("from partida"): um "create temp table partida" com duas linhas que somam zero enganava o
-- conferir_lancamento, e um lançamento de uma partida só criava dinheiro do nada. Duas travas, cada
-- uma suficiente sozinha:
--   a) ninguém além do dono do banco cria tabela temporária;
--   b) toda função do esquema procura primeiro no public e só por último no pg_temp.
-- Funções de migrações futuras precisam do mesmo "set search_path" (o PapelDaApiTest confere todas).
do $$
declare
    funcao regprocedure;
begin
    execute format('revoke temporary on database %I from public', current_database());
    for funcao in
        select p.oid::regprocedure
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
    loop
        execute format('alter function %s set search_path = public, pg_temp', funcao);
    end loop;
end;
$$;

-- No PG15+ o esquema public já nasce sem CREATE para todos; fica explícito, para não depender disso
revoke create on schema public from public;

-- 2. As permissões do usuário da API
do $$
declare
    papel text := '${papel_api}';
begin
    if papel = '' then
        return;
    end if;
    execute format('grant usage on schema public to %I', papel);
    execute format('grant select, insert, update, delete on all tables in schema public to %I', papel);
    execute format('grant usage, select on all sequences in schema public to %I', papel);
    -- O histórico do Flyway a API só lê (o teste de partida confere as migrações aplicadas)
    execute format('revoke insert, update, delete on flyway_schema_history from %I', papel);
    -- Tabelas e sequências de migrações futuras, criadas por este mesmo dono, já nascem com as mesmas
    -- permissões (as migrações rodam sempre com ele)
    execute format('alter default privileges in schema public grant select, insert, update, delete on tables to %I', papel);
    execute format('alter default privileges in schema public grant usage, select on sequences to %I', papel);
end;
$$;
