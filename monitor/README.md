# Sentinel-GEM Monitor

React frontend for the Sentinel-GEM story overview.

## Local development

Start the engine, then run:

```powershell
npm install
npm run dev
```

Open `http://localhost:5173`. The Vite server forwards `/api` requests to
`http://localhost:8080` by default. Set `ENGINE_URL` to use another engine:

```powershell
$env:ENGINE_URL = "http://localhost:8081"
npm run dev
```

## Production build

```powershell
npm run build
npm run preview
```

The static build is written to `dist`. `npm run preview` uses the same
`ENGINE_URL` proxy. When serving `dist` elsewhere, configure that server to
forward `/api` to the engine.
