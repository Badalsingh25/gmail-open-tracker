#!/bin/sh
# Daily database backup, keeps 14 days. Run from cron (see DEPLOY_AZURE.md).
set -e
cd "$(dirname "$0")"
mkdir -p "$HOME/backups"
docker compose -f docker-compose.azure.yml exec -T mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction email_tracker' \
  | gzip > "$HOME/backups/tracker-$(date +%F).sql.gz"
find "$HOME/backups" -name 'tracker-*.sql.gz' -mtime +14 -delete
