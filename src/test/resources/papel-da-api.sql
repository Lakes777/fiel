-- Roda uma vez, quando o Postgres dos testes sobe: o usuário da API sem poder de dono (ver V8).
-- Em produção quem cria é o script de preparo da VM (publicação), com senha gerada lá.
create role fiel_api login password 'fiel_api';
