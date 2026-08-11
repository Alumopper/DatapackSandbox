package moe.afox.dpsandbox.browser.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.afox.dpsandbox.core.CommandCheckResult;
import moe.afox.dpsandbox.core.CommandTraceEvent;
import moe.afox.dpsandbox.core.CommandTraceMode;
import moe.afox.dpsandbox.core.Datapack;
import moe.afox.dpsandbox.core.DatapackFunction;
import moe.afox.dpsandbox.core.DatapackLoader;
import moe.afox.dpsandbox.core.DatapackSandbox;
import moe.afox.dpsandbox.core.DiagnosticCode;
import moe.afox.dpsandbox.core.ExecutionContext;
import moe.afox.dpsandbox.core.ExecutionResult;
import moe.afox.dpsandbox.core.FunctionSource;
import moe.afox.dpsandbox.core.JsonValues;
import moe.afox.dpsandbox.core.OutputEvent;
import moe.afox.dpsandbox.core.PlayerEvent;
import moe.afox.dpsandbox.core.PlayerEvents;
import moe.afox.dpsandbox.core.ResourceLocation;
import moe.afox.dpsandbox.core.SandboxException;
import moe.afox.dpsandbox.core.SandboxBlock;
import moe.afox.dpsandbox.core.SandboxEntity;
import moe.afox.dpsandbox.core.SandboxLimits;
import moe.afox.dpsandbox.core.SandboxWorld;
import moe.afox.dpsandbox.core.SnapshotDiff;
import moe.afox.dpsandbox.core.SourceLocation;
import moe.afox.dpsandbox.core.TagDefinition;
import moe.afox.dpsandbox.core.TagKey;
import moe.afox.dpsandbox.core.TagValue;
import moe.afox.dpsandbox.core.UnsupportedFeatureMode;
import moe.afox.dpsandbox.core.VersionProfile;
import moe.afox.dpsandbox.core.VersionProfiles;
import moe.afox.dpsandbox.core.BlockPos;
import moe.afox.dpsandbox.core.WorldKt;
import org.teavm.jso.JSExport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent, browser-exported facade over the exact JVM sandbox runtime. */
public final class BrowserCoreSession {
    private final VersionProfile profile;
    private final SandboxLimits limits;
    private final Map<String, String> functionSources = new LinkedHashMap<>();
    private final Map<String, List<String>> functionTags = new LinkedHashMap<>();
    private final Map<String, String> datapackEntries = new LinkedHashMap<>();

    private SandboxWorld world;
    private DatapackSandbox sandbox;
    private boolean resourcesDirty = true;
    private boolean executionActive;
    private int outputStart;
    private int traceStart;
    private int operationCommands;
    private JsonObject snapshotBefore;

    @JSExport
    public BrowserCoreSession(
            String version,
            int maximumCommands,
            int maximumOutputEvents,
            int maximumSnapshotBytes) {
        profile = VersionProfiles.INSTANCE.get(version);
        limits = new SandboxLimits(maximumCommands, 64, 100_000, maximumOutputEvents, maximumSnapshotBytes, true);
        reset();
    }

    @JSExport
    public void beginExecution() {
        ensureSandbox();
        sandbox.clearExecutionCancellation();
        world.setCommandTraceMode(traceMode(CommandTraceMode.FULL));
        outputStart = world.getOutputs().size();
        traceStart = world.getTraces().size();
        operationCommands = 0;
        snapshotBefore = sandbox.snapshotJson();
        executionActive = true;
    }

    @JSExport
    public String executeLineSafe(String rawLine, int lineNumber) {
        if (!executionActive) {
            return failure("INVALID_REQUEST", "Execution was not started", lineNumber, rawLine);
        }
        String command = normalizeCommand(rawLine);
        if (command.isBlank() || command.startsWith("#")) {
            return "{\"ok\":true}";
        }
        try {
            ExecutionResult result = sandbox.executeCommand(
                    command,
                    new SourceLocation("<cell>", lineNumber, command),
                    new ExecutionContext());
            operationCommands += result.getCommandsExecuted();
            return "{\"ok\":true}";
        } catch (SandboxException error) {
            return failure(error.getCode().name(), error.getMessage(), lineNumber, command);
        } catch (RuntimeException error) {
            return failure("COMMAND_ERROR", message(error), lineNumber, command);
        }
    }

    @JSExport
    public String finishExecution() {
        if (!executionActive) {
            throw new IllegalStateException("Execution was not started");
        }
        executionActive = false;
        return operationResult(snapshotBefore);
    }

