package com.alibaba.cloud.ai.examples.javatuning.discovery;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutor;

public class CommandJavaProcessDiscoveryService implements JavaProcessDiscoveryService {

	private static final List<String> JPS_COMMAND = List.of("jps", "-lvm");

	private final CommandExecutor executor;

	private final ProcessDisplayNameResolver resolver;

	public CommandJavaProcessDiscoveryService(CommandExecutor executor, ProcessDisplayNameResolver resolver) {
		this.executor = executor;
		this.resolver = resolver;
	}

	@Override
	public List<JavaApplicationDescriptor> listJavaApplications() {
		String output = executor.run(JPS_COMMAND);
		List<JavaApplicationDescriptor> descriptors = new ArrayList<>();
		for (String line : output.lines().toList()) {
			if (line.isBlank()) {
				continue;
			}
			String[] parts = line.split("\\s+", 3);
			long pid = Long.parseLong(parts[0]);
			String mainClassOrJar = parts.length > 1 ? parts[1] : "";
			String commandLine = parts.length > 1 ? line.substring(line.indexOf(' ') + 1) : "";
			boolean springBootHint = commandLine.contains("spring");
			descriptors.add(new JavaApplicationDescriptor(pid, resolver.resolveDisplayName(commandLine), mainClassOrJar,
					commandLine, DiscoveryHints.workDirHint(commandLine), "", "",
					DiscoveryHints.applicationTypeHint(mainClassOrJar, springBootHint), springBootHint,
					resolver.resolveProfiles(commandLine), DiscoveryHints.portHints(commandLine), "jps", "medium"));
		}
		return descriptors;
	}

}
