#!/usr/bin/env bash
# 备份数据库与文档目录。用法：./deploy/backup.sh [备份目录]
set -euo pipefail

cd "$(dirname "$0")/.."
target="${1:-./backups}"
stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$target"

echo "==> pg_dump"
docker compose exec -T postgres pg_dump -U recordandgrow -d recordandgrow \
  | gzip > "$target/recordandgrow-$stamp.sql.gz"

echo "==> documents (from appdata volume)"
rm -rf "$target/documents-$stamp"
docker compose cp app:/app/data/documents "$target/documents-$stamp"
tar -czf "$target/documents-$stamp.tar.gz" -C "$target" "documents-$stamp"
rm -rf "$target/documents-$stamp"

echo "done: $target"
ls -lh "$target" | tail -5
