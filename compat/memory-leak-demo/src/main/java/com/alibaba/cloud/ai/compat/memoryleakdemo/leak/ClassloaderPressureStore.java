package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;
import org.springframework.stereotype.Component;

@Component
public class ClassloaderPressureStore {

	private final List<Object> retainedProxies = new ArrayList<>();

	private final List<ClassLoader> retainedLoaders = new ArrayList<>();

	private final Deque<RecentAllocationView> recentAllocations = new ArrayDeque<>(10);

	private long allocationRequests;

	private long generatedProxyClasses;

	public synchronized ClassloaderSummary allocate(int loaders, String tag) {
		for (int index = 0; index < loaders; index++) {
			ClassLoader loader = new DemoProxyClassLoader(Thread.currentThread().getContextClassLoader());
			Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { Runnable.class }, (target, method, args) -> null);
			retainedLoaders.add(loader);
			retainedProxies.add(proxy);
			generatedProxyClasses++;
		}
		allocationRequests++;
		recentAllocations.addFirst(new RecentAllocationView(tag, loaders, 0, 0));
		while (recentAllocations.size() > 10) {
			recentAllocations.removeLast();
		}
		return currentSummary();
	}

	public synchronized ClassloaderSummary currentSummary() {
		return new ClassloaderSummary(retainedLoaders.size(), generatedProxyClasses, allocationRequests,
				List.copyOf(recentAllocations), 0);
	}

	public synchronized ClassloaderSummary clear() {
		long clearedLoaders = retainedLoaders.size();
		retainedLoaders.clear();
		retainedProxies.clear();
		return new ClassloaderSummary(0, generatedProxyClasses, allocationRequests, List.copyOf(recentAllocations),
				clearedLoaders);
	}

	private static final class DemoProxyClassLoader extends ClassLoader {

		private DemoProxyClassLoader(ClassLoader parent) {
			super(parent);
		}

	}

}
