# jadx-morphe
Updated codebase to support JADX 1.5.2+ and the Morphe API. All credit goes to [valonsdev](https://github.com/valonsodev/jadx-revanced) for the original work.

## Building 
> [!IMPORTANT]
> A GitHub access token for accessing the `morphe-patcher` repo

1. Clone this repository.
2. Run `./gradlew plugin:dist` to build the plugin (output is placed in `plugin/build/dist`).
3. Install plugin via the JADX _Manage plugins_ interface.
