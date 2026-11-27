package ron.btainstaller;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Runs Better than Adventure by itself, without touching the launcher's instance: for launchers whose instance format
 * can't be replaced (the CurseForge app, the Modrinth app, the Minecraft launcher...). It does what Prism does for the
 * Better than Adventure instance: downloads the libraries listed in the instance repo's patch files, merges the Better
 * than Adventure jar over the Beta 1.7.3 jar, downloads the (legacy) assets, finds or downloads a Java 17, and starts
 * the game in its own process with Fabric's launch class.
 *
 * <p>Everything lives in {@code <game folder>/bta}: {@code game} is the Better than Adventure game folder (its mods,
 * saves and options), the rest is libraries and the merged jar. Plain Java 8, like the rest of the installer.
 */
final class Standalone {
	interface Progress {
		void say(String text);
	}

	private static final String ASSETS_URL = "https://resources.download.minecraft.net/";
	private static final String ADOPTIUM = "https://api.adoptium.net/v3/binary/latest/17/ga/%s/%s/jre/hotspot/normal/eclipse";
	private static final String[] PATCHES = {
		"patches/net.minecraft.json",
		"patches/org.lwjgl.json",
		"patches/custom.jarmod.bta.json",
		"patches/net.fabricmc.fabric-loader.json",
	};

	private Standalone() {
	}

	static Path root(Path gameDir) {
		return gameDir.resolve("bta");
	}

	static boolean installed(Path gameDir) {
		return Files.isRegularFile(root(gameDir).resolve("ready.properties"));
	}

	/** Whether opening the instance starts Better than Adventure straight away (the file bta/auto-launch exists). */
	static boolean autoLaunch(Path gameDir) {
		return Files.isRegularFile(root(gameDir).resolve("auto-launch"));
	}

	static void setAutoLaunch(Path gameDir, boolean on) {
		try {
			Path file = root(gameDir).resolve("auto-launch");
			if (on) {
				Files.write(file, new byte[0]);
			} else {
				Files.deleteIfExists(file);
			}
		} catch (IOException ignored) {
			// the prompt simply keeps appearing
		}
	}

	// ---------------------------------------------------------------- install

	/** Downloads and prepares everything; returns once the game can be started with {@link #launch}. */
	static void install(Path gameDir, Path ownJar, String repoRaw, String branch, String user, String uuid, Progress p) throws Exception {
		Path root = root(gameDir);
		Path game = root.resolve("game");
		Files.createDirectories(game);

		p.say("Reading the Better than Adventure instance files...");
		Map<String, Object> mc = parse(download(repoRaw + branch + "/client/" + PATCHES[0]));
		Map<String, Object> lwjgl = parse(download(repoRaw + branch + "/client/" + PATCHES[1]));
		Map<String, Object> bta = parse(download(repoRaw + branch + "/client/" + PATCHES[2]));
		Map<String, Object> fabric = parse(download(repoRaw + branch + "/client/" + PATCHES[3]));

		// Libraries (Fabric loader and its dependencies, LWJGL for this system).
		List<Lib> libs = new ArrayList<Lib>();
		collect(libs, fabric, root);
		collect(libs, lwjgl, root);
		List<String> classpath = new ArrayList<String>();
		int n = 0;
		for (Lib lib : libs) {
			p.say("Downloading libraries (" + (++n) + "/" + libs.size() + ")...");
			fetch(lib.url, lib.path, lib.sha1, null);
			classpath.add(lib.path.toString());
		}

		// The game jar: Beta 1.7.3 with the Better than Adventure jar merged over it.
		Map<String, Object> baseArtifact = obj(obj(obj(mc.get("mainJar")).get("downloads")).get("artifact"));
		Map<String, Object> btaArtifact = obj(obj(obj(list(bta.get("jarMods")).get(0)).get("downloads")).get("artifact"));
		Path baseJar = root.resolve("base-client.jar");
		Path btaJar = root.resolve("bta-client.jar");
		p.say("Downloading Minecraft Beta 1.7.3...");
		fetch((String) baseArtifact.get("url"), baseJar, (String) baseArtifact.get("sha1"), null);
		p.say("Downloading Better than Adventure (about 60 MB)...");
		fetch((String) btaArtifact.get("url"), btaJar, (String) btaArtifact.get("sha1"), null);
		p.say("Combining the game jars...");
		Path merged = root.resolve("minecraft-bta.jar");
		merge(btaJar, baseJar, merged);
		classpath.add(merged.toString());

		// Assets: Beta 1.7.3 reads its sounds and such from <game folder>/resources.
		assets(obj(mc.get("assetIndex")), game.resolve("resources"), p);

		// Java 17.
		p.say("Looking for Java 17...");
		Path java = findJava(root, p);


		Properties ready = new Properties();
		ready.setProperty("version", String.valueOf(bta.get("version")));
		ready.setProperty("java", java.toString());
		ready.setProperty("mainClass", String.valueOf(fabric.get("mainClass")));
		ready.setProperty("classpath", join(classpath, "|"));
		try (OutputStream out = Files.newOutputStream(root.resolve("ready.properties"))) {
			ready.store(out, "Written by BTA Installer");
		}
		writeScripts(root, game, java, String.valueOf(fabric.get("mainClass")), classpath, user, uuid);
	}

