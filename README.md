# BTA Installer

Put this jar in the `mods` folder of any Minecraft version and loader (Fabric, Quilt, Forge, NeoForge). When the game starts it shows a note, and only if you agree it sets the instance up for [Better than Adventure](https://github.com/Turnip-Labs/bta-fabric-instance-repo): the instance files are backed up, replaced by the Better than Adventure ones, and mods that are not for Better than Adventure are renamed to `.jar.disabled`. Better than Adventure mods in the folder are left alone.

- Works with MultiMC-style launchers (Prism Launcher, MultiMC). Other launchers get an explanation and a link.
- Does nothing on Better than Adventure itself and on servers.
- It does not install any other mod; put the Better than Adventure mods you want in the `mods` folder yourself.
- Written as plain Java 8 without Minecraft or loader code, so the same jar loads everywhere. The Better than Adventure version is `bta_version` in `gradle.properties`.
- Log of the setup window: `btainstaller.log` in the game folder.
