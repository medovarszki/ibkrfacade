# Vendored TWS API

Interactive Brokers does not publish the TWS API to Maven Central, so the official Java client is stored here as a
file based Maven repository (`com.interactivebrokers:tws-api`). The build resolves it like any other dependency
(see the `tws-api-vendored` repository in `pom.xml`, with checksum verification), no manual install step is needed.

| | |
|---|---|
| Version | 10.50.01 (`API_Version=1050.02`), IBKR "stable" channel, released 2026-09-30 |
| Source | `https://download2.interactivebrokers.com/installers/tws-api/stable/tws-api-stable-linux-x64.sh` |
| Installer SHA-256 | `02247a284feda91da4904e5e3d851f20b272124aa91c20ca6097112521ab61df` |
| `tws-api-10.50.01.jar` SHA-256 | `955d6613f55e48159638086bd0ae40b476ae41842645c8bcda2f506929ca4389` (unmodified `source/javaclient/TwsApi.jar`) |
| Sources | `tws-api-10.50.01-sources.jar` (the `com/**/*.java` files of the installer) |
| Runtime dependency | `com.google.protobuf:protobuf-java:4.29.5` (from Maven Central, declared in the vendored pom) |
| License | GNU GPL v3, see [TWS-API-LICENSE.txt](TWS-API-LICENSE.txt) |

**License note:** from version 10.49 the TWS API is released under the GPL v3. Distributing a program linked with it
(eg. publishing the Docker image) brings the GPL obligations for the combined work.

## Update

```bash
scripts/update-twsapi.sh stable   # or: latest
```

Then set `<tws-api.version>` in `pom.xml`, update this file, and run the tests. Remove the old version directory.
Keep the API version in line with the IB Gateway image (`ghcr.io/gnzsnz/ib-gateway`) in `docker-compose.yml`.
