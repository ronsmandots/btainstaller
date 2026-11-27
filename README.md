# BTA Installer

Put this jar in the `mods` folder of any Minecraft version and loader (Fabric, Quilt, Forge, NeoForge). When the game starts it shows a note, and only if you agree it sets the instance up for [Better than Adventure](https://github.com/Turnip-Labs/bta-fabric-instance-repo): the instance files are backed up, replaced by the Better than Adventure ones, and mods that are not for Better than Adventure are renamed to `.jar.disabled`. Better than Adventure mods in the folder are left alone.

- Works with MultiMC-style launchers (Prism Launcher, MultiMC). Other launchers get an explanation and a link.
- Does nothing on Better than Adventure itself and on servers.
- It does not install any other mod; put the Better than Adventure mods you want in the `mods` folder yourself.
- Written as plain Java 8 without Minecraft or loader code, so the same jar loads everywhere. The Better than Adventure version is `bta_version` in `gradle.properties`.
- Log of the setup window: `btainstaller.log` in the game folder.

## Other launchers (CurseForge app, Modrinth app, Minecraft launcher...)

Only MultiMC-style instances (Prism Launcher, MultiMC) can be replaced in place. On any other launcher, after the player
agrees, the installer downloads Better than Adventure itself (client jar, Beta 1.7.3 base jar, Fabric loader, LWJGL
natives, sounds), uses a Java 17+ it finds or downloads one, and starts the game in its own process from
`<game folder>/bta` (own `game/mods`, worlds and options), with the player name of the launch it was started from.
The next start of that instance offers "Launch Better than Adventure". `bta/start-bta.cmd|.sh` start it by hand.
Your instance is not changed. Not tested in real launchers yet.

The note window has a version selector (the tags of the instance repo, newest selected by default; only the built-in
version when GitHub can't be reached). In the standalone mode, Better than Adventure mods found in the instance's mods
folder are moved to `bta/game/mods` (this installer stays).
