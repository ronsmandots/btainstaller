# Notes
- Moved out of RonTech (src/installer there). Package `ron.btainstaller`: `BtaSetup` (everything), entry points `FabricPreLaunch`, `ForgeEntry` (net.minecraftforge, 1.8+), `LegacyForgeEntry` (cpw.mods.fml, 1.7.10 and older), `NeoForgeEntry`. Metadata: `fabric.mod.json`, `META-INF/mods.toml`, `mcmod.info` (old Forge), `META-INF/neoforge.mods.toml`.
- `src/stubs`: fake @Mod annotations and nothing else, compile-time only (not in the jar).
- Everything must stay Java 8 (release 8): older Forge/Fabric cannot read newer class files.
- Does not carry or download RonTech or any other mod.

## Standalone mode (Standalone.java)
Used when the game folder is not a MultiMC instance. Reads the same patch JSONs as the Prism route (library list, LWJGL
natives picked by artifact suffix, jar mod, asset index), merges the BTA jar over the b1.7.3 jar, legacy assets go to
`bta/game/resources`, launches `mainClass` of the fabric-loader patch (Knot) with `--username --session - --gameDir --uuid`.
State file: `bta/ready.properties`. Java 8 only, no dependencies (own JSON reader, tar.gz reader).
