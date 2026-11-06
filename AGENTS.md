# Alpha project instructions

## Legacy compatibility

Do not preserve compatibility with legacy Alpha versions, formats, interfaces, or protocols.
Remove legacy compatibility code and allow breaking changes when implementing the current design.
Do not add compatibility layers or migrations solely to support earlier development versions.
Do not silently delete existing user data or credentials.

## GitHub Releases

Publish only signed APKs from the Gradle `release` build type to GitHub Releases, including prereleases.
Before uploading an APK, verify that `aapt dump badging` succeeds and its output has no `application-debuggable` entry.

## Generated behavior checks

Use one executable behavior checker for generated apps and games.
Derive each scenario from the request and bind its steps to real controls and observable outputs.
Keep expected results and acceptance decisions in the host.
Keep named apps, games, and scenario presets in regression fixtures.
Test the shared generation path with an app workflow and a game workflow.
