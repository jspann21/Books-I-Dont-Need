# Release build

GitHub builds the release APK with `.github/workflows/android-release.yml`.

The workflow runs when a `v*` tag is pushed, or manually from the Actions tab. For version `1.0.3`, push:

```sh
git tag v1.0.3
git push origin v1.0.3
```

To publish an installable signed APK, add these repository secrets in GitHub:

- `ANDROID_SIGNING_KEY`: base64-encoded contents of the release `.jks` or `.keystore` file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: key alias
- `ANDROID_KEY_PASSWORD`: key password

Generate `ANDROID_SIGNING_KEY` from PowerShell with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks"))
```

If the signing secrets are missing, GitHub still builds and attaches an unsigned release APK.