    @JSExport
    public String check(String source) {
        ensureSandbox();
        JsonArray diagnostics = new JsonArray();
        String[] lines = source.split("\\r?\\n", -1);
        List<String> commands = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String command = normalizeCommand(lines[index]);
            if (command.isBlank() || command.startsWith("#")) {
                continue;
            }
            commands.add(command);
            lineNumbers.add(index + 1);
        }
        List<CommandCheckResult> results = sandbox.checkCommands(commands);
        for (int index = 0; index < results.size(); index++) {
            CommandCheckResult result = results.get(index);
            if (!result.getValid()) {
                String command = commands.get(index);
                JsonObject diagnostic = new JsonObject();
                diagnostic.addProperty("line", lineNumbers.get(index));
                diagnostic.addProperty("from", 0);
                diagnostic.addProperty("to", command.length());
                diagnostic.addProperty("severity", "error");
                diagnostic.addProperty("code", result.getErrorCode() == null ? "COMMAND_ERROR" : result.getErrorCode().name());
                diagnostic.addProperty("message", result.getMessage());
                diagnostic.addProperty("command", command);
                diagnostics.add(diagnostic);
            }
        }
        return render(diagnostics);
    }

    @JSExport
    public void interrupt() {
        if (sandbox != null) {
            sandbox.requestExecutionCancellation();
        }
    }

    @JSExport
    public void reset() {
        world = new SandboxWorld();
        world.createPlayer("Steve");
        sandbox = null;
        executionActive = false;
        resourcesDirty = true;
        ensureSandbox();
    }

    @JSExport
    public String saveCheckpoint(String name) {
        ensureSandbox();
        try {
            return successString(sandbox.saveCheckpoint(name));
        } catch (RuntimeException error) {
            return checkpointFailure(error);
        }
    }

    @JSExport
    public String restoreCheckpoint(String name) {
        ensureSandbox();
        try {
            return successString(sandbox.restoreCheckpoint(name));
        } catch (RuntimeException error) {
            return checkpointFailure(error);
        }
    }

    @JSExport
    public String deleteCheckpoint(String name) {
        ensureSandbox();
        try {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("value", sandbox.deleteCheckpoint(name));
            return render(result);
        } catch (RuntimeException error) {
            return checkpointFailure(error);
        }
    }

    @JSExport
    public String checkpointNames() {
        ensureSandbox();
        JsonArray names = new JsonArray();
        for (String name : sandbox.checkpointNames()) {
            names.add(name);
        }
        return render(names);
    }

    @JSExport
    public void clearFunctions() {
        functionSources.clear();
        functionTags.clear();
        datapackEntries.clear();
        resourcesDirty = true;
    }

    @JSExport
    public void clearDatapackEntries() {
        functionSources.clear();
        functionTags.clear();
        datapackEntries.clear();
        resourcesDirty = true;
    }

    @JSExport
    public void upsertDatapackEntry(String path, String content) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Datapack entry path must not be blank");
        }
        datapackEntries.put(path.replace('\\', '/'), content == null ? "" : content);
        resourcesDirty = true;
    }

    @JSExport
    public void upsertFunction(String id, String source) {
        ResourceLocation.Companion.parse(id, "minecraft");
        functionSources.put(id, source);
        resourcesDirty = true;
    }

    @JSExport
    public void setFunctionTag(String id, String valuesCsv) {
        ResourceLocation.Companion.parse(id, "minecraft");
        List<String> values = new ArrayList<>();
        if (valuesCsv != null && !valuesCsv.isBlank()) {
            for (String raw : valuesCsv.split(",")) {
                String value = raw.trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        functionTags.put(id, values);
        resourcesDirty = true;
    }

    @JSExport
    public String runLoad() {
        beginOperation(CommandTraceMode.BASIC);
        operationCommands += sandbox.runLoad().getCommandsExecuted();
        return endOperation();
    }

    @JSExport
    public String runTicks(int count, String tickFunction) {
        if (count < 0) {
            throw new IllegalArgumentException("Tick count must not be negative");
        }
        beginOperation(CommandTraceMode.BASIC);
        for (int index = 0; index < count; index++) {
            operationCommands += sandbox.runTicks(1).getCommandsExecuted();
            if (tickFunction != null && !tickFunction.isBlank()) {
                operationCommands += sandbox.runFunction(tickFunction).getCommandsExecuted();
            }
        }
        return endOperation();
    }

    /**
     * Advances an interactive viewport without constructing a before snapshot, trace, or
     * state diff. The final snapshot is still returned because the browser renderer consumes
     * the exact JVM world shape, but VVE-sized packs avoid serializing the world twice and
     * recursively comparing both copies on every 50 ms deadline.
     */
    @JSExport
    public String runRealtimeTicks(int count, String tickFunction) {
        return runRealtimeTicks(count, tickFunction, true);
    }

    /** Advances the viewport clock without paying to serialize a render snapshot. */
    @JSExport
    public String runRealtimeTicksCompact(int count, String tickFunction) {
        return runRealtimeTicks(count, tickFunction, false);
    }

    private String runRealtimeTicks(int count, String tickFunction, boolean includeRenderSnapshot) {
        if (count < 0) {
            throw new IllegalArgumentException("Tick count must not be negative");
        }
        ensureSandbox();
        sandbox.clearExecutionCancellation();
        world.setCommandTraceMode(CommandTraceMode.OFF);
        int realtimeOutputStart = world.getOutputs().size();
        int commands = 0;
        for (int index = 0; index < count; index++) {
            commands += sandbox.runTicks(1).getCommandsExecuted();
            if (tickFunction != null && !tickFunction.isBlank()) {
                commands += sandbox.runFunction(tickFunction).getCommandsExecuted();
            }
        }
        JsonArray outputJson = new JsonArray();
        for (OutputEvent output : world.getOutputs().subList(realtimeOutputStart, world.getOutputs().size())) {
            outputJson.add(output.toJson());
        }
        JsonObject result = new JsonObject();
        result.addProperty("commands", commands);
        result.add("outputs", outputJson);
        result.addProperty("gameTime", world.getGameTime());
        if (includeRenderSnapshot) {
            result.add("renderSnapshot", renderSnapshotObject());
        }
        String rendered = render(result);
        world.getOutputs().clear();
        world.getTraces().clear();
        return rendered;
    }

    /** Returns only the world fields consumed by the renderer bridge. */
    @JSExport
    public String renderSnapshot() {
        ensureSandbox();
        return render(renderSnapshotObject());
    }

    private JsonObject renderSnapshotObject() {
        JsonObject result = new JsonObject();
        result.addProperty("gameTime", world.getGameTime());
        result.addProperty("dayTime", world.getDayTime());
        result.addProperty("weather", world.getWeather());
        result.addProperty("seed", world.getSeed());
        JsonArray blocks = new JsonArray();
        for (Map.Entry<BlockPos, SandboxBlock> entry : world.getBlocks().entrySet()) {
            blocks.add(entry.getValue().toJson(entry.getKey()));
        }
        result.add("blocks", blocks);
        JsonArray entities = new JsonArray();
        for (SandboxEntity entity : world.getEntities()) {
            String type = entity.getType().toString();
            if ("minecraft:marker".equals(type) || "minecraft:interaction".equals(type)) {
                continue;
            }
            entities.add(WorldKt.toJson(entity, profile));
        }
        result.add("entities", entities);
        return result;
    }

    @JSExport
    public String dispatchInput(
            String player,
            String device,
            String code,
            String action,
            double x,
            double y) {
        ensureSandbox();
        if (!world.getPlayers().containsKey(player)) {
            world.createPlayer(player);
        }
        PlayerEvent event;
        event = PlayerEvents.input(
                player,
                device,
                code,
                action,
                Double.isNaN(x) ? null : x,
                Double.isNaN(y) ? null : y);
        sandbox.handlePlayerEvent(event);
        JsonObject result = new JsonObject();
        result.addProperty("player", player);
        result.addProperty("device", device);
        result.addProperty("code", code);
        result.addProperty("action", action);
        if (!Double.isNaN(x)) {
            result.addProperty("x", x);
        }
        if (!Double.isNaN(y)) {
            result.addProperty("y", y);
        }
        result.addProperty("tick", world.getGameTime());
        result.add("snapshot", sandbox.snapshotJson());
        return render(result);
    }

    @JSExport
    public String snapshot() {
        ensureSandbox();
        return sandbox.snapshotString();
    }

    private void beginOperation(CommandTraceMode traceMode) {
        ensureSandbox();
        sandbox.clearExecutionCancellation();
        world.setCommandTraceMode(traceMode(traceMode));
        outputStart = world.getOutputs().size();
        traceStart = world.getTraces().size();
        operationCommands = 0;
        snapshotBefore = sandbox.snapshotJson();
    }

    private CommandTraceMode traceMode(CommandTraceMode requested) {
        // Large generated packs such as VVE can execute thousands of nested commands per
        // tick. Per-command snapshots would dominate the actual simulation and can retain
        // tens of megabytes. The operation still returns its final diff and exact command
        // count, while small packs retain the detailed trace used for interactive debugging.
        return datapackEntries.size() > 512 ? CommandTraceMode.OFF : requested;
    }

    private String endOperation() {
        return operationResult(snapshotBefore);
    }

    private String operationResult(JsonObject before) {
        JsonObject after = sandbox.snapshotJson();
        List<CommandTraceEvent> traces = world.getTraces().subList(traceStart, world.getTraces().size());
        List<OutputEvent> outputs = world.getOutputs().subList(outputStart, world.getOutputs().size());
        JsonArray traceJson = new JsonArray();
        for (CommandTraceEvent trace : traces) {
            traceJson.add(trace.toJson());
        }
        JsonArray outputJson = new JsonArray();
        for (OutputEvent output : outputs) {
            outputJson.add(output.toJson());
        }
        JsonObject result = new JsonObject();
        result.addProperty("commands", operationCommands);
        result.add("outputs", outputJson);
        result.add("snapshotDiffs", SnapshotDiff.toJson(SnapshotDiff.stateDiff(before, after)));
        result.add("trace", traceJson);
        result.add("snapshot", after);
        String rendered = render(result);
        world.getOutputs().clear();
        world.getTraces().clear();
        return rendered;
    }

    private void ensureSandbox() {
        if (!resourcesDirty && sandbox != null) {
            return;
        }
        List<FunctionSource> sources = new ArrayList<>();
        for (Map.Entry<String, String> entry : functionSources.entrySet()) {
            sources.add(FunctionSource.text(entry.getKey(), entry.getValue()));
        }
        Datapack loaded = !datapackEntries.isEmpty()
                ? BrowserMemoryDatapackLoader.load(datapackEntries, profile)
                : sources.isEmpty()
                ? new Datapack(
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        Collections.emptyList())
                : DatapackLoader.loadFunctionSources(sources, profile);
        if (!datapackEntries.isEmpty()) {
            sandbox = new DatapackSandbox(profile, loaded, world, UnsupportedFeatureMode.WARN, limits);
            resourcesDirty = false;
            return;
        }
        Map<TagKey, TagDefinition> tags = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : functionTags.entrySet()) {
            ResourceLocation id = ResourceLocation.Companion.parse(entry.getKey(), "minecraft");
            TagKey key = new TagKey("function", id);
            List<TagValue> values = new ArrayList<>();
            for (String value : entry.getValue()) {
                values.add(new TagValue(value, true));
            }
            tags.put(key, new TagDefinition(key, "<browser:" + id + ">", false, values));
        }
        List<ResourceLocation> loadFunctions = resolveLifecycleTag("minecraft:load", loaded.getFunctions());
        List<ResourceLocation> tickFunctions = resolveLifecycleTag("minecraft:tick", loaded.getFunctions());
        Datapack datapack = new Datapack(
                loaded.getFunctions(),
                loadFunctions,
                tickFunctions,
                loaded.getLootTables(),
                loaded.getPredicates(),
                loaded.getAdvancements(),
                loaded.getRecipes(),
                loaded.getItemModifiers(),
                loaded.getRawResources(),
                tags,
                loaded.getResourceIndex(),
                loaded.getWarnings());
        sandbox = new DatapackSandbox(profile, datapack, world, UnsupportedFeatureMode.WARN, limits);
        resourcesDirty = false;
    }

    private List<ResourceLocation> resolveLifecycleTag(
            String id,
            Map<ResourceLocation, DatapackFunction> functions) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        resolveTag(id, functions, result, new LinkedHashSet<>());
        return new ArrayList<>(result);
    }

    private void resolveTag(
            String id,
            Map<ResourceLocation, DatapackFunction> functions,
            Set<ResourceLocation> result,
            Set<String> visiting) {
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Recursive function tag '#" + id + "'");
        }
        for (String value : functionTags.getOrDefault(id, Collections.emptyList())) {
            if (value.startsWith("#")) {
                resolveTag(value.substring(1), functions, result, visiting);
            } else {
                ResourceLocation function = ResourceLocation.Companion.parse(value, "minecraft");
                if (functions.containsKey(function)) {
                    result.add(function);
                }
            }
        }
        visiting.remove(id);
    }

    private String successString(String value) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("value", value);
        return render(result);
    }

    private String checkpointFailure(RuntimeException error) {
        String code = error instanceof SandboxException
                ? ((SandboxException) error).getCode().name()
                : "CHECKPOINT_FAILED";
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message(error));
        result.add("error", detail);
        return render(result);
    }

    private String failure(String code, String message, int line, String command) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        detail.addProperty("line", line);
        detail.addProperty("command", command);
        result.add("error", detail);
        return render(result);
    }

    private static String normalizeCommand(String value) {
        String command = value == null ? "" : value;
        if (!command.isEmpty() && command.charAt(0) == '\uFEFF') {
            command = command.substring(1);
        }
        command = command.trim();
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String render(JsonElement value) {
        return JsonValues.INSTANCE.render(value);
    }
}
