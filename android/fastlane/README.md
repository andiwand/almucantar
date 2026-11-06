fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android screenshots

```sh
[bundle exec] fastlane android screenshots
```

Capture the store screenshots of an app on the emulator and frame them

### android upload_listing

```sh
[bundle exec] fastlane android upload_listing
```

Upload the framed screenshots of an app to Google Play (images only, no app and no text)

### android listing

```sh
[bundle exec] fastlane android listing
```

Upload the store listing of an app to Google Play (the texts, the icon, the feature graphic)

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Upload the app bundle of an app to a track of Google Play (the bundle only, no listing)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
