#!/usr/bin/env bash
# Publica uma versão nova na VM: monta as duas imagens aqui (para ARM), manda pela conexão SSH,
# aplica as migrações com o dono, reinicia e confere a saúde. A VM não precisa do código, do JDK nem
# do Node, só do Docker.
# Uso: deploy/publicar.sh   (a primeira vez: deploy/preparar-vm.sh antes)
set -euo pipefail

VM="${VM:-ubuntu@159.112.189.95}"
CHAVE_SSH="${CHAVE_SSH:-$HOME/.ssh/oracle_bot}"
DOMINIO="${DOMINIO:-fiel.andrelagos.dev}"
cd "$(dirname "$0")/.."
SSH=(ssh -i "$CHAVE_SSH" -o BatchMode=yes)

# Só publica o que está no git: a imagem leva o commit, e dá para saber o que está no ar
if [ -n "$(git status --porcelain)" ]; then
	echo "Há mudanças sem commit. Faça o commit antes de publicar." >&2
	exit 1
fi
commit=$(git rev-parse --short HEAD)

if ! "${SSH[@]}" "$VM" "test -f fiel/.env && test -f fiel/.env.dono"; then
	echo "Faltam os .env na VM. Rode deploy/preparar-vm.sh antes." >&2
	exit 1
fi

echo "Montando as imagens ARM ($commit)..."
for alvo in api web; do
	nome=$([ "$alvo" = api ] && echo fiel || echo fiel-web)
	docker buildx build -q --load --platform linux/arm64 --target "$alvo" --build-arg COMMIT="$commit" \
		-t "$nome:$commit" .
done

# Na VM, as imagens chegam só com a tag do commit: o "latest" (o que o compose e o cron das 4h usam)
# só muda depois que as migrações passarem. Se falharem, nada do que está no ar muda.
echo "Enviando para a VM (comprimidas)..."
docker save "fiel:$commit" "fiel-web:$commit" | gzip | "${SSH[@]}" "$VM" "gunzip | docker load -q"
scp -q -i "$CHAVE_SSH" deploy/compose.yaml deploy/migrar.sh deploy/repor-demo.sh "$VM:fiel/"
"${SSH[@]}" "$VM" "chmod +x fiel/migrar.sh fiel/repor-demo.sh"

# A versão no ar (a anterior) segue rodando por alguns segundos com o esquema novo: migrações
# precisam ser compatíveis com a versão de antes (acrescentar, não renomear nem apagar)
echo "Aplicando as migrações (com o dono)..."
if ! saida=$("${SSH[@]}" "$VM" "fiel/migrar.sh fiel:$commit" 2>&1); then
	echo "$saida" | grep -vE "^\s*at " | tail -15 >&2
	echo "As migrações falharam: a versão no ar continua a de antes." >&2
	exit 1
fi
echo "$saida" | grep -E "^Migrações" || true

echo "Reiniciando..."
sed "s/DOMINIO_FIEL/$DOMINIO/g" deploy/fiel.caddy | "${SSH[@]}" "$VM" "cat > beacon/sites/fiel.caddy"
# Tira as tags de commits antigos (senão as imagens velhas ficam ocupando o disco) e o prune
# apaga o que sobrou sem tag
"${SSH[@]}" "$VM" "docker tag fiel:$commit fiel:latest && docker tag fiel-web:$commit fiel-web:latest \
	&& cd fiel && docker compose up -d --remove-orphans \
	&& for n in fiel fiel-web; do docker images \$n --format '{{.Tag}}' | grep -vxE 'latest|$commit' | xargs -r -I{} docker rmi -f \$n:{} >/dev/null; done \
	&& docker image prune -f >/dev/null"
# O Caddy é do Beacon e serve os outros sites também. Com o fiel.caddy errado, o reload recusa e
# segue com a configuração antiga; o arquivo sai da pasta, senão derrubaria todos no próximo reinício
if ! "${SSH[@]}" "$VM" "cd beacon && docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile"; then
	"${SSH[@]}" "$VM" "rm -f beacon/sites/fiel.caddy"
	echo "O Caddy recusou o fiel.caddy: tirei o arquivo, os outros sites seguem como estavam." >&2
	exit 1
fi

echo "Conferindo a saúde..."
for _ in $(seq 1 40); do
	estado=$("${SSH[@]}" "$VM" "docker inspect -f '{{.State.Health.Status}}' fiel-api-1" 2>/dev/null || true)
	if [ "$estado" = healthy ]; then
		if curl -fsS "https://$DOMINIO/api/saude" > /dev/null; then
			echo "No ar: https://$DOMINIO ($commit)"
			exit 0
		fi
	fi
	sleep 3
done
echo "O Fiel não respondeu. Veja: ssh ... 'cd fiel && docker compose logs api --tail 50'" >&2
exit 1
