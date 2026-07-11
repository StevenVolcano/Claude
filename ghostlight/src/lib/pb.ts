import PocketBase from 'pocketbase'

// Same-origin in production (PocketBase serves the built frontend from
// pb_public/); the Vite dev server proxies /api to :8090 in development.
export const pb = new PocketBase('/')

pb.autoCancellation(false)
