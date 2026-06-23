# Startable backends — progress ledger

Plan: docs/superpowers/plans/2026-06-22-startable-backends.md
Branch: startable-backends (off main @ b0136e37 + marker-test commit)

- [x] Task 1: PidMarker.ideHome
- [x] Task 2: plugin writes ideHome
- [x] Task 3: devrig DiscoveredIde.ideHome
- [x] Task 4: installed-backend enumeration + startable filter
- [x] Task 5: DevrigBackendService.ensureBackendRunning
- [x] Task 6: open_project rewrite
- [x] Task 7: drop backends[] from list tools
- [x] Task 8: CLI backend 3 groups + deletions
- [x] Task 9: docs
- [x] Task 10: full verify + deploy + live check
Task 1: complete (commit d9f1afe8, review clean)
Task 2: complete (commit 281c5db7, review clean)
Task 3: complete (commit 8de29931, recovered from dangling, review clean)
Task 4: complete (commit 5336c992, review clean)
Task 5: complete (commits f7bc7613 + 5166a21c @Suppress-fix, review clean)
Task 6: complete (commit ac5c6ca6, review clean)
Task 7: complete (commit 014d167f, review clean)
Task 8: complete (commit d95fd90c, review clean — 0 failures; deleted BackendRow/BackendInventory/BackendListTest/identity-test per plan)
Task 9: complete (commit 9e718640, review clean)
Final review: C1/C2/I1/I2/M1/M3/M4 fixed (commits 45d6aad0,0e014fcb,dc092378; recovered from dangling via ff). HEAD=dc092378
Quorum 3x: all REQUEST CHANGES -> fixed (macOS ideHome, broaden catch, schema desc, jq) commits a2dadd96,0b75de32,b11d3d12. HEAD=b11d3d12. Tests green.
