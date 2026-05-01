package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores heap dump uploads as ordered chunks under a base directory and can
 * concatenate them into a single file with SHA-256 / size verification.
 */
public class HeapDumpChunkRepository {

	private final Path baseDir;
	private final Duration uploadTtl;
	private final boolean cleanupFinalized;
	private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();

	private static final class UploadState {
		final int totalChunks;
		final Path uploadDir;

		UploadState(int totalChunks, Path uploadDir) {
			this.totalChunks = totalChunks;
			this.uploadDir = uploadDir;
		}
	}

	public HeapDumpChunkRepository(Path baseDir) {
		this(baseDir, Duration.ofSeconds(86_400), false);
	}

	public HeapDumpChunkRepository(Path baseDir, Duration uploadTtl, boolean cleanupFinalized) {
		this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
		this.uploadTtl = uploadTtl == null || uploadTtl.isNegative() ? Duration.ofSeconds(86_400) : uploadTtl;
		this.cleanupFinalized = cleanupFinalized;
	}

	/**
	 * Starts a new upload session; chunk indices are {@code 0 .. totalChunks-1}.
	 */
	public String createUpload(int totalChunks) {
		if (totalChunks <= 0) {
			throw new IllegalArgumentException("totalChunks must be positive, got: " + totalChunks);
		}
		cleanupExpiredUploads(Instant.now());
		String uploadId = UUID.randomUUID().toString();
		Path uploadDir = baseDir.resolve("uploads").resolve(uploadId);
		try {
			Files.createDirectories(uploadDir);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create upload directory: " + uploadDir, e);
		}
		if (uploads.putIfAbsent(uploadId, new UploadState(totalChunks, uploadDir)) != null) {
			// Practically impossible with random UUID; keeps id uniquely tied to one upload.
			try {
				deleteDirectory(uploadDir);
			} catch (IOException ignored) {
				// best effort
			}
			throw new IllegalStateException("Upload id collision: " + uploadId);
		}
		return uploadId;
	}

	public HeapDumpCleanupResult cleanupExpiredUploads(Instant now) {
		Instant effectiveNow = now == null ? Instant.now() : now;
		Instant cutoff = effectiveNow.minus(uploadTtl);
		List<String> warnings = new ArrayList<>();
		CleanupCounters counters = new CleanupCounters();
		cleanupIncompleteUploads(cutoff, counters, warnings);
		if (cleanupFinalized) {
			cleanupFinalizedDumps(cutoff, counters, warnings);
		}
		return new HeapDumpCleanupResult(counters.deletedUploadCount, counters.deletedBytes,
				counters.retainedUploadCount, warnings);
	}

	/**
	 * Persists one chunk for the given upload. {@code chunkIndex} must be in range.
	 */
	public void appendChunk(String uploadId, int chunkIndex, byte[] data) {
		UploadState state = uploads.get(uploadId);
		if (state == null) {
			throw new IllegalArgumentException("Unknown upload id (or already finalized): " + uploadId);
		}
		if (chunkIndex < 0 || chunkIndex >= state.totalChunks) {
			throw new IllegalArgumentException(
					"chunkIndex out of range: " + chunkIndex + " (valid: 0.." + (state.totalChunks - 1) + ")");
		}
		Path chunkFile = state.uploadDir.resolve("chunk-" + chunkIndex);
		byte[] payload = data != null ? data : new byte[0];
		try {
			Files.write(chunkFile, payload);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write chunk file: " + chunkFile, e);
		}
	}

