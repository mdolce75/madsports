#!/usr/bin/env bash
# Pone en el servidor lo último de main: baja los cambios, reconstruye la app y la reinicia (la base y los informes
# quedan en sus volúmenes; las migraciones corren solas al arrancar).
set -euo pipefail

cd "$(dirname "$0")/.."
git pull --ff-only
cd deploy
docker compose -f docker-compose.prod.yml up -d --build
docker image prune -f > /dev/null
docker compose -f docker-compose.prod.yml ps
