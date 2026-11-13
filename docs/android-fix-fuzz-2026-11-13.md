# Android fix-and-fuzz, 2026-11-13

## Scope and isolation

Baseline: `5e4c17a959adde3d2011a2a594629c1831ba7318` on `claude/project-thread-0aoiql`.
Work is isolated on `jarvis/mibot-device-fuzz-20261113`. No push, merge, deployment,
production install, production data reset, real bot communication or permission grant.

Physical device: OnePlus CPH2655, Android 16. The separate `app.mibot.qa` package
(MiBot QA) uses synthetic data through an in-process HTTP interceptor. It does not
load a saved production account. Pairing/sign-in/sign-out cannot escape the QA
fixture. QA-requested release builds are disabled. Normal release builds have
`QA_MODE=false` and exclude the fixture source set.

Build fixture APK with:

```sh
./android/gradlew -p android :app:assembleDebug -PmibotQa=true
```

Install only `app.mibot.qa`, not the normal release APK. The fixture intentionally
rejects sends with HTTP 503. Do not exercise external web links, voice/camera
permissions or real pairing as part of this fixture test.

## Defects fixed

- Composer discarded unsent text and reply target before hub acceptance. Now
  preserves them on failure/cancellation, clears only after success, and blocks
  duplicate submissions/edit/dictation while sending.
- Stale roster refresh erased newer live status and newly pushed bots.
- Stale approval refresh expired newly arrived requests or overwrote newer details.
- Late/duplicate clear events could erase a newer-generation transcript.
- Overlapping roster/approval refreshes are serialized.
- Notification permission check incorrectly required Android 13's permission on
  older Android. Posting now explicitly checks permission and handles revocation.
- Approval cards read StateFlow.value during composition, missing reactive updates.

## Evidence

- Physical phone red/green: type `QA_DRAFT_MUST_SURVIVE`, reply to the fixture bot
  message, press Send. Baseline loses both draft and reply on HTTP 503; fixed APK
  retains both and displays the error. XML hierarchy and screenshots captured.
- Bounded phone sweep passed 16 read-only destination visits (routines/library/computer/apps)
  and 4 background/resume cycles; `scripts/qa/device-smoke.py` records the scope.
- 80 core tests: 79 passed, 0 failed, 1 live-hub integration test intentionally skipped.
  Includes existing decoder/client/session/state/text/URL/voice fuzz suites and
  new refresh-race regressions plus 2,000 seeded stale/duplicate clear interleavings.
- 3 app submission unit tests passed (failure, duplicate tap/success, cancellation).
- Debug lint passed: 0 errors; 11 warnings and 1 hint remain (not a clean warning gate).
- Normal release APK assembled successfully, QA_MODE false, no QaHub dex class.
- `-PmibotQa=true :app:assembleRelease` correctly rejected (variant absent).

Commands:

```sh
./android/gradlew -p android --offline :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PmibotQa=true
./android/gradlew -p android --offline :app:assembleRelease
# Expected failure; ensures QA cannot build an APK with the production package:
./android/gradlew -p android --offline :app:assembleRelease -PmibotQa=true
```

## Limitations

This is not a production end-to-end approval. Real hub pairing, successful live
agent execution, microphone/voice/camera, notification delivery, background
service longevity and external web app screens were deliberately not exercised.
Android <33 notification compatibility is statically checked, not device-tested.
The initial local practice hub could not start due to a host permission restriction;
in-app fixtures were used instead. An early QA-only APK had missing classes due
to concurrent builds sharing core output; serial clean rebuild resolved it. That
packaging incident was not counted as a production app defect.
