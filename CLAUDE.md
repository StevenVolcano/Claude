# CLAUDE.md

This file documents the repository for AI assistants (Claude Code and others) working in this codebase. Update it as the project evolves.

## Repository Overview

- **Repo**: `StevenVolcano/Claude` on GitHub
- **Purpose**: Travel Tracker — a PWA for tracking US states visited, mainline interstates traveled (per-state segments), state capitol buildings visited, and countries visited, all on interactive Leaflet maps. Local-first persistence (localStorage) with JSON export/import for backup (e.g. to Google Drive).
- **Stack**: Vite + React + TypeScript + Leaflet. Pure static site, no backend. Deployed to GitHub Pages via Actions on push to `main` (site base path is `/Claude/`).

## Repository Structure

```
/
├── CLAUDE.md
├── planning/ghostlight.md         # product plan for Ghostlight (separate project)
├── ghostlight/                    # Ghostlight: community-theater app (Vite/React PWA +
│                                  # PocketBase backend); self-contained, own package.json;
│                                  # intended to move to its own repo eventually
├── index.html / vite.config.ts / tsconfig.json / package.json
├── .github/workflows/deploy.yml   # GitHub Pages deploy on push to main
├── public/
│   ├── icons/                     # PWA icons
│   └── data/                      # prepared datasets (checked in, regenerable)
│       ├── us-states.json         # TopoJSON, 50 states, props {key: USPS abbr, name}
│       ├── world-countries.json   # TopoJSON, props {key: ISO numeric, name}
│       ├── interstates-by-state.json  # GeoJSON, one feature per (route, state)
│       ├── interstate-states.json # route -> [state abbrs] checklist
│       └── capitols.json          # 50 capitol buildings with coords
├── scripts/prepare-data/          # data pipeline (npm run prepare-data)
│   ├── base-layers.mjs            # states/countries/capitols from us-atlas, world-atlas, seed CSV
│   ├── interstates.mjs            # Natural Earth roads -> mainline per-state segments
│   └── fips.mjs                   # FIPS/abbr/name lookup tables
└── src/
    ├── App.tsx                    # tab shell: US Map | World | Lists | Data
    ├── map/                       # UsMap.tsx, WorldMap.tsx (Leaflet)
    ├── state/                     # data model, localStorage, export/import
    ├── components/                # Checklists.tsx, DataPanel.tsx
    └── data/load.ts               # static data fetching + TopoJSON conversion
```

## Domain Notes

- Only **mainline** interstates are tracked (1–2 digit routes plus Hawaii H-1..H-3); 3-digit belts/spurs and business loops are excluded in the pipeline. Suffixed branches (I-35E/W) fold into the parent route.
- Interstate geometry comes from Natural Earth roads (~2012 vintage). Post-2012 mainline routes (I-2, I-11, I-14, I-22, I-41, and extensions of I-49/I-69/I-87/I-99) are patched into the checklist in `scripts/prepare-data/interstates.mjs` but have no map geometry. Two Natural Earth mislabels (I-27 in KY, I-75 in OK) are corrected there too. Prefer regenerating from BTS/FHWA NHPN if that host is reachable.
- The travel data document is versioned (`schemaVersion: 1`) and validated on import in `src/state/storage.ts`.
- `npm run prepare-data` regenerates `public/data/`; the interstates script needs the Natural Earth shapefile downloaded to `scripts/prepare-data/raw/` first (URL in its header comment).

## Branch Conventions

- Feature work uses descriptive branch names: `feature/<short-description>` or `claude/<task-slug>`
- Never push directly to `main` without review
- Always push with `git push -u origin <branch-name>`

## Development Workflow

1. Check out the designated feature branch before starting work
2. Make focused, atomic commits with clear messages
3. Push to the feature branch when work is complete
4. Open a pull request only when explicitly requested

## Commit Message Style

- Imperative mood, present tense: "Add X", "Fix Y", "Remove Z"
- Keep the subject line under 72 characters
- Explain *why* in the body when the reason is non-obvious
- No trailing period on the subject line

Example:
```
Add user authentication middleware

JWT verification is required before any route that touches user data.
The token is expected in the Authorization header as a Bearer token.
```

## Code Conventions

_(Fill in as a tech stack is established. Example sections below.)_

### General
- Prefer clarity over cleverness
- No comments that just restate what the code does — only write comments for non-obvious *why*
- Delete dead code; don't comment it out

### Testing
- Tests live alongside source files or in a dedicated `tests/` directory
- Run the full test suite before pushing
- Do not skip failing tests to make CI green — fix the root cause

### Security
- Never commit secrets, tokens, or credentials
- Validate all input at system boundaries (user input, external APIs)
- Use parameterized queries for any database access

## AI Assistant Instructions

### What to do
- Read this file at the start of every session
- Update the "Repository Structure" section when adding new directories or major files
- Prefer editing existing files over creating new ones
- Match the code style of the surrounding file
- Keep changes minimal and scoped to what was requested

### What not to do
- Do not create documentation files unless explicitly asked
- Do not add error handling for impossible cases
- Do not refactor or clean up code beyond the stated task
- Do not push to `main` without explicit user approval
- Do not force-push without explicit user approval

## Environment Notes

- Remote: `http://local_proxy@127.0.0.1:37469/git/StevenVolcano/Claude` (proxied GitHub)
- GitHub MCP tools are available for PR/issue management (prefix: `mcp__github__`)
- Restricted to the `StevenVolcano/Claude` repository — do not access other repos
