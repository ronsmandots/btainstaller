# Notes
- Moved out of RonTech (src/installer there). Package `ron.btainstaller`: `BtaSetup` (everything), entry points `FabricPreLaunch`, `ForgeEntry`, `NeoForgeEntry`. Metadata: `fabric.mod.json`, `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`.
- `src/stubs`: fake @Mod annotations and nothing else, compile-time only (not in the jar).
- Everything must stay Java 8 (release 8): older Forge/Fabric cannot read newer class files.
- Does not carry or download RonTech or any other mod.
