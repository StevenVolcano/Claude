# CLAUDE.md

This file documents the repository for AI assistants (Claude Code and others) working in this codebase. Update it as the project evolves.

## Repository Overview

- **Repo**: `StevenVolcano/Claude` on GitHub
- **Purpose**: _(describe what this project does)_
- **Status**: New — no source files yet. This CLAUDE.md is the first commit.

## Repository Structure

```
/                   # root — to be populated
CLAUDE.md           # this file
```

Update this section as directories and files are added.

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
