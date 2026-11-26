package net.fabricmc.loader.api.entrypoint;

/** Compile-time stand-in for Fabric/Quilt's pre-launch entrypoint interface (never packaged). */
public interface PreLaunchEntrypoint {
	void onPreLaunch();
}
