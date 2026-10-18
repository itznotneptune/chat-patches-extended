// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import io.github.itznotneptune.cpextended.dev.DevHooks;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * File access for {@code cpextended.json} (P14, Q36). Change detection uses content (SHA-256 and length), never the
 * modification time alone. Saves write a temporary file in the same directory, flush it, re-check the target's content
 * immediately before the {@code ATOMIC_MOVE}, then replace. Where atomic replacement isn't supported, ordinary saves
 * copy the current file to {@code .bak} and write directly.
 *
 * <p>Residual race (documented, not eliminated): an external write landing between the final content check and the
 * rename (or during the fallback's direct write) is overwritten. Java has no portable compare-and-swap rename.
 */
public final class ConfigStorage {
	/** Content identity of one version of the file; {@code exists=false} for a missing file. */
	public record Fingerprint(boolean exists, long length, String sha256) {
		public static final Fingerprint MISSING = new Fingerprint(false, -1, "");

		static Fingerprint of(byte[] bytes) {
			return new Fingerprint(true, bytes.length, ConfigStorage.sha256(bytes));
		}
	}

	/** A read of the file: bytes (null if missing) or an I/O failure. */
	public record Read(@Nullable byte[] bytes, @Nullable IOException error) {
		public boolean missing() { return bytes == null && error == null; }
		public boolean unreadable() { return error != null; }
		public Fingerprint fingerprint() { return bytes == null ? Fingerprint.MISSING : Fingerprint.of(bytes); }
	}

	public enum SaveStatus { SAVED_ATOMIC, SAVED_FALLBACK, CHANGED_ON_DISK, UNREADABLE, FAILED }

	public record SaveResult(SaveStatus status, @Nullable Fingerprint written, @Nullable String detail) {}

	private final Path file;

	// Development-only fault injection (verification of V-21e and V-62); inert unless DevHooks.isDev().
	private volatile boolean devNoAtomicMove;
	private volatile int devTransientFailures;
	private volatile @Nullable Runnable devBeforeFinalCheck;
	private volatile @Nullable Runnable devBetweenCheckAndReplace;
	private volatile long lastCheckToReplaceNanos = -1;
	// Q36 two-phase writes
	private volatile Boolean atomicSupported; // null until probed; false once unsupported for the session
	private volatile boolean devProbeUnsupported;
	private volatile boolean devFailTempWrite;
	private volatile int devRenameTransient;
	private volatile boolean devRenamePermanent;
	private volatile boolean devRenameUnsupported;

	public ConfigStorage(Path file) {
		this.file = file;
	}

	public Path file() { return file; }
	public Path backup() { return file.resolveSibling(file.getFileName() + ".bak"); }

	public Read read() {
		try {
			return new Read(Files.readAllBytes(file), null);
		} catch (NoSuchFileException e) {
			return new Read(null, null);
		} catch (IOException e) {
			return new Read(null, e);
		}
	}

	public Read readBackup() {
		try {
			return new Read(Files.readAllBytes(backup()), null);
		} catch (NoSuchFileException e) {
			return new Read(null, null);
		} catch (IOException e) {
			return new Read(null, e);
		}
	}

	/** Byte copy of an unparseable original as {@code cpextended.json.corrupt-<timestamp>}; the original stays in place. */
	public Path preserveCorrupt(byte[] original) throws IOException {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path p = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
		for (int i = 2; Files.exists(p); i++) p = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp + "-" + i);
		Files.write(p, original, StandardOpenOption.CREATE_NEW);
		return p;
	}

	/**
	 * Saves {@code content} if the file still has the content {@code expected} (the last version Extended loaded or
	 * wrote). One retry on an I/O failure (e.g. a transient lock), then FAILED.
	 */
	public SaveResult save(byte[] content, Fingerprint expected) {
		SaveResult r = saveOnce(content, expected);
		if (r.status() == SaveStatus.FAILED) r = saveOnce(content, expected);
		return r;
	}

	private SaveResult saveOnce(byte[] content, Fingerprint expected) {
		Path tmp = null;
		try {
			Files.createDirectories(file.getParent());
			maybeTransientFailure();
			// Write and flush the complete new version first, so the check below sits right before the replace.
			tmp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buf = ByteBuffer.wrap(content);
				while (buf.hasRemaining()) ch.write(buf);
				ch.force(true);
			}
			dev(devBeforeFinalCheck);
			SaveResult check = check(expected);
			if (check != null) return check;
			long t0 = System.nanoTime();
			dev(devBetweenCheckAndReplace);
			try {
				if (devNoAtomicMove && DevHooks.isDev()) throw new AtomicMoveNotSupportedException(tmp.toString(), file.toString(), "dev: simulated");
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				lastCheckToReplaceNanos = System.nanoTime() - t0;
				tmp = null;
				return new SaveResult(SaveStatus.SAVED_ATOMIC, Fingerprint.of(content), null);
			} catch (AtomicMoveNotSupportedException e) {
				if (!devNoAtomicMove) atomicSupported = false;
				return ordinarySave(content, expected);
			}
		} catch (IOException e) {
			return new SaveResult(SaveStatus.FAILED, null, e.toString());
		} finally {
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// a leftover temp file is harmless and never read
				}
			}
		}
	}

	/** Q36 ordinary-save fallback: copy the current file to .bak, write directly, flush; .bak kept until the next save. */
	private SaveResult ordinarySave(byte[] content, Fingerprint expected) throws IOException {
		SaveResult check = check(expected);
		if (check != null) return check;
		if (Files.exists(file)) Files.copy(file, backup(), StandardCopyOption.REPLACE_EXISTING);
		long t0 = System.nanoTime();
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buf = ByteBuffer.wrap(content);
			while (buf.hasRemaining()) ch.write(buf);
			ch.force(true);
		}
		lastCheckToReplaceNanos = System.nanoTime() - t0;
		return new SaveResult(SaveStatus.SAVED_FALLBACK, Fingerprint.of(content), null);
	}

	/** Null when the file still matches {@code expected}; otherwise why the save must not proceed. */
	private @Nullable SaveResult check(Fingerprint expected) {
		Read now = read();
		if (now.unreadable()) return new SaveResult(SaveStatus.UNREADABLE, null, now.error().toString());
		Fingerprint fp = now.fingerprint();
		if (!fp.equals(expected)) return new SaveResult(SaveStatus.CHANGED_ON_DISK, null, fp.exists() ? "content changed" : "file was removed");
		return null;
	}

	private void maybeTransientFailure() throws IOException {
		if (devTransientFailures > 0 && DevHooks.isDev()) {
			devTransientFailures--;
			throw new IOException("dev: simulated transient lock");
		}
	}

	private static void dev(@Nullable Runnable hook) {
		if (hook != null && DevHooks.isDev()) hook.run();
	}

	// ---------------------------------------------------------------- Q36 two-phase replacement (orphan restoration)

	/** A flushed temp file holding the next version, ready to replace {@code cpextended.json}. */
	public record Prepared(Path tmp, byte[] content, Fingerprint expected) {}

	public enum PrepareStatus { OK, CHANGED_ON_DISK, UNREADABLE, IO_FAILED, ATOMIC_UNSUPPORTED }

	public record PrepareResult(PrepareStatus status, @Nullable Prepared prepared, @Nullable String detail) {}

	public enum ReplaceStatus { REPLACED, CHANGED_ON_DISK, UNREADABLE, UNSUPPORTED, FAILED }

	public record ReplaceResult(ReplaceStatus status, @Nullable Fingerprint written, @Nullable String detail) {}

	/**
	 * Whether atomic replacement works in the config directory: a cached probe (temp file moved over another temp file
	 * with {@code ATOMIC_MOVE, REPLACE_EXISTING}), and not marked unsupported since. A failing probe I/O counts as
	 * unsupported for this call but isn't cached, so a transient problem doesn't disable it for the session.
	 */
	public boolean atomicReplaceAvailable() {
		Boolean known = atomicSupported;
		if (known != null) return known;
		if ((devProbeUnsupported || devNoAtomicMove) && DevHooks.isDev()) return false;
		Path a = null, b = null;
		try {
			Files.createDirectories(file.getParent());
			a = Files.createTempFile(file.getParent(), file.getFileName() + ".probe.", ".tmp");
			b = Files.createTempFile(file.getParent(), file.getFileName() + ".probe.", ".tmp");
			Files.move(a, b, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			a = null;
			atomicSupported = true;
			return true;
		} catch (AtomicMoveNotSupportedException e) {
			atomicSupported = false;
			return false;
		} catch (IOException e) {
			return false;
		} finally {
			for (Path p : new Path[]{a, b}) {
				if (p == null) continue;
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// a leftover probe file is harmless and never read
				}
			}
		}
	}

	/**
	 * Prepare (Q36 step 5a/5b): checks the file still has the content {@code expected}, writes and flushes
	 * {@code content} to a temp file in the same directory, and confirms atomic replacement is available. Nothing live
	 * changes; on any failure the temp file is gone.
	 */
	public PrepareResult prepareAtomic(byte[] content, Fingerprint expected) {
		SaveResult check = check(expected);
		if (check != null) {
			return new PrepareResult(check.status() == SaveStatus.UNREADABLE ? PrepareStatus.UNREADABLE : PrepareStatus.CHANGED_ON_DISK, null, check.detail());
		}
		Path tmp = null;
		try {
			Files.createDirectories(file.getParent());
			tmp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
			if (devFailTempWrite && DevHooks.isDev()) throw new IOException("dev: simulated temp write failure (disk full)");
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buf = ByteBuffer.wrap(content);
				while (buf.hasRemaining()) ch.write(buf);
				ch.force(true);
			}
			if (!atomicReplaceAvailable()) {
				delete(tmp);
				return new PrepareResult(PrepareStatus.ATOMIC_UNSUPPORTED, null, "atomic file replacement isn't supported here");
			}
			return new PrepareResult(PrepareStatus.OK, new Prepared(tmp, content, expected), null);
		} catch (IOException e) {
			delete(tmp);
			return new PrepareResult(PrepareStatus.IO_FAILED, null, e.toString());
		}
	}

	/**
	 * Commit's file step (Q36 step 6b): the P14 content check immediately before the rename, then
	 * {@code ATOMIC_MOVE, REPLACE_EXISTING}. A transient I/O failure gets one retry after a short delay;
	 * {@code AtomicMoveNotSupportedException} marks atomic replacement unsupported for the session. Never falls back to
	 * a non-atomic write. On failure the temp file is deleted and the file is untouched.
	 */
	public ReplaceResult replaceAtomic(Prepared p) {
		for (int attempt = 0; attempt < 2; attempt++) {
			SaveResult check = check(p.expected());
			if (check != null) {
				delete(p.tmp());
				return new ReplaceResult(check.status() == SaveStatus.UNREADABLE ? ReplaceStatus.UNREADABLE : ReplaceStatus.CHANGED_ON_DISK, null, check.detail());
			}
			try {
				if (DevHooks.isDev()) {
					if (devRenameUnsupported) throw new AtomicMoveNotSupportedException(p.tmp().toString(), file.toString(), "dev: simulated");
					if (devRenamePermanent) throw new java.nio.file.AccessDeniedException(file.toString(), null, "dev: simulated permanent failure");
					if (devRenameTransient > 0) { devRenameTransient--; throw new java.nio.file.FileSystemException(file.toString(), null, "dev: simulated transient lock"); }
				}
				Files.move(p.tmp(), file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return new ReplaceResult(ReplaceStatus.REPLACED, Fingerprint.of(p.content()), null);
			} catch (AtomicMoveNotSupportedException e) {
				atomicSupported = false;
				delete(p.tmp());
				return new ReplaceResult(ReplaceStatus.UNSUPPORTED, null, e.toString());
			} catch (IOException e) {
				if (attempt == 0) {
					try {
						Thread.sleep(100); // e.g. a Windows sharing violation from an antivirus or indexer
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
					continue;
				}
				delete(p.tmp());
				return new ReplaceResult(ReplaceStatus.FAILED, null, e.toString());
			}
		}
		delete(p.tmp());
		return new ReplaceResult(ReplaceStatus.FAILED, null, "unreachable");
	}

	/**
	 * Deletes temp files a killed process left behind ({@code cpextended.json.<random>.tmp}, probe files). They're never
	 * read; called at startup. Returns how many were removed.
	 */
	public int deleteStaleTemps() {
		int n = 0;
		try (var files = Files.list(file.getParent())) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();
				if (name.startsWith(file.getFileName() + ".") && name.endsWith(".tmp")) {
					delete(p);
					n++;
				}
			}
		} catch (IOException ignored) {
			// nothing to clean, or the directory can't be listed: harmless
		}
		return n;
	}

	/** Discards a prepared temp file (refusal or cancellation). */
	public void abort(@Nullable Prepared p) {
		if (p != null) delete(p.tmp());
	}

	private static void delete(@Nullable Path p) {
		if (p == null) return;
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
			// a leftover temp file is harmless and never read
		}
	}

	public void devSetProbeUnsupported(boolean v) { DevHooks.requireDev(); devProbeUnsupported = v; atomicSupported = null; }
	public void devSetFailTempWrite(boolean v) { DevHooks.requireDev(); devFailTempWrite = v; }
	public void devSetRenameFailures(int transientCount, boolean permanent, boolean unsupported) {
		DevHooks.requireDev();
		devRenameTransient = transientCount;
		devRenamePermanent = permanent;
		devRenameUnsupported = unsupported;
	}
	/** Development builds only: forget the cached probe result. */
	public void devResetAtomicProbe() { DevHooks.requireDev(); atomicSupported = null; }

	/** Duration of the last check-to-replace window (nanoseconds), for V-62. */
	public long lastCheckToReplaceNanos() { return lastCheckToReplaceNanos; }

	// ---------------------------------------------------------------- development-only hooks

	public void devSetNoAtomicMove(boolean v) { DevHooks.requireDev(); devNoAtomicMove = v; }
	public void devSetTransientFailures(int n) { DevHooks.requireDev(); devTransientFailures = n; }
	public void devSetBeforeFinalCheck(@Nullable Runnable r) { DevHooks.requireDev(); devBeforeFinalCheck = r; }
	public void devSetBetweenCheckAndReplace(@Nullable Runnable r) { DevHooks.requireDev(); devBetweenCheckAndReplace = r; }

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean sameBytes(@Nullable byte[] a, @Nullable byte[] b) {
		return Arrays.equals(a, b);
	}

}
