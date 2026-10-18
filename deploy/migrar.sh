#!/usr/bin/env bash
# Na VM: aplica as migrações com o DONO das tabelas (Migrar.kt), num contêiner que existe só durante
# isto. É o único lugar que lê o .env.dono: a API nunca recebe a senha do dono.
# Uso: migrar.sh [imagem]   (padrão: fiel:latest; o publicar.sh passa a do commit novo)
set -euo pipefail
docker run --rm --network beacon_interna --env-file ~/fiel/.env.dono --memory 384m \
	--entrypoint java "${1:-fiel:latest}" -cp fiel-0.0.1-SNAPSHOT.jar io.github.lakes777.fiel.MigrarKt
