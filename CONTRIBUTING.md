# Contributing

Contributions are welcome through pull requests.

1. Keep the SDK receive-only; sending and console management do not belong in this package.
2. Add tests for behavior changes and run the committed wrapper over every module:
   ```sh
   ./gradlew testDebugUnitTest assembleRelease
   ```
3. Run `./gradlew spotlessApply` before committing.
4. Do not commit credentials, provider configuration, local paths, build output, or customer data.
5. Run `scripts/check-public-content.sh`; it fails on names, paths and other content that must not
   be published.
6. Use Conventional Commits for commit subjects.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security
issues privately as described in [SECURITY.md](SECURITY.md).

First-time external workflow runs may require maintainer approval.
