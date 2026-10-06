# Releasing

Releases are published to Maven Central by the [Release workflow](.github/workflows/release.yml)
when a `vX.Y.Z` tag is pushed. The workflow:

1. checks that the tag matches the version in `pom.xml` and is not a SNAPSHOT;
2. checks that the Maven Central token is valid;
3. builds, runs all tests, signs and publishes with `mvn -P release deploy`;
4. creates the GitHub release from the matching `CHANGELOG.md` section.

A release on Maven Central cannot be changed or removed. A mistake means a new patch release.

## Cutting a release

1. Pick the version per [Semantic Versioning](https://semver.org/): fixes → patch, new
   backward-compatible API → minor, breaking changes → major.
2. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`, add a new empty
   `## [Unreleased]` above it and update the compare links at the bottom.
3. Set `<version>X.Y.Z</version>` in `pom.xml` and update the version in the `README.md` install
   snippets.
4. Commit to `main` and wait for CI to pass.
5. Tag and push:

   ```bash
   git tag -a vX.Y.Z -m "daktela-v6-java-connector vX.Y.Z"
   git push origin vX.Y.Z
   ```

6. Watch the Release workflow. Central usually serves the new version within 30 minutes; the
   search page on central.sonatype.com can take a few hours to catch up.

If the workflow fails before the publish step, fix the problem, delete the tag
(`git push --delete origin vX.Y.Z && git tag -d vX.Y.Z`) and tag again. If it fails after
publishing, do not re-tag: check the deployment in the Central Portal under **Publish →
Deployments**.

## Dry run

Run the Release workflow manually from the Actions tab (**Run workflow** on `main`). It checks the
token, runs the tests and signs the artifacts, but uploads nothing. Do this after rotating any
secret.

## Secrets

The workflow reads these secrets from the `maven-central` GitHub environment, which only `main`
and `v*` tags can use:

| Secret | Value |
|--------|-------|
| `CENTRAL_USERNAME` | Central Portal user token username (central.sonatype.com → View Account → Generate User Token) |
| `CENTRAL_PASSWORD` | Central Portal user token password |
| `GPG_PRIVATE_KEY` | ASCII-armored release signing key: `gpg --armor --export-secret-keys C8A69575` |
| `GPG_PASSPHRASE` | Passphrase of that key (leave unset if it has none) |

The signing key `C8A69575` (Daktela &lt;daktela@daktela.com&gt;) expires on 2028-01-22. Extend
it with `gpg --quick-set-expire C8A69575 2y`, republish the public key to a keyserver, and update
`GPG_PRIVATE_KEY`.

## Manual release (fallback)

Without GitHub Actions, build a bundle with `mvn -P release verify` (with the key available to
gpg), zip `com/daktela/daktela-v6-java-connector/X.Y.Z/` with the jars, pom, `.asc` signatures
and `.md5`/`.sha1` checksums, and upload it in the Central Portal under **Publish → Publish
Component**.
