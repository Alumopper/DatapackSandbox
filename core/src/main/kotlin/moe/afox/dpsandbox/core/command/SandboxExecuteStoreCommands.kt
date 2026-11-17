package moe.afox.dpsandbox.core.command

import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SourceLocation

/** Store callbacks also accept conditions or modifiers before the final command. */
internal fun DatapackSandbox.executeStoreChain(
    command: String,
    tokens: List<CommandToken>,
    index: Int,
    contexts: List<ExecutionContext>,
    location: SourceLocation?,
): Boolean {
    val storeIndices = mutableListOf<Int>()
    var next = index
    while (tokens.getOrNull(next)?.text == "store") {
        requireIndex(tokens, next + 2, "execute store <result|success> <target> ...", location)
        storeIndices += next + 1
        next +=
            when (tokens[next + 2].text) {
                "score", "bossbar" -> 5
                "storage", "entity" -> 7
                "block" -> 9
                else ->
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Unsupported execute store target '${tokens[next + 2].text}'",
                        location,
                    )
            }
    }
    requireIndex(tokens, next, "execute store must be followed by a condition or command", location)
    val rest =
        if (tokens[next].text == "run") {
            requireIndex(tokens, next + 1, "execute run <command>", location)
            CommandTokenizer.tailAfter(command, tokens[next])
        } else {
            "execute " + CommandTokenizer.tailFrom(command, tokens[next])
        }
    var anySuccess = false
    contexts.forEach { storeContext ->
        val commandsBefore = commandsExecuted
        val outputsBefore = world.outputs.size
        lastFunctionReturnValue = null
        val commandSuccess = executeOne(rest, location, storeContext)
        val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
        storeIndices.forEach { valueIndex ->
            val stored =
                executeStoreValue(
                    tokens[valueIndex].text,
                    commandsRun,
                    outputsBefore,
                    lastFunctionReturnValue,
                    commandSuccess,
                )
            storeExecuteValue(tokens, valueIndex, stored, location, storeContext)
        }
        anySuccess = anySuccess || commandSuccess
    }
    return anySuccess
}
