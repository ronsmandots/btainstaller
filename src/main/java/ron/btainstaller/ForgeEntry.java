package ron.btainstaller;

import net.minecraftforge.fml.common.Mod;

/** Forge (1.12 and 1.13+): the mod's constructor runs while mods load. */
@Mod(value = "btainstaller", modid = "btainstaller", name = "BTA Installer", version = "1.0.0", acceptableRemoteVersions = "*")
public class ForgeEntry {
	public ForgeEntry() {
		BtaSetup.start(ForgeEntry.class.getClassLoader());
	}
}
