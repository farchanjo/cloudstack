# Git hooks (this repo)

Configured with:

```bash
git config core.hooksPath .githooks
```

## `pre-push`

- Only `main` or `master` may be pushed to any remote.
- Any other local branch must be **merged into** main/master first (or deleted).
- Tags are allowed.

This is enforced locally; remotes are not reconfigured by this hook.
