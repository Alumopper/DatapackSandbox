package moe.afox.dpsandbox.core

/** Synchronous execution boundaries. Observers may suspend the owning execution thread. */
interface SandboxExecutionObserver {
    fun enterFunction(id: ResourceLocation, location: SourceLocation?, context: ExecutionContext) {}

    fun beforeCommand(command: String, location: SourceLocation?, context: ExecutionContext) {}

    fun exitFunction() {}
}
