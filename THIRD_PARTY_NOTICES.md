# Third-party components

Zap's [MIT license](LICENSE) covers its original code, documentation, and artwork.
Third-party components keep their own licenses.

## Android app

The resolved runtime graph and upstream license metadata are listed in
[the Android notices](licenses/android/THIRD_PARTY_NOTICES.txt). All 114 Maven
runtime modules in the audited graph declare Apache-2.0, including inherited POM
licenses. This graph includes platform and metadata modules that do not necessarily
contribute code to the APK.

The app bundles the notices and [Apache-2.0 text](licenses/android/Apache-2.0.txt)
under `assets/licenses/`, alongside Zap's MIT license. Original upstream copyrights
and embedded notices remain applicable.

OkHttp 4.12.0 also contains Public Suffix List data covered by
[MPL-2.0](licenses/android/MPL-2.0.txt). Its embedded notice is preserved, and the
[corresponding rule source](licenses/android/okhttp-public-suffix-list.txt) accompanies
the app. That text is decoded from the exact distributed data; rules are unchanged.
It does not change the license of Zap's original code.

## Mac app

The Mac target has no downloaded Swift packages or vendored third-party libraries.
It links macOS-provided frameworks and the system SQLite library. Those components
are supplied by the operating system. The build script includes Zap's MIT license
in `Zap.app/Contents/Resources/LICENSE`.

## Build and test tools

The checked-in Gradle wrapper JAR contains its Apache-2.0 license at
`META-INF/LICENSE`; the [same license text](licenses/android/Apache-2.0.txt) is also
available in this repository.

Node and Gradle dependencies are downloaded during development. They are not
relicensed by Zap and are not included in its source checkout. The backend has
only development dependencies; Wrangler bundles the project's Worker code for
deployment. Tool binaries such as workerd, sharp/libvips, Kotlin, and Android build
tools have their own licenses and may include additional third-party components.
The [npm license inventory](docs/dependencies/npm-licenses.json) records lockfile
metadata, including optional platform binaries. That metadata is not a complete
license manifest for the internals of those binaries.

The packaging scripts distribute the app artifacts, not `node_modules`, Gradle
caches, or Android SDK installations. If you separately redistribute build tools,
preserve their full upstream notices and satisfy their own source/license terms.

See the [dependency audit](docs/dependency-audit.md) for findings and regeneration
commands. Recheck notices whenever dependencies or bundled assets change.
