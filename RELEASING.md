# Releasing

## Publish to Maven Central

The artifacts are published under `ai.openpush` by the **Publish to Maven Central** workflow. It
runs only when started by hand, for a tag that is already on `main`.

1. In GitHub, open **Actions → Publish to Maven Central → Run workflow**. Enter the tag (for example
   `1.2.2`). Leave **include_compat** off unless `openpush-android-onesignal` should be published too.
2. Approve the run when it waits on the `maven-central` environment.
3. The workflow runs the unit tests, builds and signs the tag, uploads the bundle to the Central
   Portal and waits until it is validated. If validation fails, the run fails and prints the errors.
4. Open [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments)
   and press **Publish** on the validated deployment. About 30 minutes later the artifacts are on
   Maven Central.

Deployments are uploaded as `USER_MANAGED`, so nothing is public until step 4. Once releases are
routine, the upload can switch to `AUTOMATIC` to publish as soon as validation passes.
