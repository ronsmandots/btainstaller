# BTA Installer

Put this jar in the `mods` folder of any Minecraft version and loader (Fabric, Quilt, Forge, NeoForge). When the game starts it shows a note, and only if you agree it downloads and starts [Better than Adventure](https://github.com/Turnip-Labs/bta-fabric-instance-repo) in its own folder next to your instance, which is left as it is.

- On MultiMC-style launchers (Prism Launcher, MultiMC) it does not install anything; it shows a note with a link to the GitHub page to set the instance up manually. Other launchers get the automatic standalone install.
- Does nothing on Better than Adventure itself and on servers.
- It does not install any other mod; put the Better than Adventure mods you want in the `mods` folder yourself.
- Written as plain Java 8 without Minecraft or loader code, so the same jar loads everywhere. The Better than Adventure version is `bta_version` in `gradle.properties`.
- Log of the setup window: `btainstaller.log` in the game folder.

## Other launchers (CurseForge app, Modrinth app, Minecraft launcher...)

After the player agrees, the installer downloads Better than Adventure itself (client jar, Beta 1.7.3 base jar, Fabric loader, LWJGL
natives, sounds), uses a Java 17+ it finds or downloads one, and starts the game in its own process from
`<game folder>/bta` (own `game/mods`, worlds and options), with the player name of the launch it was started from.
The next start of that instance offers "Launch Better than Adventure". `bta/start-bta.cmd|.sh` start it by hand.
Your instance is not changed. Not tested in real launchers yet.

The note window has a version selector (the tags of the instance repo, newest selected by default; only the built-in
version when GitHub can't be reached). In the standalone mode, Better than Adventure mods found in the instance's mods
folder are moved to `bta/game/mods` (this installer stays).

"Open Better than Adventure automatically from now on" (a checkbox in the launch prompt) creates `bta/auto-launch`; with
it, opening the instance starts Better than Adventure without asking. Delete that file to get the prompt back (delete
`bta/ready.properties` as well to see the install window again).

## HalpLibe, Mod Menu and updates
Every install (and reinstall) downloads the newest HalpLibe release made for the chosen Better than Adventure version
from GitHub (Turnip-Labs/bta-halplibe; files named like `halplibe-6.2.0+8.0.1.jar`; same major.minor and not a newer patch
also counts) into the mods folder it uses (`bta/game/mods`, or the instance's `mods` on Prism/MultiMC), and removes older
HalpLibe jars there. HalpLibe jars found in the instance's mods folder are not moved over; the installer manages that one.

Mod Menu is handled the same way (Turnip-Labs/ModMenu; files named like `modmenu-bta-5.0.2.jar`, the Better than Adventure
version is read from the release name such as "[BTA! v8.0] 5.0.2"; older `modmenu*.jar` files are removed and copies in the
instance's mods folder are left alone). If either download fails, the install still finishes and says so.

When the prompt appears on a later run and the repo has a newer Better than Adventure version than the installed one, it
offers "Update to X" with a warning to update the mods to X too. "Launch" or "Not now" remembers that version
(`bta/update-ignored`), so it only asks again for an even newer one; "Reinstall" can still pick any version. The automatic
launch is skipped, showing the prompt, while a not-yet-declined update exists.
