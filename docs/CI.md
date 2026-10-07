# FuckPo0JiXian dedicated NAS CI

Public repository `yiyezq162/FuckPo0JiXian` owns Runner/container/Compose
project `nas-ci-fuckpo0jixian`, selector
`self-hosted, Linux, X64, nas-fuckpo0jixian`. Runtime
`/volume1/services/ci-fuckpo0jixian/compose.yaml`, independent home/work/cache
volume `nas-ci-fuckpo0jixian-home`. Normal development does not register another
Runner or use another project's home/credentials.

| Job | Execution |
| --- | --- |
| Trusted main/tag/manual Android/core/lint/APK/module build | Dedicated NAS Linux Runner; `CI_RUNNER=github` gives cloud fallback |
| Every PR Android build | Disposable GitHub Ubuntu, never NAS |
| Android emulator UI tests | GitHub Ubuntu/KVM, not NAS host/VM |
| Native desktop installers | GitHub macOS/Windows respectively; Linux cannot replace native packaging |
| Tag draft Release assembly | Existing GitHub cloud job; original draft/prerelease/version/signer gates retained |

Workflow installs JDK17/pinned Android setup action/SDK36/build-tools36.0.0;
NAS image is not a preinstalled-SDK promise. Gradle uses at most2workers.
HTTP(S)/apt uses private Mihomo → BitsFlow DE, not global NAS proxy/DNS/routing.
Runner-scoped `JAVA_TOOL_OPTIONS` supplies explicit HTTP/HTTPS JVM proxy
properties for Gradle/SDK tools (environment HTTP_PROXY alone is insufficient);
the workflow verifies the actual Java DE exit before SDK/build work.
CPU6/RAM6GiB+swap2GiB are ceilings, not reserved capacity; one job per Runner.
Registrations and mutable cache/work are independent; only immutable image
layers and the proxy service are shared. No cross-project scheduling hook.

Initial NAS run37565439815/dc69437 passed the Java DE exit gate, but SDK setup
failed because the pinned setup action defaults to retired package `tools`.
The NAS action now explicitly requests platform-tools/SDK36/build-tools36.0.0;
its own versioned sdkmanager handles installation, not a presumed `latest`
directory. Cloud setup retains its original command. No product/signature/test
gate was changed; full NAS Android acceptance is pending the corrected run.

## Public fork safety

Owner-approved approval policy is `all_external_contributors`, not only
first-time contributors. Review **workflow diffs** before approval: a fork can
change `runs-on`; a routing expression alone is not a sandbox. Do not blindly
approve a workflow using self-hosted labels or use `pull_request_target` to
execute untrusted checkout on NAS. This nonprivileged container has no host
Docker socket/device/production mounts, but sudo/private-network access still
make it a trusted-code Runner, not an untrusted-code sandbox.

```sh
gh api repos/yiyezq162/FuckPo0JiXian/actions/runners \
  --jq '.runners[] | {name,status,busy,labels:[.labels[].name]}'
gh run list --repo yiyezq162/FuckPo0JiXian --workflow build.yml --limit 5
```

Authorized source push starts CI without re-initializing the Runner. Preview
signing secret remains in GitHub Secrets, never Compose/source/logs. Existing
tag workflow creates a **draft**, not an automatically public Release. This
CI change does not authorize tags/publication or real Po0 whitelist operations.
Tests retain TESTING.md credential-free/isolated rules.

VPSManage/vault `20-服务/nas-ci-runners.md` own NAS maintenance/recovery; use
pinned alias/current authority, require busy=false before exact-Runner stop/
recreation. No other Runner/service/proxy or retired NAS whitelist updater is
changed. Never copy keys/tokens/private credential files into this repository.
