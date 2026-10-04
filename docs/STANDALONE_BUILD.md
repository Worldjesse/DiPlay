# Building a usable (standalone) car package locally

CI publishes **source-only** APKs. Those install and run, but they cannot complete CarPlay pairing
with an iPhone, because they carry no accessory identity. This is deliberate: the identity is an
Apple-issued MFi credential and must not be committed.

A usable package is built on your own machine, from the same source, with the identity supplied
through an environment variable. Nothing about that step touches CI.

## Why CI cannot do this for you

CarPlay pairing checks an Apple-signed accessory identity before it will talk to a receiver. The
two files are:

| File | Size | What it is |
| --- | --- | --- |
| `offline-mfi/identity.pk8` | 67 bytes | ECDSA-P256 private key (PKCS#8) |
| `offline-mfi/certificate.p7b` | 607 bytes | PKCS#7 certificate chain issued by Apple Inc. |

These are credentials. Anything that can read a CI log, a build cache or an artifact can
effectively hand them out, and an MFi identity cannot be rotated cheaply once exposed. Building
locally keeps them on one machine that you control. Upstream DiPlay does the same thing: its own
release APKs are built outside CI, which is why its workflows only ever produce source-only output.

## 1. Prerequisites

You need the same toolchain CI uses:

- **JDK 25** (CI pins temurin 25)
- **Android SDK platform 37**, build-tools 36.0.0
- **NDK 28.2.13676358** (the two JNI modules are compiled by ndk-build)
- The Gradle wrapper ships with the repository; do not use a system Gradle

Check your machine:

```sh
java -version          # must report 25
echo "$ANDROID_HOME"   # must point at an SDK with platform 37 and build-tools 36.0.0
ls "$ANDROID_HOME/ndk/28.2.13676358"
```

On Windows, `JAVA_HOME` and `ANDROID_HOME` must be exported in the shell that runs Gradle; setting
them in the system environment is not always picked up by Git Bash.

## 2. Obtain the identity

Place the two files in a directory **outside the repository**:

```
<somewhere-private>/diplay-auth/
  offline-mfi/
    identity.pk8
    certificate.p7b
```

Keep this directory out of version control. If you use a sync folder or a cloud drive, exclude it.

The repository enforces this: `scripts/check_public_tree.py` runs in every CI job and rejects
unexpected credential files, and the Gradle build independently refuses to package anything but
those two exact filenames when `DIPLAY_AUTH_ASSETS_DIR` is set.

## 3. Build

```sh
cd /path/to/DiPlay
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/diplay-auth ./gradlew :mobile:assembleStandaloneDebug
```

`assembleStandaloneDebug` fails fast when the directory or either file is missing, so a successful
run means the identity was actually packaged. Output:

```
mobile/build/outputs/apk/debug/mobile-debug.apk
```

### Build one architecture at a time

`assembleDebug` writes a single output name, so build, move the result aside, then build the next.
Otherwise the second build overwrites the first.

```sh
# Intel x86_64, for GWM Lemon (Harman / iFlytek HiLife) head units
DIPLAY_AUTH_ASSETS_DIR=$AUTH ./gradlew :mobile:assembleStandaloneDebug -PdiplyAbiFilters=x86_64
mkdir -p dist
mv mobile/build/outputs/apk/debug/mobile-debug.apk dist/DiPlay-x86_64-standalone.apk

# ARM, for BYD DiLink and other ARM head units
DIPLAY_AUTH_ASSETS_DIR=$AUTH ./gradlew :mobile:assembleStandaloneDebug \
  -PdiplyAbiFilters=arm64-v8a,armeabi-v7a
mv mobile/build/outputs/apk/debug/mobile-debug.apk dist/DiPlay-arm64-armeabi-standalone.apk
```

## 4. Verify before you trust it

Confirm the identity is inside and the architecture is what you intended:

```sh
# must list exactly one architecture
unzip -l dist/DiPlay-x86_64-standalone.apk | grep -o 'lib/[a-z0-9_-]*/' | sort -u

# must list both files, non-empty
unzip -l dist/DiPlay-x86_64-standalone.apk | grep offline-mfi

# minSdk must read 26; aapt2 writes it in decimal
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree --file AndroidManifest.xml \
  dist/DiPlay-x86_64-standalone.apk | grep minSdkVersion
```

Also install over the previous build rather than uninstalling, so your settings survive:

```sh
adb install -r dist/DiPlay-x86_64-standalone.apk
```

## 5. Publish to the release

Upload to the existing `v0.3.0` release. Uploaded assets are public, so confirm the file you are
publishing is the verified one from step 4.

```sh
cd dist
sha256sum *.apk > SHA256SUMS.txt

gh release upload v0.3.0 \
  DiPlay-x86_64-standalone.apk \
  DiPlay-arm64-armeabi-standalone.apk \
  SHA256SUMS.txt --clobber
```

Without the `gh` CLI:

```sh
RELEASE_ID=402870077   # the v0.3.0 release; look it up as shown below

for f in DiPlay-x86_64-standalone.apk DiPlay-arm64-armeabi-standalone.apk SHA256SUMS.txt; do
  curl -sL -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$f" \
    "https://uploads.github.com/repos/Worldjesse/DiPlay/releases/$RELEASE_ID/assets?name=$f"
done
```

Look the release up to confirm the id rather than trusting the value above:

```sh
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  https://api.github.com/repos/Worldjesse/DiPlay/releases/tags/v0.3.0 | grep '"id"'
```

The token needs `contents: write`. Uploading a name that already exists returns 422; delete the
existing asset first, or use `gh release upload --clobber` which handles it.

## What the source-only and standalone packages share

Only the accessory identity differs. Both are built from the same commit and produce the same
CarPlay receive path, so the Android 8 and GWM Lemon platform work described in
[COMPATIBILITY.md](COMPATIBILITY.md) applies to both.

| | Source-only (CI) | Standalone (local) |
| --- | --- | --- |
| Installs and runs | Yes | Yes |
| CarPlay pairing with an iPhone | **No** | Yes, while the identity remains valid |
| Architecture-specific builds | Yes | Yes |
| Contains the identity | No | Yes |
| Publishable to CI | Yes | **No** |
