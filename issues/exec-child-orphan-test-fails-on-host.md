# exec_child_runs_guest_when_orphaned_to_init fails on the host (pre-existing)

Found 2026-09-30 while landing an unrelated tawcroot change. It is
**not** caused by that change — verified by stashing the change and
re-running the test in isolation, where it fails identically.

```
$ ./tawcroot/test.sh --host exec_child_runs_guest_when_orphaned_to_init
exec_child_runs_guest_when_orphaned_to_init exec_child orphan marker missing; diag tags="gs"
- - - - - - - - - - - - - - - - - oh no :(

tawcroot/tests/integration/test_exec_child.c:374: found is false
0 test passed, 1 test failed
```

The full host suite is otherwise green: 2309 passed, this 1 failed.

## What the test does

`tawcroot/tests/integration/test_exec_child.c`, around line 374. It
exercises the `--exec-child <fd>` re-exec path: a guest child is
deliberately orphaned so it is reparented away from its parent, and the
test then asserts the orphaned guest still runs and writes a marker the
parent can find. `diag tags="gs"` is the harness's tag set for the run,
not a clue.

The assertion that fires is `found is false` — the marker the orphaned
guest should have written was not seen.

## Why it matters beyond the test

`--exec-child` is the re-exec path tawcroot uses when a guest is
adopted after a seccomp filter is already installed (see
notes/tawcroot/sigsys-handler.md, "Why non-PIE"). If orphaning an
exec-child loses the guest, that is a real robustness gap, not just a
red test. It may also be a harness artifact — the marker lookup could be
racing the orphan's scheduling, or depend on something the hosted runner
provides differently from a device. Neither has been established yet.

## Next step

Run it on a device (`./tawcroot/test.sh --device`) to split the two:
if it passes there, the hosted runner is at fault and the fix belongs in
the harness; if it fails there too, the exec-child orphan path is
genuinely broken.
