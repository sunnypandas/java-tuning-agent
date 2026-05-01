package com.alibaba.cloud.ai.examples.javatuning.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapDumpChunkRepositoryTest {

	@Test
	void finalize_matches_sha256(@TempDir Path tempDir) throws Exception {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir);
		String uploadId = repo.createUpload(2);
		repo.appendChunk(uploadId, 0, "hello".getBytes(StandardCharsets.UTF_8));
		repo.appendChunk(uploadId, 1, "world".getBytes(StandardCharsets.UTF_8));

		Path out = repo.finalize(uploadId,
				"936a185caaa266bb9cbe981e9e05cb78cd732b0b3280eb944412bb6f8f8f07af",
				10L);

		assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo("helloworld");
		assertThat(Files.size(out)).isEqualTo(10L);
	}

	@Test
	void wrong_hash_throws_IllegalArgumentException(@TempDir Path tempDir) {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir);
		String uploadId = repo.createUpload(2);
		repo.appendChunk(uploadId, 0, "hello".getBytes(StandardCharsets.UTF_8));
		repo.appendChunk(uploadId, 1, "world".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> repo.finalize(uploadId,
				"0000000000000000000000000000000000000000000000000000000000000000",
				10L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SHA-256");
	}

	@Test
	void cleanupDeletesIncompleteUploadOlderThanTtl(@TempDir Path tempDir) throws Exception {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir, Duration.ofSeconds(60), false);
		String uploadId = repo.createUpload(2);
		repo.appendChunk(uploadId, 0, "stale".getBytes(StandardCharsets.UTF_8));
		Path uploadDir = tempDir.resolve("uploads").resolve(uploadId);
		Instant now = Instant.parse("2026-05-01T00:00:00Z");
		touchTree(uploadDir, now.minusSeconds(120));

		HeapDumpCleanupResult result = repo.cleanupExpiredUploads(now);

		assertThat(result.deletedUploadCount()).isEqualTo(1);
		assertThat(result.deletedBytes()).isEqualTo(5L);
		assertThat(result.retainedUploadCount()).isZero();
		assertThat(result.warnings()).isEmpty();
		assertThat(uploadDir).doesNotExist();
		assertThatThrownBy(() -> repo.appendChunk(uploadId, 1, "x".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown upload id");
	}

	@Test
	void cleanupKeepsRecentIncompleteUpload(@TempDir Path tempDir) throws Exception {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir, Duration.ofSeconds(60), false);
		String uploadId = repo.createUpload(2);
		repo.appendChunk(uploadId, 0, "recent".getBytes(StandardCharsets.UTF_8));
		Path uploadDir = tempDir.resolve("uploads").resolve(uploadId);
		Instant now = Instant.parse("2026-05-01T00:00:00Z");
		touchTree(uploadDir, now.minusSeconds(30));

		HeapDumpCleanupResult result = repo.cleanupExpiredUploads(now);

		assertThat(result.deletedUploadCount()).isZero();
		assertThat(result.deletedBytes()).isZero();
		assertThat(result.retainedUploadCount()).isEqualTo(1);
		assertThat(uploadDir).isDirectory();
	}

	@Test
	void cleanupKeepsFinalizedHeapDumpByDefault(@TempDir Path tempDir) throws Exception {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir, Duration.ofSeconds(60), false);
		String uploadId = repo.createUpload(1);
		repo.appendChunk(uploadId, 0, "heap".getBytes(StandardCharsets.UTF_8));
		Path out = repo.finalize(uploadId,
				"103a360f285151bfda3fb4009852c15084fd9bf997470c43c20eef413ed98898", 4L);
		Instant now = Instant.parse("2026-05-01T00:00:00Z");
		Files.setLastModifiedTime(out, FileTime.from(now.minusSeconds(120)));

		HeapDumpCleanupResult result = repo.cleanupExpiredUploads(now);

		assertThat(result.deletedUploadCount()).isZero();
		assertThat(out).isRegularFile();
	}

	@Test
	void cleanupDeletesFinalizedHeapDumpWhenConfigured(@TempDir Path tempDir) throws Exception {
		HeapDumpChunkRepository repo = new HeapDumpChunkRepository(tempDir, Duration.ofSeconds(60), true);
		String uploadId = repo.createUpload(1);
		repo.appendChunk(uploadId, 0, "heap".getBytes(StandardCharsets.UTF_8));
		Path out = repo.finalize(uploadId,
				"103a360f285151bfda3fb4009852c15084fd9bf997470c43c20eef413ed98898", 4L);
		Instant now = Instant.parse("2026-05-01T00:00:00Z");
		Files.setLastModifiedTime(out, FileTime.from(now.minusSeconds(120)));

		HeapDumpCleanupResult result = repo.cleanupExpiredUploads(now);

		assertThat(result.deletedUploadCount()).isEqualTo(1);
		assertThat(result.deletedBytes()).isEqualTo(4L);
		assertThat(out).doesNotExist();
	}

	private static void touchTree(Path root, Instant instant) throws Exception {
		try (var stream = Files.walk(root)) {
			for (Path path : stream.toList()) {
				Files.setLastModifiedTime(path, FileTime.from(instant));
			}
		}
	}
}
