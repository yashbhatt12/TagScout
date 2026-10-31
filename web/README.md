# TagScout web dashboard

Read-only dashboard for the TagScout Firebase backend (project `tagscout-a0cc3`):
overview, stock, movements, stock counts and alerts. It never writes to Firestore.

## Setup (once)

1. Node 24.12 or later (`node -v`).
2. Firebase console → Project settings → General → Your apps → Add app → Web, name it "TagScout Web".
3. Copy `.env.example` to `.env.local` and fill it from that app's config.
4. `npm install`

## Run

- `npm run dev` — local dashboard at the URL it prints. Sign in with any company login.
- `npm run build` — type-check and build to `dist/`.
- `npm run deploy` — build, then `firebase deploy --only hosting --project tagscout-a0cc3`.

Never run a bare `firebase deploy`: `alarmNotifier` isn't in this repo and would be deleted.

## Where things are

- `src/lib/data.ts` — every Firestore read. Queries need no composite indexes.
- `src/lib/config.ts` — labels, feature switches and status names from `settings/config`.
- `src/lib/CompanyProvider.tsx` — company-wide data (locations, products, alerts) and label helpers.
- `src/pages/` — one file per screen.
