#!/usr/bin/env bash
# Respaldo diario: la base (pg_dump comprimido) y los informes guardados, en /var/backups/madsports; se conservan los
# de los últimos 14 días. Para correrlo todos los días a las 3:30 (crontab -e):
#   30 3 * * * /opt/madsports/deploy/respaldo.sh >> /var/log/madsports-respaldo.log 2>&1
set -euo pipefail

DEST=/var/backups/madsports
DAY=$(date +%F)
cd "$(dirname "$0")"
mkdir -p "$DEST"

docker compose -f docker-compose.prod.yml exec -T db pg_dump -U madsports madsports | gzip > "$DEST/base-$DAY.sql.gz"
docker run --rm -v madsports_informes:/informes:ro -v "$DEST":/destino alpine \
  tar czf "/destino/informes-$DAY.tgz" -C /informes .

find "$DEST" -type f -mtime +14 -delete
echo "$(date '+%F %T') respaldo listo: $(du -sh "$DEST" | cut -f1) en $DEST"
