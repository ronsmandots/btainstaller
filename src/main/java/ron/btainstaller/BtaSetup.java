package ron.btainstaller;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything for running this jar on a Minecraft that is not Better than Adventure (any version, Fabric, Quilt,
 * Forge or NeoForge). Plain Java 8 with no Minecraft or loader classes, so it loads anywhere.
 *
 * <p>Flow: a loader entry point (FabricPreLaunch, ForgeEntry, NeoForgeEntry) calls {@link #start}. On Better than
 * Adventure that returns at once. Otherwise a second JVM is started with this class as its main (so the window works
 * whatever the game's own AWT/LWJGL state is, and so the jar is no longer locked on Windows) and the game exits.
 * That JVM shows the note, and only when the player agrees does it touch anything: it downloads the Better than
 * Adventure instance files, backs the old ones up, writes the new ones, and disables the mods that are not for
 * Better than Adventure.
 *
 * <p>The instance format is MultiMC/Prism (instance.cfg, mmc-pack.json and patches next to the game folder), which is
 * what the Better than Adventure instance repo ships. Other launchers get an explanation and a link instead.
 */
public final class BtaSetup {
	private static final String FALLBACK_BTA_VERSION = "8.0.1";
	private static final String REPO_PAGE = "https://github.com/Turnip-Labs/bta-fabric-instance-repo";
	private static final String REPO_RAW = "https://raw.githubusercontent.com/Turnip-Labs/bta-fabric-instance-repo/";
	/** The instance files in the repo's client folder, which is the Better than Adventure instance (branch = major.minor of its version). */
	private static final String[] FILES = {
		"instance.cfg",
		"mmc-pack.json",
		"patches/net.minecraft.json",
		"patches/org.lwjgl.json",
		"patches/custom.jarmod.bta.json",
		"patches/net.fabricmc.fabric-loader.json",
	};
	private static final String MARKER = "ron/btainstaller/BtaSetup.class";

	private BtaSetup() {
	}

	// ---------------------------------------------------------------- entry from the game

	/** Called by the loader entry points. Returns normally on Better than Adventure and on servers. */
	public static void start(ClassLoader gameLoader) {
		try {
			if (isBta(gameLoader) || !isClient()) {
				return;
			}
			Path gameDir = gameDir();
			Path jar = ownJar(gameDir);
			if (jar == null) {
				return;
			}
			List<String> cmd = new ArrayList<String>();
			cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
			cmd.add("-Djava.awt.headless=false");
			cmd.add("-cp");
			cmd.add(jar.toString());
			cmd.add(BtaSetup.class.getName());
			cmd.add(gameDir.toString());
			cmd.add(jar.toString());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.environment().remove("JAVA_TOOL_OPTIONS");
			pb.environment().remove("_JAVA_OPTIONS");
			pb.redirectErrorStream(true);
			pb.redirectOutput(gameDir.resolve("btainstaller.log").toFile());
			pb.start();
			Runtime.getRuntime().halt(0);
		} catch (Throwable t) {
			// Never break a game over this: carry on loading (the mod then simply does nothing here).
			t.printStackTrace();
		}
	}

	/** Better than Adventure has its own core classes under net.minecraft.core; no other Minecraft does. */
	private static boolean isBta(ClassLoader loader) {
		String res = "net/minecraft/core/entity/Mob.class";
		return (loader != null && loader.getResource(res) != null)
			|| Thread.currentThread().getContextClassLoader() != null && Thread.currentThread().getContextClassLoader().getResource(res) != null
			|| ClassLoader.getSystemResource(res) != null;
	}

	/** Clients are launched with a username and an access token; servers never are. */
	private static boolean isClient() {
		String cmd = System.getProperty("sun.java.command", "");
		return cmd.contains("--accessToken") || cmd.contains("--username") || cmd.toLowerCase().contains("client");
	}

	private static Path gameDir() {
		String cmd = System.getProperty("sun.java.command", "");
		String[] parts = cmd.split(" ");
		for (int i = 0; i < parts.length - 1; i++) {
			if (parts[i].equals("--gameDir")) {
				return Paths.get(parts[i + 1]).toAbsolutePath();
			}
		}
		return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
	}

	/** The jar this class was loaded from, found through the code source or else by looking in the mods folder. */
	private static Path ownJar(Path gameDir) {
		try {
			String s = BtaSetup.class.getProtectionDomain().getCodeSource().getLocation().toString();
			if (s.startsWith("jar:")) {
				s = s.substring(4);
			}
			int bang = s.indexOf("!/");
			if (bang >= 0) {
				s = s.substring(0, bang);
			}
			Path p = Paths.get(new URI(s));
			if (Files.isRegularFile(p)) {
				return p;
			}
		} catch (Throwable ignored) {
			// fall through to the scan
		}
		for (Path jar : jars(gameDir.resolve("mods"))) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				if (zip.getEntry(MARKER) != null) {
					return jar;
				}
			} catch (IOException ignored) {
				// next
			}
		}
		return null;
	}

	private static List<Path> jars(Path dir) {
		List<Path> out = new ArrayList<Path>();
		if (Files.isDirectory(dir)) {
			try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.jar")) {
				for (Path p : s) {
					out.add(p);
				}
			} catch (IOException ignored) {
				// empty
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- the helper JVM

	private static String btaVersion(Path jar) {
		try (JarFile jf = new JarFile(jar.toFile())) {
			Manifest m = jf.getManifest();
			String v = m == null ? null : m.getMainAttributes().getValue("Bta-Version");
			if (v != null && !v.isEmpty()) {
				return v;
			}
		} catch (IOException ignored) {
			// fallback
		}
		return FALLBACK_BTA_VERSION;
	}

	/** The note the player has to see before anything is downloaded or changed. */
	static String note(String version) {
		return "This mod installs a custom Minecraft version called Better than Adventure, version " + version + ". "
			+ "It will not work with other mods, however, Better than Adventure supports more mods that were built specifically for it. "
			+ "It will automatically download Better than Adventure for you, replacing your current instance and disabling mods that arent compatible. "
			+ "Have fun!";
	}

	private static final String DETAILS = "What will happen if you agree:\n"
		+ " - The instance files (settings, version list, loader) are backed up to a btainstaller-backup folder, then replaced by the Better than Adventure ones.\n"
		+ " - Mods that are not for Better than Adventure are renamed to .jar.disabled. Mods that are for it are left alone.\n"
		+ " - Your worlds and other game files are not touched.\n"
		+ " - Afterwards, fully close and reopen your launcher and start the instance again. The launcher downloads the game itself.\n"
		+ "Works with MultiMC-style launchers (Prism Launcher, MultiMC and the like).";

	public static void main(String[] args) throws Exception {
		final Path gameDir = Paths.get(args[0]);
		final Path jar = Paths.get(args[1]);
		final String version = btaVersion(jar);
		SwingUtilities.invokeAndWait(new Runnable() {
			@Override
			public void run() {
				try {
					new Window(gameDir, jar, version).show();
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		});
	}

	/** The note window, then progress, then the result. */
	private static final class Window {
		private final Path gameDir;
		private final Path jar;
		private final String version;
		private final JDialog dialog = new JDialog((java.awt.Frame) null, "BTA Installer", true);

		Window(Path gameDir, Path jar, String version) {
			this.gameDir = gameDir;
			this.jar = jar;
			this.version = version;
		}

		void show() {
			JTextArea noteArea = text(note(version), 6, 54, true);
			JTextArea details = text(DETAILS, 8, 54, false);
			JPanel texts = new JPanel(new BorderLayout(0, 8));
			texts.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
			texts.add(noteArea, BorderLayout.NORTH);
			texts.add(new JScrollPane(details), BorderLayout.CENTER);

			JButton agree = new JButton("Agree and install");
			JButton cancel = new JButton("Cancel");
			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttons.add(cancel);
			buttons.add(agree);

			dialog.setLayout(new BorderLayout());
			dialog.add(texts, BorderLayout.CENTER);
			dialog.add(buttons, BorderLayout.SOUTH);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			cancel.addActionListener(e -> dialog.dispose());
			agree.addActionListener(e -> install(agree, cancel, buttons));
			dialog.pack();
			dialog.setLocationRelativeTo(null);
			dialog.setVisible(true);
		}

		private void install(final JButton agree, final JButton cancel, JPanel buttons) {
			agree.setEnabled(false);
			cancel.setEnabled(false);
			final JProgressBar bar = new JProgressBar();
			bar.setIndeterminate(true);
			bar.setString("Working...");
			bar.setStringPainted(true);
			buttons.add(bar, 0);
			dialog.pack();
			new Thread(() -> {
				String result;
				boolean ok = true;
				boolean unsupported = false;
				try {
					result = Setup.run(gameDir, jar, version);
				} catch (Setup.Unsupported u) {
					result = u.getMessage();
					unsupported = true;
					ok = false;
				} catch (Throwable t) {
					t.printStackTrace();
					result = "Nothing was lost: the instance files you had are unchanged or backed up.\n\n" + t;
					ok = false;
				}
				final String text = result;
				final boolean good = ok;
				final boolean help = unsupported;
				SwingUtilities.invokeLater(() -> {
					dialog.dispose();
					JOptionPane.showMessageDialog(null, text, good ? "BTA Installer: done" : "BTA Installer: could not set up", good ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
					if (help) {
						try {
							Desktop.getDesktop().browse(URI.create(REPO_PAGE));
						} catch (Throwable ignored) {
							// no browser
						}
					}
				});
			}, "btainstaller").start();
		}

		private static JTextArea text(String s, int rows, int cols, boolean bold) {
			JTextArea a = new JTextArea(s, rows, cols);
			a.setLineWrap(true);
			a.setWrapStyleWord(true);
			a.setEditable(false);
			a.setOpaque(false);
			if (bold) {
				a.setFont(a.getFont().deriveFont(java.awt.Font.BOLD, 14f));
			}
			return a;
		}
	}

	// ---------------------------------------------------------------- the actual replacement

	private static final class Setup {
		static final class Unsupported extends Exception {
			Unsupported(String m) {
				super(m);
			}
		}

		static String run(Path gameDir, Path ownJar, String version) throws Exception {
			// 1. Is this a MultiMC-style instance? (game folder = <instance>/.minecraft or <instance>/minecraft)
			Path root = gameDir.getParent();
			String leaf = gameDir.getFileName().toString();
			if (root == null || !(leaf.equals(".minecraft") || leaf.equals("minecraft"))
				|| !Files.isRegularFile(root.resolve("instance.cfg")) || !Files.isRegularFile(root.resolve("mmc-pack.json"))) {
				throw new Unsupported("This launcher's instances can't be replaced automatically (only MultiMC-style ones: Prism Launcher, MultiMC...).\n\n"
					+ "Install Prism Launcher and import the Better than Adventure instance from the page that opens next, then put your Better than Adventure mods in its mods folder.");
			}

			// 2. Download everything first, so a failed download changes nothing.
			String[] v = version.split("\\.");
			String branch = v[0] + (v.length > 1 ? "." + v[1] : "");
			Path temp = Files.createTempDirectory("btainstaller");
			for (String f : FILES) {
				Path to = temp.resolve(f);
				Files.createDirectories(to.getParent());
				byte[] data = download(REPO_RAW + branch + "/client/" + f);
				if (data.length < 10) {
					throw new IOException("Downloaded an empty file for " + f);
				}
				Files.write(to, data);
			}

			// 3. Back up what is about to be replaced.
			Path backup = root.resolve("btainstaller-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
			Files.createDirectories(backup);
			copyIfExists(root.resolve("instance.cfg"), backup.resolve("instance.cfg"));
			copyIfExists(root.resolve("mmc-pack.json"), backup.resolve("mmc-pack.json"));
			copyTree(root.resolve("patches"), backup.resolve("patches"));

			// 4. Replace the instance files (keeping the instance's name).
			String oldName = null;
			for (String line : Files.readAllLines(root.resolve("instance.cfg"), StandardCharsets.UTF_8)) {
				if (line.startsWith("name=")) {
					oldName = line;
				}
			}
			List<String> cfg = new ArrayList<String>();
			for (String line : Files.readAllLines(temp.resolve("instance.cfg"), StandardCharsets.UTF_8)) {
				if (!line.startsWith("name=")) {
					cfg.add(line);
					if (line.equals("[General]") && oldName != null) {
						cfg.add(oldName);
					}
				}
			}
			Files.write(root.resolve("instance.cfg"), cfg, StandardCharsets.UTF_8);
			Files.copy(temp.resolve("mmc-pack.json"), root.resolve("mmc-pack.json"), StandardCopyOption.REPLACE_EXISTING);
			Files.createDirectories(root.resolve("patches"));
			for (String f : FILES) {
				if (f.startsWith("patches/")) {
					Files.copy(temp.resolve(f), root.resolve(f), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			// Loader patches of the old setup (Forge, NeoForge, Quilt, intermediary...) no longer have a component, so the launcher ignores them.

			// 5. Disable mods that are not for Better than Adventure. The game has just exited, but Windows can take a moment to let go of the jars.
			int disabled = 0;
			int kept = 0;
			for (Path mod : jars(gameDir.resolve("mods"))) {
				if (sameFile(mod, ownJar) || isBtaMod(mod)) {
					kept++;
					continue;
				}
				Path target = mod.resolveSibling(mod.getFileName() + ".disabled");
				move(mod, target);
				disabled++;
			}
			return "Better than Adventure " + version + " is set up.\n\n"
				+ disabled + " mod(s) were disabled (renamed to .jar.disabled), " + kept + " kept.\n"
				+ "The old instance files are in " + backup.getFileName() + ".\n\n"
				+ "Now fully close your launcher and open it again, then start this instance. The launcher will download Better than Adventure.";
		}

		private static byte[] download(String url) throws IOException {
			HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(15000);
			c.setReadTimeout(30000);
			c.setRequestProperty("User-Agent", "BTAInstaller");
			if (c.getResponseCode() != 200) {
				throw new IOException("Could not download " + url + " (HTTP " + c.getResponseCode() + ")");
			}
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
				}
				return out.toByteArray();
			}
		}

		private static void copyIfExists(Path from, Path to) throws IOException {
			if (Files.isRegularFile(from)) {
				Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		private static void copyTree(Path from, Path to) throws IOException {
			if (!Files.isDirectory(from)) {
				return;
			}
			Files.createDirectories(to);
			try (DirectoryStream<Path> s = Files.newDirectoryStream(from)) {
				for (Path p : s) {
					if (Files.isDirectory(p)) {
						copyTree(p, to.resolve(p.getFileName().toString()));
					} else {
						Files.copy(p, to.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}

		private static boolean sameFile(Path a, Path b) {
			try {
				return Files.isSameFile(a, b);
			} catch (IOException e) {
				return a.toAbsolutePath().equals(b.toAbsolutePath());
			}
		}

		private static void move(Path from, Path to) throws Exception {
			IOException last = null;
			for (int i = 0; i < 40; i++) {
				try {
					Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
					return;
				} catch (IOException e) {
					last = e;
					Thread.sleep(500);
				}
			}
			throw last;
		}

		/** A mod is for Better than Adventure when its fabric.mod.json depends on HalpLibe or BTA, or on Minecraft b1.7.x. */
		private static boolean isBtaMod(Path jar) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry e = zip.getEntry("fabric.mod.json");
				if (e == null) {
					return false;
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (InputStream in = zip.getInputStream(e)) {
					byte[] buf = new byte[4096];
					int n;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
					}
				}
				String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
				return json.contains("\"halplibe\"") || json.contains("\"bta\"") || json.matches("(?s).*\"minecraft\"\\s*:\\s*\"[^\"]*b1\\.7.*");
			} catch (IOException ex) {
				return false;
			}
		}
	}
}
