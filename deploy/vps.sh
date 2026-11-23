#!/usr/bin/env bash
set -euo pipefail

sha=${1:?Pass the tested commit SHA}
[[ $sha =~ ^[0-9a-f]{40}$ ]] || exit 1

deploy_dir="$HOME/sentinel-gem-vps"
mkdir -p "$deploy_dir"
cd "$deploy_dir"
exec 9>.deploy.lock
flock -x 9

git init -q
git fetch --no-tags https://github.com/Lnscc/sentinel-GEM.git refs/heads/deploy
if [[ $(git rev-parse FETCH_HEAD) != "$sha" ]]; then
  echo "Branch deploy has changed since CI started. Skipping $sha." >&2
  exit 1
fi
git checkout --detach "$sha"

if [[ ! -f .env ]]; then
  (
    umask 077
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" > .env
  )
fi

export DEPLOY_SHA="$sha"
compose=(docker compose -f compose.yaml -f compose.vps.yaml)
"${compose[@]}" up -d --no-build --wait --wait-timeout 150

curl --fail --silent --show-error --max-time 5 --retry 45 --retry-delay 2 \
  --retry-all-errors --retry-max-time 180 --output /dev/null \
  'http://127.0.0.1:4000/api/articles?limit=1'
curl --fail --silent --show-error --max-time 5 --output /dev/null 'http://127.0.0.1:4000/'
curl --fail --silent --show-error --max-time 5 --output /dev/null \
  'http://127.0.0.1:3000/api/articles?limit=1'
"${compose[@]}" ps

# Keep recent release images for rollback. Docker preserves images in use.
docker image prune --all --force --filter label=io.sentinel-gem.vps=true --filter until=168h ||
  echo 'Could not remove old Sentinel GEM release images.' >&2
df -h /
