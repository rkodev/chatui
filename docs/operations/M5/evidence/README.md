# M5-07.4 evidence

Synthetic data only. No real passphrases, provider credentials, prompts, or
conversation content are recorded here.

- [gate-results.md](gate-results.md) - full gate commands and results.
- [cross-client-restore.log](cross-client-restore.log) - web and Android
  restore in both directions against local MinIO.
- [conflict-safe-failure-tests.txt](conflict-safe-failure-tests.txt) - conflict,
  tombstone, wrong-passphrase, tamper, and interrupted-upload results on web,
  Android, and Rust.
- [bucket-listing.txt](bucket-listing.txt) - object names and sizes.
- [storage-inspection.txt](storage-inspection.txt) - envelope magic, ciphertext
  prefix, plaintext/secret scan, and manifest HEAD metadata.
- [manifest-head-object.json](manifest-head-object.json) - raw HEAD response.
- [scheduling.txt](scheduling.txt) - Android WorkManager constraints, web/Rust
  documented behavior, and the malicious-provider risk statement.

The cross-client runs used temporary harnesses that exercised the shipped
envelope, manifest, conversation-payload codecs, sync engines, and S3 adapters.
The harnesses were removed after the run so the normal web and Android test
suites do not require MinIO; their results are recorded in
[cross-client-restore.log](cross-client-restore.log).
