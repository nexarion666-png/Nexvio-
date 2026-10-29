# Nexvio Web Deployment

Vercel project settings for this app:

- Git repository: `nexarion666-png/Nexvio-`
- Production branch: `main`
- Root Directory: `web`
- Install Command: `npm install`
- Build Command: `npm run build`
- Output Directory: `dist`

The built-in Vidlink provider uses the server-side Vercel function at `web/api/vidlink.js` through `/api/vidlink`. Keep the `api` directory inside the configured `web` root so Vercel can deploy the function.

Deploy from the latest commit on the `main` branch so the live site includes the current web app changes. If a new commit does not appear in Vercel, check the project's Git connection and confirm that production deployments track `main`.
