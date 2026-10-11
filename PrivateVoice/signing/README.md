# Debug signing key

`debug.keystore` is a **debug** key (store/key password `android`, alias `androiddebugkey`), committed on purpose so that every build — local or GitHub Actions — is signed with the same key and installs as an update over the previous one. It is not a secret and must not be used for release builds.