	/** Valid Better than Adventure mods (not this installer) go from the instance's mods folder to the bta game's. */
	static void moveMods(Path from, Path to) {
		if (!Files.isDirectory(from)) {
			return;
		}
		List<Path> mods = new ArrayList<Path>();
		try (java.nio.file.DirectoryStream<Path> s = Files.newDirectoryStream(from, "*.jar")) {
			for (Path m : s) {
				mods.add(m);
			}
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		for (Path m : mods) {
			try {
				if (isInstaller(m) || !BtaSetup.Setup.isBtaMod(m)) {
					continue;
				}
				Files.createDirectories(to);
				BtaSetup.Setup.move(m, to.resolve(m.getFileName()));
				System.out.println("Moved " + m.getFileName() + " to " + to);
			} catch (Exception e) {
				// one stuck mod must not stop the rest, or the game
				System.out.println("Could not move " + m.getFileName() + ": " + e);
			}
		}
	}

	private static boolean isInstaller(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getEntry("ron/btainstaller/BtaSetup.class") != null;
		} catch (IOException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- launch

	static void launch(Path gameDir, String user, String uuid) throws IOException {
		Path root = root(gameDir);
		Properties ready = new Properties();
		try (InputStream in = Files.newInputStream(root.resolve("ready.properties"))) {
			ready.load(in);
		}
		List<String> classpath = new ArrayList<String>();
		for (String s : ready.getProperty("classpath").split("\\|")) {
			classpath.add(s);
		}
		Path game = root.resolve("game");
		Files.createDirectories(game.resolve("logs"));
		// Better than Adventure mods in the instance's mods folder (new ones too) go to the Better than Adventure game folder.
		moveMods(gameDir.resolve("mods"), game.resolve("mods"));
		ProcessBuilder pb = new ProcessBuilder(command(Paths.get(ready.getProperty("java")), ready.getProperty("mainClass"), classpath, game, user, uuid));
		pb.directory(game.toFile());
		pb.environment().remove("JAVA_TOOL_OPTIONS");
		pb.environment().remove("_JAVA_OPTIONS");
		pb.redirectErrorStream(true);
		pb.redirectOutput(game.resolve("logs").resolve("btainstaller-launch.log").toFile());
		pb.start();
	}

	private static List<String> command(Path java, String mainClass, List<String> classpath, Path game, String user, String uuid) {
		List<String> cmd = new ArrayList<String>();
		cmd.add(java.toString());
		if (isMac()) {
			cmd.add("-XstartOnFirstThread");
		}
		cmd.add("-Xms512M");
		cmd.add("-Xmx2G");
		cmd.add("-Djava.awt.headless=true");
		cmd.add("-cp");
		cmd.add(join(classpath, File.pathSeparator));
		cmd.add(mainClass);
		cmd.add("--username");
		cmd.add(user);
		cmd.add("--session");
		cmd.add("-");
		cmd.add("--gameDir");
		cmd.add(game.toString());
		cmd.add("--uuid");
		cmd.add(uuid);
		return cmd;
	}

	/** start-bta.cmd / start-bta.sh in the bta folder start the game without Minecraft or the launcher. */
	private static void writeScripts(Path root, Path game, Path java, String mainClass, List<String> classpath, String user, String uuid) throws IOException {
		List<String> cmd = command(java, mainClass, classpath, game, user, uuid);
		StringBuilder line = new StringBuilder();
		for (String s : cmd) {
			line.append('"').append(s).append("\" ");
		}
		if (isWindows()) {
			String text = "@echo off\r\ncd /d \"" + game + "\"\r\n" + line + "\r\n";
			Files.write(root.resolve("start-bta.cmd"), text.getBytes(StandardCharsets.UTF_8));
		} else {
			Path sh = root.resolve("start-bta.sh");
			Files.write(sh, ("#!/bin/sh\ncd \"" + game + "\" || exit 1\nexec " + line + "\n").getBytes(StandardCharsets.UTF_8));
			sh.toFile().setExecutable(true);
		}
	}

	// ---------------------------------------------------------------- libraries and jars

	private static final class Lib {
		String url;
		Path path;
		String sha1;
	}

	/** Adds the libraries of one patch file that apply to this system (LWJGL natives: only this system's). */
	private static void collect(List<Lib> out, Map<String, Object> patch, Path root) {
		String natives = nativesSuffix();
		for (Object o : list(patch.get("libraries"))) {
			Map<String, Object> l = obj(o);
			String[] gav = ((String) l.get("name")).split(":");
			String artifact = gav[1];
			if (artifact.contains("natives-") && !artifact.endsWith(natives)) {
				continue;
			}
			String rel = gav[0].replace('.', '/') + "/" + artifact + "/" + gav[2] + "/" + artifact + "-" + gav[2] + (gav.length > 3 ? "-" + gav[3] : "") + ".jar";
			Lib lib = new Lib();
			lib.path = root.resolve("libraries").resolve(rel);
			Object downloads = l.get("downloads");
			if (downloads != null) {
				Map<String, Object> a = obj(obj(downloads).get("artifact"));
				lib.url = (String) a.get("url");
				lib.sha1 = (String) a.get("sha1");
			} else {
				String base = (String) l.get("url");
				lib.url = (base.endsWith("/") ? base : base + "/") + rel;
				lib.sha1 = (String) l.get("sha1");
			}
			out.add(lib);
		}
	}

	private static String nativesSuffix() {
		String arch = System.getProperty("os.arch", "").toLowerCase();
		boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
		if (isWindows()) {
			return arm64 ? "natives-windows-arm64" : (arch.equals("x86") || arch.equals("i386") ? "natives-windows-x86" : "natives-windows");
		}
		if (isMac()) {
			return arm64 ? "natives-macos-arm64" : "natives-macos";
		}
		return arm64 ? "natives-linux-arm64" : (arch.startsWith("arm") ? "natives-linux-arm32" : "natives-linux");
	}

	/** Everything of the Better than Adventure jar, plus whatever of the base jar it does not replace; signatures dropped. */
	private static void merge(Path top, Path base, Path out) throws IOException {
		Set<String> done = new HashSet<String>();
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
			for (Path src : new Path[]{top, base}) {
				try (ZipFile zip = new ZipFile(src.toFile())) {
					Enumeration<? extends ZipEntry> en = zip.entries();
					while (en.hasMoreElements()) {
						ZipEntry e = en.nextElement();
						String name = e.getName();
						String up = name.toUpperCase();
						if (up.equals("META-INF/MANIFEST.MF") || up.endsWith(".SF") || up.endsWith(".RSA") || up.endsWith(".DSA") || !done.add(name)) {
							continue;
						}
						zos.putNextEntry(new ZipEntry(name));
						if (!e.isDirectory()) {
							try (InputStream in = zip.getInputStream(e)) {
								copy(in, zos);
							}
						}
						zos.closeEntry();
					}
				}
			}
		}
	}

	private static void assets(Map<String, Object> index, Path resources, final Progress p) throws Exception {
		Map<String, Object> idx = parse(download((String) index.get("url")));
		Map<String, Object> objects = obj(idx.get("objects"));
		final List<String[]> jobs = new ArrayList<String[]>();
		for (Map.Entry<String, Object> e : objects.entrySet()) {
			String hash = (String) obj(e.getValue()).get("hash");
			jobs.add(new String[]{e.getKey(), hash});
		}
		final AtomicInteger done = new AtomicInteger();
		final int total = jobs.size();
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<Future<?>> futures = new ArrayList<Future<?>>();
		for (final String[] job : jobs) {
			final Path dest = resources.resolve(job[0]);
			futures.add(pool.submit(new java.util.concurrent.Callable<Object>() {
				@Override
				public Object call() throws Exception {
					if (!Files.isRegularFile(dest)) {
						fetch(ASSETS_URL + job[1].substring(0, 2) + "/" + job[1], dest, job[1], null);
					}
					p.say("Downloading sounds and other assets (" + done.incrementAndGet() + "/" + total + ")...");
					return null;
				}
			}));
		}
		try {
			for (Future<?> f : futures) {
				f.get();
			}
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------------------------------------------------------------- Java 17

	private static Path findJava(Path root, Progress p) throws Exception {
		List<Path> homes = new ArrayList<Path>();
		homes.add(Paths.get(System.getProperty("java.home")));
		String env = System.getenv("JAVA_HOME");
		if (env != null) {
			homes.add(Paths.get(env));
		}
		Path inBta = root.resolve("java");
		homes.add(inBta);
		for (String dir : searchRoots()) {
			Path d = Paths.get(dir);
			if (Files.isDirectory(d)) {
				try (Stream<Path> s = Files.walk(d, 7)) {
					s.filter(x -> x.getFileName() != null && (x.getFileName().toString().equals("java") || x.getFileName().toString().equals("java.exe"))
						&& x.getParent() != null && x.getParent().getFileName().toString().equals("bin") && Files.isRegularFile(x))
						.limit(40).forEach(x -> homes.add(x.getParent().getParent()));
				} catch (IOException ignored) {
					// unreadable folder
				}
			}
		}
		for (Path home : homes) {
			Path exe = javaExe(home);
			if (exe != null) {
				int major = majorOf(exe);
				if (major >= 17 && major <= 24) {
					return exe;
				}
			}
		}
		p.say("Downloading Java 17 (about 45 MB)...");
		// Into a common Java folder when this computer has one we can write to (so other programs can use it too), else the bta folder.
		for (String dir : searchRoots()) {
			Path d = Paths.get(dir);
			if (Files.isDirectory(d) && Files.isWritable(d)) {
				try {
					return downloadJava(d.resolve(JAVA_DIR));
				} catch (IOException | RuntimeException e) {
					// not usable after all: try the next one
				}
			}
		}
		return downloadJava(inBta);
	}

	private static String[] searchRoots() {
		String home = System.getProperty("user.home");
		List<String> r = new ArrayList<String>();
		if (isWindows()) {
			String pf = System.getenv("ProgramFiles");
			String app = System.getenv("APPDATA");
			String local = System.getenv("LOCALAPPDATA");
			if (pf != null) {
				for (String s : new String[]{"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"}) {
					r.add(pf + "\\" + s);
				}
			}
			if (app != null) {
				r.add(app + "\\.minecraft\\runtime");
				r.add(app + "\\PrismLauncher\\java");
			}
			if (local != null) {
				r.add(local + "\\Packages");
			}
		} else if (isMac()) {
			r.add("/Library/Java/JavaVirtualMachines");
			r.add(home + "/Library/Application Support/minecraft/runtime");
			r.add(home + "/Library/Application Support/PrismLauncher/java");
		} else {
			r.add("/usr/lib/jvm");
			r.add(home + "/.minecraft/runtime");
			r.add(home + "/.local/share/PrismLauncher/java");
			r.add(home + "/.sdkman/candidates/java");
		}
		r.add(home + "/curseforge/minecraft/Install/runtime");
		r.add(home + "/Documents/curseforge/minecraft/Install/runtime");
		return r.toArray(new String[0]);
	}

	private static Path javaExe(Path home) {
		for (String s : new String[]{"bin/javaw.exe", "bin/java.exe", "bin/java", "Contents/Home/bin/java"}) {
			Path p = home.resolve(s);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	/** The major version a java executable reports (8, 17, 21...), or 0 when it can't be asked. */
	private static int majorOf(Path exe) {
		try {
			Path probe = exe;
			if (exe.getFileName().toString().equals("javaw.exe") && Files.isRegularFile(exe.resolveSibling("java.exe"))) {
				probe = exe.resolveSibling("java.exe");
			}
			Process pr = new ProcessBuilder(probe.toString(), "-version").redirectErrorStream(true).start();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = pr.getInputStream()) {
				copy(in, out);
			}
			String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
			int i = text.indexOf("version \"");
			if (i < 0) {
				return 0;
			}
			String v = text.substring(i + 9, text.indexOf('"', i + 9));
			String[] parts = v.split("[._-]");
			int first = Integer.parseInt(parts[0]);
			return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
		} catch (Exception e) {
			return 0;
		}
	}

	private static final String JAVA_DIR = "btainstaller-java-17";
	/** Downloads Java 17 into {@code dir} (replacing what is there) and returns its java executable. */
	private static Path downloadJava(Path dir) throws Exception {
		Path root = dir.getParent();
		String os = isWindows() ? "windows" : (isMac() ? "mac" : "linux");
		String arch = System.getProperty("os.arch", "").toLowerCase();
		String a = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : (arch.equals("x86") || arch.equals("i386") ? "x32" : "x64");
		Path archive = root.resolve(isWindows() ? "java.zip" : "java.tar.gz");
		fetch(String.format(ADOPTIUM, os, a), archive, null, null);
		deleteTree(dir);
		Files.createDirectories(dir);
		if (isWindows()) {
			unzip(archive, dir);
		} else {
			untar(archive, dir);
		}
		Files.deleteIfExists(archive);
		try (Stream<Path> s = Files.walk(dir, 6)) {
			List<Path> found = new ArrayList<Path>();
			s.filter(x -> (x.getFileName().toString().equals("java") || x.getFileName().toString().equals("javaw.exe"))
				&& x.getParent().getFileName().toString().equals("bin") && Files.isRegularFile(x)).forEach(found::add);
			for (Path x : found) {
				if (x.getFileName().toString().equals("javaw.exe")) {
					return x;
				}
			}
			if (!found.isEmpty()) {
				return found.get(0);
			}
		}
		throw new IOException("The downloaded Java could not be unpacked.");
	}

	private static void unzip(Path zip, Path to) throws IOException {
		try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				Path dest = safe(to, e.getName());
				if (e.isDirectory()) {
					Files.createDirectories(dest);
				} else {
					Files.createDirectories(dest.getParent());
					Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Minimal tar.gz reader: files, folders, symlinks, long names (GNU 'L' and pax 'x'), executable bits. */
	private static void untar(Path tgz, Path to) throws IOException {
		try (InputStream in = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tgz))))) {
			byte[] h = new byte[512];
			String longName = null;
			while (readFully(in, h)) {
				if (h[0] == 0) {
					break;
				}
				String name = str(h, 0, 100);
				long size = octal(h, 124, 12);
				char type = (char) h[156];
				String prefix = str(h, 345, 155);
				if (!prefix.isEmpty()) {
					name = prefix + "/" + name;
				}
				if (type == 'L' || type == 'x') {
					byte[] data = new byte[(int) size];
					readFully(in, data);
					skipPad(in, size);
					String s = new String(data, StandardCharsets.UTF_8);
					if (type == 'L') {
						longName = s.trim().replace("\0", "");
					} else {
						for (String line : s.split("\n")) {
							int sp = line.indexOf(' ');
							int eq = line.indexOf('=');
							if (sp > 0 && eq > sp && line.substring(sp + 1, eq).equals("path")) {
								longName = line.substring(eq + 1);
							}
						}
					}
					continue;
				}
				if (longName != null) {
					name = longName;
					longName = null;
				}
				Path dest = safe(to, name);
				if (type == '5') {
					Files.createDirectories(dest);
				} else if (type == '2') {
					Files.createDirectories(dest.getParent());
					try {
						Files.deleteIfExists(dest);
						Files.createSymbolicLink(dest, Paths.get(str(h, 157, 100)));
					} catch (Exception ignored) {
						// no symlinks here: the file is simply missing
					}
				} else if (type == '0' || type == 0) {
					Files.createDirectories(dest.getParent());
					try (OutputStream out = Files.newOutputStream(dest)) {
						long left = size;
						byte[] buf = new byte[8192];
						while (left > 0) {
							int n = in.read(buf, 0, (int) Math.min(buf.length, left));
							if (n < 0) {
								throw new IOException("Unexpected end of the Java archive.");
							}
							out.write(buf, 0, n);
							left -= n;
						}
					}
					skipPad(in, size);
					if ((octal(h, 100, 8) & 0111) != 0) {
						dest.toFile().setExecutable(true);
					}
				} else {
					skipFully(in, size + pad(size));
				}
			}
		}
	}

	private static Path safe(Path to, String name) throws IOException {
		Path dest = to.resolve(name).normalize();
		if (!dest.startsWith(to.normalize())) {
			throw new IOException("Unsafe path in archive: " + name);
		}
		return dest;
	}

	private static String str(byte[] b, int off, int len) {
		int end = off;
		while (end < off + len && b[end] != 0) {
			end++;
		}
		return new String(b, off, end - off, StandardCharsets.UTF_8);
	}

	private static long octal(byte[] b, int off, int len) {
		String s = str(b, off, len).trim();
		return s.isEmpty() ? 0 : Long.parseLong(s, 8);
	}

	private static long pad(long size) {
		return (512 - size % 512) % 512;
	}

	private static void skipPad(InputStream in, long size) throws IOException {
		skipFully(in, pad(size));
	}

	private static void skipFully(InputStream in, long n) throws IOException {
		byte[] buf = new byte[8192];
		while (n > 0) {
			int r = in.read(buf, 0, (int) Math.min(buf.length, n));
			if (r < 0) {
				throw new IOException("Unexpected end of the Java archive.");
			}
			n -= r;
		}
	}

	private static boolean readFully(InputStream in, byte[] b) throws IOException {
		int off = 0;
		while (off < b.length) {
			int n = in.read(b, off, b.length - off);
			if (n < 0) {
				return off > 0 && off == b.length;
			}
			off += n;
		}
		return true;
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			List<Path> all = new ArrayList<Path>();
			s.forEach(all::add);
			for (int i = all.size() - 1; i >= 0; i--) {
				Files.deleteIfExists(all.get(i));
			}
		}
	}

	// ---------------------------------------------------------------- downloading

	private static byte[] download(String url) throws IOException {
		HttpURLConnection c = open(url);
		try (InputStream in = c.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			copy(in, out);
			return out.toByteArray();
		}
	}

	/** Downloads to dest (skipped when it is already there and matches the checksum), retrying a few times. */
	private static void fetch(String url, Path dest, String sha1, Progress unused) throws Exception {
		if (Files.isRegularFile(dest) && (sha1 == null || sha1.equalsIgnoreCase(sha1(dest)))) {
			if (sha1 != null) {
				return;
			}
		}
		Files.createDirectories(dest.getParent());
		IOException last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			Path part = dest.resolveSibling(dest.getFileName() + ".part");
			try {
				HttpURLConnection c = open(url);
				try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
					copy(in, out);
				}
				if (sha1 != null && !sha1.equalsIgnoreCase(sha1(part))) {
					throw new IOException("Checksum mismatch for " + url);
				}
				Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				last = e;
				Files.deleteIfExists(part);
			}
		}
		throw last;
	}

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(15000);
		c.setReadTimeout(60000);
		c.setInstanceFollowRedirects(true);
		c.setRequestProperty("User-Agent", "BTA-Installer");
		if (c.getResponseCode() != 200) {
			throw new IOException("Could not download " + url + " (HTTP " + c.getResponseCode() + ")");
		}
		return c;
	}

	private static String sha1(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[16384];
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
	}

	private static String join(List<String> parts, String sep) {
		StringBuilder sb = new StringBuilder();
		for (String s : parts) {
			if (sb.length() > 0) {
				sb.append(sep);
			}
			sb.append(s);
		}
		return sb.toString();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	// ---------------------------------------------------------------- a small JSON reader (objects, arrays, strings, numbers, true/false/null)

	@SuppressWarnings("unchecked")
	private static Map<String, Object> obj(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object o) {
		return o == null ? new ArrayList<Object>() : (List<Object>) o;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> parse(byte[] json) {
		return (Map<String, Object>) new Json(new String(json, StandardCharsets.UTF_8)).value();
	}

	private static final class Json {
		private final String s;
		private int i;

		Json(String s) {
			this.s = s;
		}

		Object value() {
			ws();
			char c = s.charAt(i);
			if (c == '{') {
				i++;
				Map<String, Object> m = new LinkedHashMap<String, Object>();
				ws();
				if (s.charAt(i) == '}') {
					i++;
					return m;
				}
				while (true) {
					ws();
					String k = string();
					ws();
					i++; // :
					m.put(k, value());
					ws();
					if (s.charAt(i++) == '}') {
						return m;
					}
				}
			}
			if (c == '[') {
				i++;
				List<Object> l = new ArrayList<Object>();
				ws();
				if (s.charAt(i) == ']') {
					i++;
					return l;
				}
				while (true) {
					l.add(value());
					ws();
					if (s.charAt(i++) == ']') {
						return l;
					}
				}
			}
			if (c == '"') {
				return string();
			}
			int start = i;
			while (i < s.length() && ",]} \t\r\n".indexOf(s.charAt(i)) < 0) {
				i++;
			}
			String t = s.substring(start, i);
			if (t.equals("true")) {
				return Boolean.TRUE;
			}
			if (t.equals("false")) {
				return Boolean.FALSE;
			}
			if (t.equals("null")) {
				return null;
			}
			return Double.valueOf(t);
		}

		private String string() {
			StringBuilder sb = new StringBuilder();
			i++; // opening quote
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					char e = s.charAt(i++);
					switch (e) {
						case 'n': sb.append('\n'); break;
						case 't': sb.append('\t'); break;
						case 'r': sb.append('\r'); break;
						case 'b': sb.append('\b'); break;
						case 'f': sb.append('\f'); break;
						case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
						default: sb.append(e);
					}
				} else {
					sb.append(c);
				}
			}
		}

		private void ws() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}
	}
}
