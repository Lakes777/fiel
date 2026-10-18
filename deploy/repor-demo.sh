#!/usr/bin/env bash
# Na VM, todo dia às 4h (cron do preparar-vm.sh): recria a demonstração do zero. O razão não deixa
# apagar nada (de propósito), então "limpar" é apagar o banco inteiro e criar de novo: migrações, e a
# API semeia a Loja Exemplo e a Ana ao partir. Quem estiver usando às 4h perde a conta descartável.
set -euo pipefail
cd ~/fiel
echo "$(date '+%F %T') recriando a demonstração"
docker compose stop api
# Falhou no meio: avisa no Telegram (o mesmo bot da conciliação). O token vai para o curl pela
# entrada padrão, e não como argumento: não aparece na lista de processos
avisar() {
	local token chat
	token=$(grep '^FIEL_TELEGRAM_TOKEN=' ~/fiel/.env | cut -d= -f2-)
	chat=$(grep '^FIEL_TELEGRAM_CHAT=' ~/fiel/.env | cut -d= -f2-)
	[ -n "$token" ] && [ -n "$chat" ] || return 0
	printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$token" \
		| curl -s -o /dev/null -K - --data-urlencode "chat_id=$chat" \
			--data-urlencode "text=Fiel: a recriação da demonstração das 4h falhou. Veja ~/fiel/repor-demo.log na VM." || true
}
# Se algo falhar daqui em diante, avisa, e a API volta mesmo assim (sem tabelas ela não sobe; o
# aviso é o que faz alguém olhar)
trap 'status=$?; [ "$status" = 0 ] || avisar; docker compose start api' EXIT
(cd ~/beacon && docker compose exec -T banco psql -U beacon -d beacon -v ON_ERROR_STOP=1 -q) <<'SQL'
drop database if exists fiel_demo with (force);
create database fiel_demo owner fiel_dono;
revoke connect on database fiel_demo from public;
grant connect on database fiel_demo to fiel_api;
SQL
~/fiel/migrar.sh
echo "$(date '+%F %T') pronto"
