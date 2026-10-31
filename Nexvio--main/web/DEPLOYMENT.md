# Nexvio Web Deployment

## Cloudflare Pages (alternative hosting)

The web app can be deployed to Cloudflare Pages from this repository without relying on Vercel's deployment quota.

In Cloudflare Dashboard, open Workers & Pages, choose Create application, then Pages, then Import an existing Git repository. Connect `nexarion666-png/Nexvio-` and set:

- Production branch: `main`
- Root directory: `web`
- Build command: `npm run build`
- Build output directory: `dist`

Add these environment variables in the Pages project settings before or after the first build:

- `VITE_TMDB_API_KEY`
- `VITE_SUPABASE_URL`
- `VITE_SUPABASE_PUBLISHABLE_KEY`

The built-in Vidlink provider uses the Cloudflare Pages Function at `web/functions/api/vidlink.js`, exposed as `/api/vidlink`. The existing `web/api/vidlink.js` is retained for Vercel compatibility. Pages should publish the `dist` folder and deploy the `functions` directory alongside it.

Cloudflare Pages supports automatic deployments from the connected Git repository on pushes to `main`. After the first deployment, test movie search, sign-in, watchlist syncing, and Vidlink stream search on the `pages.dev` URL. Confirm any required Supabase authentication redirect URLs include the new Cloudflare Pages domain.

## Vercel

- Root Directory: `web`
- Install Command: `npm install`
- Build Command: `npm run build`
- Output Directory: `dist`
- Production branch: `main`

The built-in Vidlink provider uses the server-side Vercel function at `web/api/vidlink.js` through `/api/vidlink`.
