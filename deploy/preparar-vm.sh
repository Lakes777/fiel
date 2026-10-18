#!/usr/bin/env bash
# Prepara a VM uma vez só (rodar de novo não estraga nada): os dois usuários do Fiel no Postgres do
# Beacon (fiel_dono, dono das tabelas, e fiel_api, sem poder de dono: ver V8), o banco fiel_demo, os
# dois .env com as senhas (geradas NA VM, ficam só lá), o Telegram da conciliação (o mesmo bot do
# Vigil, copiado de uma VM para a outra sem passar pela tela) e o cron que recria a demo às 4h.
# Uso: deploy/preparar-vm.sh
set -euo pipefail

VM="${VM:-ubuntu@159.112.189.95}"
VM_DO_VIGIL="${VM_DO_VIGIL:-ubuntu@147.15.40.173}"
CHAVE_SSH="${CHAVE_SSH:-$HOME/.ssh/oracle_bot}"
SSH=(ssh -i "$CHAVE_SSH" -o BatchMode=yes)

"${SSH[@]}" "$VM" bash -s <<'REMOTO'
set -euo pipefail
mkdir -p ~/fiel
if [ -f ~/fiel/.env ] && [ -f ~/fiel/.env.dono ]; then
	echo "Os .env já existem: usuários, banco e .env ficam como estão."
else
	dono=$(openssl rand -hex 24)
	api=$(openssl rand -hex 24)
	# A rede do Caddy (ex.: 172.30.0.0/24) vira a regex dos proxies confiáveis da API
	rede=$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' beacon_interna)
	case "$rede" in
		*/24) confiaveis="$(printf '%s' "${rede%.*/24}" | sed 's/\./\\./g')\\.\\d{1,3}" ;;
		*) echo "A rede do Caddy ($rede) não é /24: ajuste FIEL_PROXIES_CONFIAVEIS à mão." >&2; exit 1 ;;
	esac
	cd ~/beacon
	# Pela entrada padrão (e não como argumento): as senhas não aparecem na lista de processos.
	# Repetível: cria só o que falta e (re)define as senhas, que vão para os .env logo abaixo. Se
	# algo falhar no meio, rodar de novo conserta. O log_min_error_statement impede que um comando
	# que dê erro vá para o log do Postgres com a senha dentro.
	docker compose exec -T banco psql -U beacon -d beacon -v ON_ERROR_STOP=1 -q <<SQL
set log_min_error_statement = panic;
select 'create role fiel_dono login' where not exists (select from pg_roles where rolname = 'fiel_dono') \gexec
select 'create role fiel_api login' where not exists (select from pg_roles where rolname = 'fiel_api') \gexec
alter role fiel_dono password '$dono';
alter role fiel_api password '$api';
select 'create database fiel_demo owner fiel_dono' where not exists (select from pg_database where datname = 'fiel_demo') \gexec
revoke connect on database fiel_demo from public;
grant connect on database fiel_demo to fiel_api;
SQL
	umask 077
	cat > ~/fiel/.env <<ENV
FIEL_BANCO_URL=jdbc:postgresql://banco:5432/fiel_demo
FIEL_BANCO_USUARIO=fiel_api
FIEL_BANCO_SENHA=$api
FIEL_MIGRAR_NA_PARTIDA=false
FIEL_DEMO=true
FIEL_CABECALHOS_DO_PROXY=native
FIEL_PROXIES_CONFIAVEIS=$confiaveis
ENV
	# Só o migrar.sh lê este: a API nunca recebe a senha do dono
	cat > ~/fiel/.env.dono <<ENV
FIEL_BANCO_URL=jdbc:postgresql://banco:5432/fiel_demo
FIEL_BANCO_DONO=fiel_dono
FIEL_BANCO_DONO_SENHA=$dono
FIEL_BANCO_PAPEL_API=fiel_api
ENV
	echo "Usuários fiel_dono e fiel_api, banco fiel_demo e os .env prontos (proxies: $confiaveis)."
fi

# A demo recriada todo dia às 4h (a VM está no horário de Brasília), depois da conciliação das 3h
if ! crontab -l 2>/dev/null | grep -q 'fiel/repor-demo.sh'; then
	(crontab -l 2>/dev/null; echo '0 4 * * * ~/fiel/repor-demo.sh >> ~/fiel/repor-demo.log 2>&1') | crontab -
	echo "Cron das 4h instalado."
fi
REMOTO

# O Telegram da conciliação: o mesmo bot e chat do Vigil. Vai de uma VM para a outra por este PC,
# só pela memória do pipe (nunca na tela nem em arquivo daqui)
quantas=$("${SSH[@]}" "$VM" "grep -cE '^FIEL_TELEGRAM_(TOKEN|CHAT)=.' ~/fiel/.env" || true)
if [ "$quantas" = 2 ]; then
	echo "O Telegram já está no .env."
else
	# Pela metade (ou nada): tira o que houver e copia de novo
	"${SSH[@]}" "$VM" "sed -i '/^FIEL_TELEGRAM_/d' ~/fiel/.env"
	"${SSH[@]}" "$VM_DO_VIGIL" "grep -E '^TELEGRAM_(TOKEN|CHAT_ID)=' ~/vigil/.env" \
		| sed 's/^TELEGRAM_TOKEN=/FIEL_TELEGRAM_TOKEN=/; s/^TELEGRAM_CHAT_ID=/FIEL_TELEGRAM_CHAT=/' \
		| "${SSH[@]}" "$VM" "umask 077; cat >> ~/fiel/.env"
	quantas=$("${SSH[@]}" "$VM" "grep -cE '^FIEL_TELEGRAM_(TOKEN|CHAT)=.' ~/fiel/.env" || true)
	[ "$quantas" = 2 ] && echo "Telegram copiado do Vigil." || { echo "O Telegram não veio inteiro ($quantas de 2)." >&2; exit 1; }
fi
