package com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts a classic two-lock cycle once per JVM so {@code jcmd Thread.print} can show a Java-level deadlock.
 */
@Component
@ConditionalOnProperty(name = "memory-leak-demo.features.deadlock-demo", havingValue = "true", matchIfMissing = true)
public class DeadlockDemoTrigger {

	private static final Object LOCK_A = new Object();

	private static final Object LOCK_B = new Object();

	private final AtomicBoolean fired = new AtomicBoolean();

	public boolean alreadyTriggered() {
		return fired.get();
	}

	/**
	 * @return true if this call started the demo threads; false if a previous call already did.
	 */
	public boolean triggerOnce() {
		if (!fired.compareAndSet(false, true)) {
			return false;
		}
		Thread t1 = new Thread(() -> holdThenWait(LOCK_A, LOCK_B), "memleak-deadlock-t1");
		Thread t2 = new Thread(() -> holdThenWait(LOCK_B, LOCK_A), "memleak-deadlock-t2");
		t1.setDaemon(true);
		t2.setDaemon(true);
		t1.start();
		t2.start();
		return true;
	}

	private static void holdThenWait(Object first, Object second) {
		synchronized (first) {
			sleepBriefly();
			synchronized (second) {
				// unreachable once deadlocked
			}
		}
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(80L);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
