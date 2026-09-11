#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
npm ci
npx wrangler whoami
if ! npx wrangler r2 bucket info zap-content --json > /dev/null; then
  npx wrangler r2 bucket create zap-content --update-config=false
fi
npx wrangler deploy
zap_setup_token=$(openssl rand -hex 32)
printf '%s' "$zap_setup_token" | npx wrangler secret put BOOTSTRAP_TOKEN
printf '\nIn Zap on Mac, open Settings and enter the Worker URL above.\nSetup token: %s\n' "$zap_setup_token"
