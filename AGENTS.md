# FuckPo0JiXian project handoff

Read `README.md`, `docs/CI.md` and `docs/TESTING.md` before CI work. Preserve
unrelated edits, signer/version/module contracts and application behavior.
Tests/builds do not authorize real Po0 whitelist operations.

Dedicated NAS Runner is `nas-ci-fuckpo0jixian`, selector
`self-hosted, Linux, X64, nas-fuckpo0jixian`. Other projects have separate
containers/registrations/home/cache; ordinary development does not initialize
another Runner. Trusted main/tag/manual Android builds use NAS; all PRs,
emulators and native macOS/Windows packaging remain on GitHub cloud.

Public approval is `all_external_contributors`; review workflow diffs before
approval. Never run untrusted fork code on NAS or bypass the gate with
`pull_request_target`. No host Docker socket/devices/production mounts, but
this remains a trusted-code Runner, not an untrusted-code sandbox.

Do not dump secrets or put signing/NAS keys in source. Use exact project-owned
targets and reversible recovery; no global prune/unknown deletion/other-service
changes. Native installers need the matching OS. Existing releases are drafts;
tags/publication/production changes need current user authority. Keep CI/docs
synchronized and retain NOT_RUN/UNKNOWN evidence boundaries.
