# Publishing

The library is published to **Maven Central**, the only registry with anonymous
consumption: whoever uses it needs no token and no repository declaration.

- [Setup](#setup) — first time only
- [The release flow](#the-release-flow)
- [What each workflow does](#what-each-workflow-does)
- [Consuming the library](#consuming-the-library)
- [Troubleshooting](#troubleshooting)

---

## Setup

Four manual steps, once. Until they are done, the `Release` workflow warns and
publishes nothing.

### 1. Verify the `io.github.sebas2409` namespace

1. Sign in to <https://central.sonatype.com> with the GitHub account.
2. *Publish → Namespaces → Add Namespace* → `io.github.sebas2409`.
3. The portal gives a code such as `abc123xyz`. Create an empty public repository
   with that exact name in your account and press *Verify Namespace*.
4. Once it reads **verified**, delete that repository: it is no longer needed.

The `io.github.<user>` namespace is verified this way, without owning a domain.
That is why the `groupId` is `io.github.sebas2409` and not `com.policyrules`.

### 2. Generate the portal token

Under *Account → Generate User Token*. It returns a username and a password that
are not your account's: those are the credentials Maven uses.

### 3. Create the GPG key

Central requires every artifact to be signed.

```bash
# Create the key (pick RSA 4096 and a passphrase you can store)
gpg --full-generate-key

# Note the long key id
gpg --list-secret-keys --keyid-format=long

# Publish it, or Central cannot verify the signature
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID

# Export it for the workflow (the whole block, BEGIN/END lines included)
gpg --armor --export-secret-keys YOUR_KEY_ID
```

Store the private key and the passphrase in a password manager: losing them means
repeating the process.

### 4. Register the four secrets in GitHub

Under *Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Content |
|--------|---------|
| `CENTRAL_USERNAME` | username of the portal token (step 2) |
| `CENTRAL_TOKEN` | password of that token |
| `GPG_PRIVATE_KEY` | the full output of `gpg --armor --export-secret-keys` |
| `GPG_PASSPHRASE` | the key's passphrase |

---

## The release flow

**The version in `pom.xml` drives it.** Tags are never created by hand: the tag
is derived from the version.

```bash
# 1. Bump the version in pom.xml (e.g. 1.0.1 -> 1.1.0)
#    <version>1.1.0</version>

git commit -am "Version 1.1.0"
git push origin main
```

From there the `Release` workflow does the rest on its own:

```
pom.xml (1.1.0)
   ├─ compiles, runs the tests and builds sources + Javadoc
   ├─ signs the five artifacts with GPG
   ├─ publishes io.github.sebas2409:policy-rules:1.1.0 to Maven Central
   ├─ creates the v1.1.0 tag
   └─ opens the GitHub release with the three jars attached
```

The gate rules, so that nothing is published twice or by accident:

| Situation | Result |
|-----------|--------|
| one of the four secrets is missing | warns and publishes nothing |
| version `1.1.0-SNAPSHOT` | does nothing (snapshots are not published) |
| version `1.1.0` with tag `v1.1.0` already present | does nothing |
| version `1.1.0` without a tag | publishes |

When run by hand (*Actions → Release → Run workflow*) and any of that fails, the
workflow **fails** instead of ending silently: if you triggered it, you expected a
publication.

The tag is created **after** Central accepts the artifact, so a tag always
corresponds to a version that really exists in the registry. With `autoPublish`
there is nothing to click in the portal.

A version published to Central is **immutable**: it cannot be deleted or
overwritten. To fix something, publish a new version.

The first time, the artifact takes a few minutes to appear on
[central.sonatype.com](https://central.sonatype.com/artifact/io.github.sebas2409/policy-rules)
and up to a few hours in the search index.

---

## What each workflow does

### `.github/workflows/ci.yml`

Runs on every push to `main` and on every pull request. It runs
`mvn -Prelease verify`: compiles, runs the tests and **builds the Javadoc with
strict `doclint`**. That way incomplete documentation breaks the pull request
rather than the publication. It signs nothing and needs no secrets.

### `.github/workflows/release.yml`

Runs when `pom.xml` changes on `main`, or on demand. It needs `contents: write`
to create the tag and the release, plus the four secrets above. It publishes with
`mvn -Prelease,central deploy`.

### The `pom.xml` profiles

| Profile | Adds | Used by |
|---------|------|---------|
| `release` | sources and Javadoc jars | CI, the release, and you locally |
| `central` | GPG signing and `central-publishing-maven-plugin` | the release only |

They are deliberately separate, so `mvn -Prelease verify` keeps working locally
without a GPG key configured.

---

## Consuming the library

No token, no extra repositories:

```xml
<dependency>
    <groupId>io.github.sebas2409</groupId>
    <artifactId>policy-rules</artifactId>
    <version>1.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.sebas2409:policy-rules:1.1.0")
```

The consuming project needs **Java 25**.

---

## Troubleshooting

**`401 Unauthorized` when publishing.** The credentials are the portal's *user
token*, not your account's username and password. Regenerate them under
*Account → Generate User Token*.

**`403` or "namespace not verified".** The `io.github.sebas2409` namespace has not
finished verifying, or the pom's `groupId` does not match it exactly.

**"No public key", or signature validation fails.** The GPG key is not published
on a keyserver. Run `gpg --send-keys` again; propagation takes a few minutes.

**`gpg: signing failed: Inappropriate ioctl for device`.** The
`--pinentry-mode loopback` the pom already configures is missing, or the
passphrase is not reaching `MAVEN_GPG_PASSPHRASE`.

**`UnrecognizedPropertyException` after a successful upload.** The Central Portal
API added a field that an older `central-publishing-maven-plugin` cannot parse.
The artifact is published but the build fails afterwards; bump the plugin version.

**The workflow does not trigger.** It only listens to changes in `pom.xml` on
`main`. If you changed the version on another branch, merge it into `main` or run
the workflow by hand.

**The build fails on Javadoc.** That is intentional: the `release` profile uses
strict `doclint`. Complete the documentation of the element the error points at.

**Publishing from a local machine.** Possible, although the workflow is the
intended path. You need the `central` server in your `settings.xml` and the GPG
key in your keyring:

```bash
mvn -B -Prelease,central deploy
```
