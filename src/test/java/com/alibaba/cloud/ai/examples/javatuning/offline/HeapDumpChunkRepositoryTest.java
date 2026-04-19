package com.alibaba.cloud.ai.examples.javatuning.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
