Welcome to _Dash Wallet_, a standalone Dash payment app for your Android device!

This project contains several sub-projects:

 * __wallet__:
     The Android app itself. This is probably what you're searching for.
 * __native-scrypt__:
     Native code implementation for Scrypt. The C files are copied from the
     Java Scrypt project at [GitHub](https://github.com/wg/scrypt).
 * __native-x11__
     Native code implementation for X11. The C files are copied from the
     Java Scrypt project at [GitHub](https://github.com/hashengineering/dashj).
 * __market__:
     App description and promo material for the Google Play app store.
 * __integration-android__:
     A tiny library for integrating Dash payments into your own Android app
     (e.g. donations, in-app purchases).
 * __sample-integration-android__:
     A minimal example app to demonstrate integration of digital payments into
     your Android app.

You can build all sub-projects at once using Gradle:

`gradle clean build -x test`

The built apks will be in `wallet/build/outputs/apk`


