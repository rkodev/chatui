# UX-11 - Performance and baseline profile

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
- Order: 12
- Depends on: UX-03, UX-09
- Parallel-safe: yes, with UX-12
- Owns: performance measurement, baseline profile, `android/baselineprofile/`, performance notes
- Read first: [android-ux.md](../../android-ux.md) section 14; [implementation plan](../android-ux-implementation-plan.md) section 3.8; `android/baselineprofile/`, `StartupBaselineProfile.kt`, M3/M4 performance evidence

## Goal

Confirm the redesigned UI meets the performance budgets and regenerate the
baseline profile for the new shell and chat. Record measurements, hardware, and
methodology. Do not change product behavior to hit a number.

## Preconditions

- UX-03 and UX-09 are `Complete` so measurements reflect the final chat and
  motion.
- A device or emulator is available for baseline-profile generation and
  macrobenchmark runs.
- Do not change streaming, storage, or transport behavior.

## Deliverables

- Cold-start measurement with method and hardware. Target: under 1.5 s on a
  mid-tier device.
- Streaming check: no history recomposition and no dropped frames during token
  streaming.
- List scrolling check: 60 fps on a 10,000-message conversation and on the
  conversation/model lists.
- Image decode sizing and Coil cache verification.
- Regenerated baseline profile committed under `android/baselineprofile/`.
- A performance note in this file with a table: metric, target, measured,
  hardware, method, pass/fail, deviation/follow-up owner.

## Implementation steps

1. Read the existing baseline profile and M3/M4 performance evidence.
2. Measure cold start with the project's method (baseline profile/macrobenchmark
   or `am start -W`). Record the device and method.
3. Profile streaming with the layout inspector or macrobenchmark. Confirm the
   history does not recompose and the streaming row is isolated.
4. Profile a 10,000-message conversation scroll and the conversation/model
   lists. Record frame drops.
5. Verify image decode sizing and Coil cache keys in `ChatMessageItem.kt`.
6. Regenerate the baseline profile:
   ```bash
   cd android && ./gradlew :app:generateBaselineProfile
   ```
7. Commit the regenerated profile and verify the release build uses it.
8. Fill in the performance note. For every miss, record the cause and a
   follow-up owner.

## Acceptance criteria

- [ ] Cold start is measured and under 1.5 s, or a deviation is recorded.
- [ ] Streaming does not recompose history or drop frames.
- [ ] A 10,000-message conversation scrolls at 60 fps, or a deviation is
      recorded.
- [ ] Conversation and model lists scroll at 60 fps.
- [ ] Image decode sizing and Coil cache are verified.
- [ ] The baseline profile is regenerated and committed.
- [ ] The performance note records hardware, method, and deviations.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:generateBaselineProfile
cd android && ./gradlew :app:assembleRelease
```

Plus the recorded cold-start and scroll measurements.

## Out of scope

- Visual redesign.
- Accessibility audit (UX-10).
- Golden regeneration (UX-12).
- Changing budgets; record a deviation instead.

## Handoff notes

- Record the device, OS, and method for every measurement.
- List any deviation with a cause and follow-up owner.
- Note the baseline-profile generation command and result.

## Performance note

| Metric | Target | Measured | Hardware | Method | Pass/Fail | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| Cold start | < 1.5 s | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |
| Streaming jank | none | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |
| 10k message scroll | 60 fps | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |
| Conversation list scroll | 60 fps | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |
| Model list scroll | 60 fps | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |
