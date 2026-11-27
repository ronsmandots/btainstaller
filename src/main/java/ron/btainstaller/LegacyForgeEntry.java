package ron.btainstaller;

import cpw.mods.fml.common.Mod;

/** Forge 1.7.10 and older: the annotation lives in cpw.mods.fml instead of net.minecraftforge.fml. */
@Mod(modid = "btainstaller", name = "BTA Installer", version = "1.0.0", acceptableRemoteVersions = "*")
public class LegacyForgeEntry {
	public LegacyForgeEntry() {
		BtaSetup.start(LegacyForgeEntry.class.getClassLoader());
	}
}
