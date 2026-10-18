# P14 amendment (RC.2): last-good configuration backup

Owner decision (RC.2 authorization, Policy A): `config/cpextended.json.bak` holds a **previously committed, valid** configuration across successful saves, including the normal atomic-save path. This supersedes the fallback-only `.bak` of RC.1. Written before implementation; the implementation follows it exactly (`ConfigStorage`, `ConfigManager`).

## Files
| File | Contents |
|---|---|
| `cpextended.json` | The active configuration (unchanged role). |
| `cpextended.json.bak` | The active file's **previous** content, promoted byte for byte when a save replaces it (unknown keys, untouched invalid filters and formatting kept). Never written from memory, never normalized. |
| `cpextended.json.corrupt-<time>` | Unchanged: byte copy of an unparseable active file found at startup. |
| `cpextended.json.<random>.tmp`, `cpextended.json.bak.<random>.tmp` | Staging files; never read; deleted at startup. |

## Promotion rule
A save that replaces active content **A** with new content **N** promotes A to `.bak` only if all hold:
1. **A is reconciled:** the save's content check found the file identical (SHA-256 and length) to the baseline Extended last loaded, adopted or wrote, and the manager is in the NORMAL state. A file that is corrupt, of an unsupported newer schema, unreadable, or externally modified without reconciliation (divergence lock, no-save state) is never promoted. **Overwrite file**, **Replace file** and **Restore from backup** therefore never touch `.bak`.
2. **A is valid:** `ConfigParser` reads it with status OK (supported schema). Preserved invalid filters or unknown keys don't make it invalid.
3. **It's a new generation:** A differs from N (a rewrite of identical bytes would otherwise discard the older backup), and A differs from the current `.bak`.

Otherwise `.bak` is left exactly as it is. A missing active file (first start) has nothing to promote.

## Ordering: atomic replacement (ordinary saves and Q36 commits)
| Step | Action | If it fails or the process dies here |
|---|---|---|
| 1 Prepare | Write N to `cpextended.json.<r>.tmp`, fsync. | Temp deleted (or left by a crash and deleted at startup). Both files unchanged. Save fails/retries as before. |
| 2 Validate candidate | Read A; its fingerprint must equal the baseline (else the existing external-edit handling, nothing written). Decide promotion (rule above). | Nothing written. |
| 3 Stage backup | If promoting: write A to `cpextended.json.bak.<r>.tmp`, fsync. | **Promotion skipped** (staging file deleted, warning logged); the save continues. `.bak` keeps its previous generation, which is still a valid, older configuration. |
| 4 Commit active | P14 content check immediately before `ATOMIC_MOVE` of the N temp over `cpextended.json`. | Both temps deleted; **both files unchanged**. Q36: the reference swap is rolled back as before (the commit's file step failed). |
| 5 Finalize backup | `ATOMIC_MOVE` of the staged A over `cpextended.json.bak`. Runs **after** the active commit, and for Q36 **after** `OrphanRestore.commit` has returned committed, outside its rollback scope. | Staging file deleted, warning logged. The save **stays successful**: active is N, `.bak` is the previous (older, valid) generation. Never rolls anything back. |

Why active first, backup second: a failed or refused commit (step 4) must leave the previously committed configuration and all in-memory message state intact (Q36). With the backup finalized only after success, a failed commit leaves **both** files byte-identical to before, so no backup generation is lost and nothing needs undoing. The price is that a crash or failure between steps 4 and 5 leaves `.bak` one generation older; it's still valid.

Step 3 sits before the content check of step 4 and doesn't lengthen the documented check-to-rename window (V-62). The staged A equals the file on disk at step 4 because both carry the same fingerprint as the baseline.

## Ordering: backup-protected non-atomic fallback (ordinary saves only)
Used only when `ATOMIC_MOVE` is unsupported (Q36 fallback (i)). Orphan-producing operations are still refused there (unchanged).

| Step | Action | If it fails or the process dies here |
|---|---|---|
| 1 | Content check (A must equal the baseline). | Nothing written. |
| 2 | If N equals A: nothing to write; reported as saved. | — |
| 3 | If A is promotable: write A directly over `.bak`, fsync. **The backup protects the direct write, so a failure aborts the save** (one retry, then "not saved"; changes stay in memory). | Active unchanged (A). `.bak` may be torn by a crash; restore validates it and rejects a torn file. |
| 4 | Write N directly over `cpextended.json`, fsync. | A crash leaves the active file torn: at the next start it's unparseable, so NO_SAVE, a `.corrupt-<time>` copy, running read-only on `.bak` (= A, valid), and Restore from backup is offered. |

If A isn't promotable (only possible for the explicit no-save replacements), `.bak` keeps its older valid generation and the write proceeds as the player explicitly confirmed.

## Crash and failure generations (atomic path)
Starting from active A and backup B₀:

| Interrupted | Active | `.bak` |
|---|---|---|
| before step 4 | A | B₀ |
| between 4 and 5 | N | B₀ |
| after 5 | N | A |
| step 4 fails / Q36 rollback | A | B₀ |
| step 3 or 5 fails | N | B₀ |

`.bak` is never N, never corrupt, and never an unreconciled external edit. **The two files are not one atomic transaction:** each replacement is atomic on its own, and the table lists every combination this ordering can produce.

**Durability:** Java can't portably fsync the directory, so after a power loss a completed rename may itself be lost. Each file then holds a complete earlier generation; the two renames may persist independently. Not claimed: recovery from every crash, file-system failure, or external edit.

## Recovery
- **Startup with an unparseable active file:** unchanged. NO_SAVE, original left in place plus the `.corrupt` copy, read-only on a valid `.bak` (otherwise defaults).
- **Restore from backup** (NO_SAVE only, explicit confirmation): `.bak` is read and validated again. Missing, unreadable, unparseable and unsupported-schema backups are each refused, with the reason shown on the button and in the result. Orphan rules apply: an orphan-producing restore needs the Q22 confirmation and runs the Q36 transaction, and capacity-projection failures refuse it. The file-content check still guards against unrelated external edits (they're handled as a possible repair, never overwritten). The restored bytes are written as the active file; `.bak` is unchanged.
- No message history is persisted; `.bak` is configuration only.

## Unchanged
The NORMAL / NO_SAVE / DIVERGENCE states, content fingerprints, external-edit adoption, raw-JSON preservation, the Q36 prepare/commit/rollback, P5/P6/Q27/C-1, the schema, and the check-to-rename race (V-62).
