#!/usr/bin/env python3
"""Lojista exemplo: recebe os avisos do Fiel e confere cada um antes de confiar nele.

Só a biblioteca padrão do Python. Para rodar:

    FIEL_SEGREDO=<o segredo que o PUT /webhook devolveu> python3 exemplos/lojista.py

e cadastre http://localhost:8099/avisos como webhook (a API precisa de
fiel.avisos.permitir-rede-local=true para aceitar http e localhost).

O que um lojista de verdade precisa fazer, e este faz:
  1. conferir a assinatura (HMAC-SHA256 de "<t>.<corpo>" com o segredo), em tempo constante;
  2. recusar aviso velho (t a mais de 5 minutos da hora daqui): alguém pode ter capturado e
     estar reenviando;
  3. descartar repetidos pelo "id" do corpo (o mesmo do Fiel-Evento-Id, mas assinado): a entrega
     é "pelo menos uma vez", então o mesmo aviso pode chegar de novo, e o pedido não pode ser
     marcado como pago duas vezes;
  4. responder 2xx rápido. Qualquer outra coisa faz o Fiel tentar de novo mais tarde.
"""

import hashlib
import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SEGREDO = os.environ["FIEL_SEGREDO"].encode()
TOLERANCIA_SEGUNDOS = 300
# Num sistema de verdade, uma tabela com chave única no banco, não a memória
ja_processados: set[str] = set()


def assinatura_confere(cabecalho: str, corpo: bytes) -> bool:
    campos = dict(parte.split("=", 1) for parte in cabecalho.split(",") if "=" in parte)
    t, v1 = campos.get("t", ""), campos.get("v1", "")
    if not t.isdigit() or abs(time.time() - int(t)) > TOLERANCIA_SEGUNDOS:
        return False
    esperado = hmac.new(SEGREDO, t.encode() + b"." + corpo, hashlib.sha256).hexdigest()
    return hmac.compare_digest(esperado, v1)


class Avisos(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        corpo = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if not assinatura_confere(self.headers.get("Fiel-Assinatura", ""), corpo):
            print("recusado: assinatura não confere ou aviso velho", flush=True)
            self.send_response(401)
            self.end_headers()
            return
        # O id que vale é o de DENTRO do corpo, que a assinatura cobre. O cabeçalho Fiel-Evento-Id
        # não é assinado: quem capturasse um aviso poderia reenviá-lo com outro id e passar como novo
        aviso = json.loads(corpo)
        if aviso["id"] in ja_processados:
            print(f"repetido, ignorado: {aviso['id']}", flush=True)
        else:
            ja_processados.add(aviso["id"])
            print(f"{aviso['tipo']}: {json.dumps(aviso['dados'], ensure_ascii=False)}", flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args) -> None:  # sem a linha de log padrão a cada pedido
        pass


if __name__ == "__main__":
    print("lojista exemplo ouvindo em http://localhost:8099/avisos", flush=True)
    HTTPServer(("127.0.0.1", 8099), Avisos).serve_forever()
