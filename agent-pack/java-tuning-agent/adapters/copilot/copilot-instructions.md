# Java tuning agent Copilot instructions

When a user asks for JVM tuning, GC/memory/leak diagnosis, Java process analysis, or offline heap/import analysis, use the `java-tuning-agent` MCP tools when available.

Critical rules:

- Do not skip `listJavaApps` before live JVM analysis.
- Do not guess across ambiguous PIDs; ask the user to choose.
- Run `inspectJvmRuntime` before privileged evidence collection.
- Do not call `collectMemoryGcEvidence` until the user has chosen the step-3 evidence scope, including the explicit snapshot-only option.
- Require explicit approval before class histogram, thread dump, heap dump, or JFR.
- Use `generateTuningAdviceFromEvidence` with the exact evidence pack returned by `collectMemoryGcEvidence`; do not recollect the same evidence.
- Render `formattedSummary` as Markdown in the chat body. Do not wrap the whole summary in an outer code fence.
- For Copilot cloud/coding-agent environments, live local JVM inspection only sees JVMs inside that agent environment. Use offline/import tools for production bundles or diagnostics exported from another machine.
