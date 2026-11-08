# Supplementary Unicode in platform settings

The pinned open-source IntelliJ Platform 2026.1.4 filters primitive map keys
through `JDOMUtil.removeControlChars`. Its UTF-16 code-unit loop removes both
halves of valid supplementary characters. A project path containing `😀` was
saved without that character in trusted-paths.xml and recentProjects.xml. The
unchanged app bundle prompted to trust the exact same owned fixture again after
a normal exit; rebuilding was not required to reproduce it.

The public-source patch iterates Unicode code points while preserving the
existing XML validity policy. Legal pairs survive; illegal control characters
and unpaired surrogates are still filtered. It changes serialization only.
Existing trust policy and explicit user choices remain in place. Previously
truncated entries are not guessed, migrated or granted new trust.

The public release commit, source/JAR/patch hashes and complete replacement class
list are pinned in
[platform-unicode-2026.1.4.json](../../product/patches/platform-unicode-2026.1.4.json).
The compiler output is verified under a locked cache. Packaging patches only an
owned app copy below this repository's build directory. The immutable source SDK
and installed reference applications are untouched; plugins do not shadow core
platform classes. The app manifest records the patched artifact identity.

An isolated classloader regression uses the actual rebuilt platform classes and
real TrustedPaths state serialization, including distinct emoji/BMP/truncated
sibling paths. Native same-bundle restart verification is recorded in
[unicode-persistence-native.json](unicode-persistence-native.json). Full settings
and layout parity remain open.
