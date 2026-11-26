package ron.btainstaller;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Fabric and Quilt: runs before Minecraft starts. On Better than Adventure it does nothing. */
public class FabricPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		BtaSetup.start(getClass().getClassLoader());
	}
}
