# OVN Fork Audit Log

Running log of audits performed on the custom OVN integration in this CloudStack
fork. Each audit file lists every bug surfaced, its severity, the commit that
fixed it (or a `WONTFIX` / `DEFERRED` marker), and the production verification
proving the fix landed.

## Purpose

Without this log, every fresh audit dispatch keeps re-flagging bugs that were
already fixed in an earlier session. The conductor (or a subagent) must read
the relevant audit file BEFORE running an open-ended "find all the bugs" pass
and scope its analysis to either:

- gaps the prior audit explicitly marked as `OPEN`/`DEFERRED`, OR
- surface area that did not exist at the time of the prior audit
  (newer commits, newer modules).

## Conventions

- One file per audit pass: `YYYY-MM-DD-<topic>.md`.
- Status states: `FIXED`, `OPEN`, `DEFERRED`, `WONTFIX`, `OBSOLETE`.
- Every `FIXED` entry MUST cite the fix commit hash + production verification
  evidence (build md5, smoke-test output, API field returned, etc.).
- Audits are append-only. Never edit historical files; create a new file when
  re-auditing the same surface.
- en-US for all content (project policy).

## Index

| Date | File | Scope | Bugs found | Bugs fixed | Bugs open |
|---|---|---|---|---|---|
| 2026-05-09 | `2026-05-09-ovn-fork-audit.md` | OVN fork DB + API + UI inconsistencies | 8 | 8 | 0 |