	/**
	 * Concatenates all chunks in order, writes {@code heap-&lt;uploadId&gt;.hprof} under {@code baseDir/final/},
	 * verifies size and SHA-256 (hex), then removes chunk storage for this upload.
	 */
	public Path finalize(String uploadId, String expectedSha256Hex, long expectedSizeBytes) {
		if (expectedSha256Hex == null || expectedSha256Hex.isBlank()) {
			throw new IllegalArgumentException("expectedSha256Hex must not be blank");
		}
		UploadState state = uploads.remove(uploadId);
		if (state == null) {
			throw new IllegalArgumentException("Unknown upload id (or already finalized): " + uploadId);
		}

		Path finalDir = baseDir.resolve("final");
		Path target = finalDir.resolve("heap-" + uploadId + ".hprof");
		Path partial = finalDir.resolve("heap-" + uploadId + ".hprof.partial");

		try {
			Files.createDirectories(finalDir);
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (OutputStream out = Files.newOutputStream(partial)) {
				for (int i = 0; i < state.totalChunks; i++) {
					Path chunkFile = state.uploadDir.resolve("chunk-" + i);
					if (!Files.isRegularFile(chunkFile)) {
						throw new IllegalArgumentException(
								"Missing chunk " + i + " for upload " + uploadId + " (expected "
										+ state.totalChunks + " chunks)");
					}
					byte[] bytes = Files.readAllBytes(chunkFile);
					md.update(bytes);
					out.write(bytes);
				}
			}

			long actualSize = Files.size(partial);
			if (actualSize != expectedSizeBytes) {
				Files.deleteIfExists(partial);
				throw new IllegalArgumentException(
						"Size mismatch for upload " + uploadId + ": expected " + expectedSizeBytes
								+ " bytes but got " + actualSize);
			}

			byte[] digest = md.digest();
			String actualHex = HexFormat.of().formatHex(digest);
			if (!actualHex.equalsIgnoreCase(expectedSha256Hex.trim())) {
				Files.deleteIfExists(partial);
				throw new IllegalArgumentException(
						"SHA-256 mismatch for upload " + uploadId + ": expected " + expectedSha256Hex
								+ " but got " + actualHex);
			}

			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			deleteDirectory(state.uploadDir);
			return target;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(partial);
			} catch (IOException ignored) {
				// best effort
			}
			throw new IllegalStateException("Failed to finalize upload " + uploadId, e);
		}
	}

	private static void deleteDirectory(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			var paths = stream.sorted((a, b) -> b.compareTo(a)).toList();
			for (Path p : paths) {
				Files.deleteIfExists(p);
			}
		}
	}

	private void cleanupIncompleteUploads(Instant cutoff, CleanupCounters counters, List<String> warnings) {
		Path uploadsDir = baseDir.resolve("uploads");
		if (!Files.isDirectory(uploadsDir)) {
			return;
		}
		Set<String> seen = new HashSet<>();
		try (var stream = Files.list(uploadsDir)) {
			for (Path uploadDir : stream.filter(Files::isDirectory).toList()) {
				String uploadId = uploadDir.getFileName().toString();
				seen.add(uploadId);
				if (lastModified(uploadDir).isAfter(cutoff)) {
					counters.retainedUploadCount++;
					continue;
				}
				long bytes = directorySize(uploadDir);
				try {
					deleteDirectory(uploadDir);
					uploads.remove(uploadId);
					counters.deletedUploadCount++;
					counters.deletedBytes += bytes;
				}
				catch (IOException ex) {
					warnings.add("Unable to delete expired heap dump upload " + uploadId + ": " + ex.getMessage());
					counters.retainedUploadCount++;
				}
			}
		}
		catch (IOException ex) {
			warnings.add("Unable to scan heap dump upload directory: " + ex.getMessage());
		}
		for (String uploadId : uploads.keySet()) {
			if (!seen.contains(uploadId)) {
				uploads.remove(uploadId);
			}
		}
	}

	private void cleanupFinalizedDumps(Instant cutoff, CleanupCounters counters, List<String> warnings) {
		Path finalDir = baseDir.resolve("final");
		if (!Files.isDirectory(finalDir)) {
			return;
		}
		try (var stream = Files.list(finalDir)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				if (lastModified(file).isAfter(cutoff)) {
					counters.retainedUploadCount++;
					continue;
				}
				long bytes = safeSize(file);
				try {
					Files.deleteIfExists(file);
					counters.deletedUploadCount++;
					counters.deletedBytes += bytes;
				}
				catch (IOException ex) {
					warnings.add("Unable to delete finalized heap dump " + file + ": " + ex.getMessage());
					counters.retainedUploadCount++;
				}
			}
		}
		catch (IOException ex) {
			warnings.add("Unable to scan finalized heap dump directory: " + ex.getMessage());
		}
	}

	private static Instant lastModified(Path root) {
		try (var stream = Files.walk(root)) {
			return stream.map(HeapDumpChunkRepository::safeLastModified)
				.max(Instant::compareTo)
				.orElse(Instant.EPOCH);
		}
		catch (IOException ex) {
			return Instant.EPOCH;
		}
	}

	private static Instant safeLastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toInstant();
		}
		catch (IOException ex) {
			return Instant.EPOCH;
		}
	}

	private static long directorySize(Path root) {
		try (var stream = Files.walk(root)) {
			return stream.filter(Files::isRegularFile).mapToLong(HeapDumpChunkRepository::safeSize).sum();
		}
		catch (IOException ex) {
			return 0L;
		}
	}

	private static long safeSize(Path path) {
		try {
			return Files.size(path);
		}
		catch (IOException ex) {
			return 0L;
		}
	}

	private static final class CleanupCounters {
		long deletedUploadCount;
		long deletedBytes;
		long retainedUploadCount;
	}
}
