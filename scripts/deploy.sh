#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
npm ci
npx wrangler whoami
if ! npx wrangler r2 bucket list --json | node --input-type=module -e 'let s=""; for await (const c of process.stdin) s+=c; process.exit(JSON.parse(s).some(x=>x.name==="zap-content")?0:1)'; then
  npx wrangler r2 bucket create zap-content
fi
npx wrangler deploy
zap_setup_token=$(openssl rand -hex 32)
printf '%s' "$zap_setup_token" | npx wrangler secret put BOOTSTRAP_TOKEN
printf '\nIn Zap on Mac, open Settings and enter the Worker URL above.\nSetup token: %s\n' "$zap_setup_token"
