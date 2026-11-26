package ron.btainstaller;

import net.neoforged.fml.common.Mod;

/** NeoForge: the mod's constructor runs while mods load. */
@Mod("btainstaller")
public class NeoForgeEntry {
	public NeoForgeEntry() {
		BtaSetup.start(NeoForgeEntry.class.getClassLoader());
	}
}
