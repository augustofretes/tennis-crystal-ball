#!/usr/bin/env bash
# Run inside the existing postgres:14 maintenance service with /backups mounted.
set -euo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sql_file="$script_dir/2026-11-02-arnaldi.sql"
backup=/backups/pre-identity-repair-20261102.dump
test ! -e "$backup"
export PGOPTIONS="${PGOPTIONS:-} -c default_transaction_read_only=off -c search_path=tcb,public -c statement_timeout=120000"
pg_dump -Fc --schema=tcb --file="$backup"
pg_restore --list "$backup" >/dev/null
sha256sum "$backup"
printf 'IDENTITY_BACKUP_COMPLETE\n'
preview_file="$(mktemp)"
trap 'rm -f "$preview_file"' EXIT
sed 's/^COMMIT;$/ROLLBACK;/' "$sql_file" > "$preview_file"
psql -X -v ON_ERROR_STOP=1 -f "$preview_file"
printf 'IDENTITY_REPAIR_ROLLBACK_TEST_PASSED\n'
psql -X -v ON_ERROR_STOP=1 -f "$sql_file"
printf 'IDENTITY_REPAIR_COMMITTED\n'
psql -X -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'IDENTITY_REPAIR_COUNTS '::text||jsonb_build_object(
  'arnaldi_matches',(SELECT count(*) FROM match WHERE winner_id=54581 OR loser_id=54581),
  'thursfield_matches',(SELECT count(*) FROM match WHERE winner_id=52898 OR loser_id=52898),
  'arnaldi_rankings',(SELECT count(*) FROM player_ranking WHERE player_id=54581),
  'thursfield_rankings',(SELECT count(*) FROM player_ranking WHERE player_id=52898),
  'backup_rows',(SELECT count(*) FROM identity_repair_backup WHERE repair_id='arnaldi-20261102'));
SQL
