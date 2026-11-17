package moe.afox.dpsandbox.core

/** Stores belong to a command branch. A function body starts with fresh callbacks. */
internal class CommandCallbacks(
    val stores: List<(Boolean, Int) -> Unit> = emptyList(),
    val onReturn: ((Boolean, Int?) -> Unit)? = null,
) {
    fun complete(success: Boolean, value: Int?) {
        if (value != null) stores.forEach { it(success, value) }
        onReturn?.invoke(success, value)
    }
}

internal class ReturnSignal(
    val value: Int,
    val success: Boolean = true,
) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** An in-game failure that produces (false, 0) when another command consumes its result. */
internal class CommandFailure(
    message: String,
    location: SourceLocation? = null,
) : SandboxException(DiagnosticCode.COMMAND_ERROR, message, location)

internal fun DatapackSandbox.executeReturn(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
    callbacks: CommandCallbacks?,
): Nothing {
    requireSize(tokens, 2, "return <value|fail|run ...>", location)
    val result =
        when (tokens[1].text) {
            "fail" -> ReturnSignal(0, false)
            "run" -> {
                val rest = CommandTokenizer.tailAfter(command, tokens[1])
                if (rest.isBlank()) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: return run <command>", location)
                try {
                    executeOne(
                        rest,
                        location,
                        context,
                        CommandCallbacks(onReturn = { success, value ->
                            throw ReturnSignal(value ?: 0, value != null && success)
                        }),
                    )
                    ReturnSignal(0, false)
                } catch (signal: ReturnSignal) {
                    signal
                }
            }
            else -> ReturnSignal(parseInt(tokens[1].text, "return value", location))
        }
    callbacks?.complete(result.success, result.value)
    throw result
}
