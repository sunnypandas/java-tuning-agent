package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.time.Instant;

public record AllocationRecord(long sequence, String tag, Instant createdAt, byte[] payload) {

	public long sizeInBytes() {
		return payload.length;
	}

}
