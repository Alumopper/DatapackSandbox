package moe.afox.dpsandbox.browser.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.afox.dpsandbox.core.AdvancementDefinition;
import moe.afox.dpsandbox.core.AdvancementReward;
import moe.afox.dpsandbox.core.Criterion;
import moe.afox.dpsandbox.core.Datapack;
import moe.afox.dpsandbox.core.DatapackFunction;
import moe.afox.dpsandbox.core.DatapackLoader;
import moe.afox.dpsandbox.core.FunctionSource;
import moe.afox.dpsandbox.core.JsonValues;
import moe.afox.dpsandbox.core.LootTable;
import moe.afox.dpsandbox.core.PredicateDefinition;
import moe.afox.dpsandbox.core.RawJsonResource;
import moe.afox.dpsandbox.core.ResourceBehaviorLevels;
import moe.afox.dpsandbox.core.ResourceCatalog;
import moe.afox.dpsandbox.core.ResourceDirectoryProfile;
import moe.afox.dpsandbox.core.ResourceIndexEntry;
import moe.afox.dpsandbox.core.ResourceLocation;
import moe.afox.dpsandbox.core.TagDefinition;
import moe.afox.dpsandbox.core.TagKey;
import moe.afox.dpsandbox.core.TagValue;
import moe.afox.dpsandbox.core.VersionProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads browser-owned text entries into the same immutable resource models used on the JVM. */
final class BrowserMemoryDatapackLoader {
    private BrowserMemoryDatapackLoader() {
    }

    static Datapack load(Map<String, String> entries, VersionProfile profile) {
        ResourceDirectoryProfile directories = profile.getResourceDirectories();
        List<FunctionSource> functionSources = new ArrayList<>();
        Map<ResourceLocation, LootTable> lootTables = new LinkedHashMap<>();
        Map<ResourceLocation, PredicateDefinition> predicates = new LinkedHashMap<>();
        Map<ResourceLocation, AdvancementDefinition> advancements = new LinkedHashMap<>();
        Map<ResourceLocation, RawJsonResource> recipes = new LinkedHashMap<>();
        Map<ResourceLocation, RawJsonResource> itemModifiers = new LinkedHashMap<>();
        Map<String, Map<ResourceLocation, RawJsonResource>> rawResources = new LinkedHashMap<>();
        Map<TagKey, TagDefinition> tags = new LinkedHashMap<>();
        Map<String, TagDefinition> functionTags = new LinkedHashMap<>();
        List<ResourceIndexEntry> index = new ArrayList<>();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String path = normalizePath(entry.getKey());
            LocatedResource located = locate(path);
            if (located == null) {
                continue;
            }
            String relative = located.relative;
            String functionPath = underDirectory(relative, directories.getFunctions(), ".mcfunction");
            if (functionPath != null) {
                ResourceLocation id = resourceId(located.namespace, functionPath, path);
                functionSources.add(FunctionSource.text(id.toString(), entry.getValue(), path));
                index.add(indexEntry("function", id, path, index.size()));
                continue;
            }
            if (!relative.endsWith(".json")) {
                continue;
            }
            JsonElement root = parseJson(entry.getValue(), path);
            if (relative.startsWith("tags/")) {
                TagDefinition tag = parseTag(located.namespace, relative, path, root, directories);
                if (tag != null) {
                    if ("function".equals(tag.getKey().getRegistry())) {
                        functionTags.put(tag.getKey().getId().toString(), tag);
                    } else {
                        tags.put(tag.getKey(), tag);
                    }
                    index.add(indexEntry("tag/" + tag.getKey().getRegistry(), tag.getKey().getId(), path, index.size()));
                }
                continue;
            }
            String idPath;
            if ((idPath = underDirectory(relative, directories.getLootTables(), ".json")) != null) {
                ResourceLocation id = resourceId(located.namespace, idPath, path);
                if (!root.isJsonObject()) throw invalid(path, "Loot table must be a JSON object");
                lootTables.put(id, new LootTable(id, path, root.getAsJsonObject()));
                index.add(indexEntry("loot_table", id, path, index.size()));
            } else if ((idPath = underDirectory(relative, directories.getPredicates(), ".json")) != null) {
                ResourceLocation id = resourceId(located.namespace, idPath, path);
                predicates.put(id, new PredicateDefinition(id, path, root));
                index.add(indexEntry("predicate", id, path, index.size()));
            } else if ((idPath = underDirectory(relative, directories.getAdvancements(), ".json")) != null) {
                ResourceLocation id = resourceId(located.namespace, idPath, path);
                advancements.put(id, parseAdvancement(id, path, root));
                index.add(indexEntry("advancement", id, path, index.size()));
            } else if ((idPath = underDirectory(relative, directories.getRecipes(), ".json")) != null) {
                ResourceLocation id = resourceId(located.namespace, idPath, path);
                RawJsonResource resource = new RawJsonResource("recipe", id, path, root, profile.getId(), null);
                recipes.put(id, resource);
                rawResources.computeIfAbsent("recipe", ignored -> new LinkedHashMap<>()).put(id, resource);
                index.add(indexEntry("recipe", id, path, index.size()));
            } else if ((idPath = underDirectory(relative, directories.getItemModifiers(), ".json")) != null) {
                ResourceLocation id = resourceId(located.namespace, idPath, path);
                RawJsonResource resource = new RawJsonResource("item_modifier", id, path, root, profile.getId(), null);
                itemModifiers.put(id, resource);
                rawResources.computeIfAbsent("item_modifier", ignored -> new LinkedHashMap<>()).put(id, resource);
                index.add(indexEntry("item_modifier", id, path, index.size()));
            } else {
                for (String kind : ResourceCatalog.INSTANCE.getAdditionalRawJsonTypes()) {
                    idPath = underDirectory(relative, Collections.singletonList(kind), ".json");
                    if (idPath == null) continue;
                    ResourceLocation id = resourceId(located.namespace, idPath, path);
                    RawJsonResource resource = new RawJsonResource(kind, id, path, root, profile.getId(), null);
                    rawResources.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).put(id, resource);
                    index.add(indexEntry(kind, id, path, index.size()));
                    break;
                }
            }
        }

        Datapack functions = functionSources.isEmpty() ? emptyDatapack() : DatapackLoader.loadFunctionSources(functionSources, profile);
        List<ResourceLocation> loadFunctions = resolveFunctionTag("minecraft:load", functionTags, functions.getFunctions());
        List<ResourceLocation> tickFunctions = resolveFunctionTag("minecraft:tick", functionTags, functions.getFunctions());
        functionTags.forEach((id, definition) -> {
            if (!"minecraft:load".equals(id) && !"minecraft:tick".equals(id)) tags.put(definition.getKey(), definition);
        });
        return new Datapack(
                functions.getFunctions(),
                loadFunctions,
                tickFunctions,
                lootTables,
                predicates,
                advancements,
                recipes,
                itemModifiers,
                rawResources,
                tags,
                index,
                Collections.emptyList());
    }

    private static Datapack emptyDatapack() {
        return new Datapack(
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
    }

    private static TagDefinition parseTag(
            String namespace,
            String relative,
            String path,
            JsonElement root,
            ResourceDirectoryProfile directories) {
        String rest = relative.substring("tags/".length());
        int separator = rest.indexOf('/');
        if (separator <= 0 || !rest.endsWith(".json")) throw invalid(path, "Tag must be under tags/<registry>/<path>.json");
        String registry = rest.substring(0, separator);
        if (directories.getFunctionTags().contains(registry)) registry = "function";
        ResourceLocation id = resourceId(namespace, rest.substring(separator + 1, rest.length() - 5), path);
        if (!root.isJsonObject()) throw invalid(path, "Tag must be a JSON object");
        JsonObject object = root.getAsJsonObject();
        JsonElement valuesElement = object.get("values");
        if (valuesElement == null || !valuesElement.isJsonArray()) throw invalid(path, "Tag must contain a values array");
        List<TagValue> values = new ArrayList<>();
        for (JsonElement value : valuesElement.getAsJsonArray()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                values.add(new TagValue(value.getAsString(), true));
            } else if (value.isJsonObject() && value.getAsJsonObject().get("id") != null) {
                JsonObject item = value.getAsJsonObject();
                boolean required = item.get("required") == null || item.get("required").getAsBoolean();
                values.add(new TagValue(item.get("id").getAsString(), required));
            } else {
                throw invalid(path, "Tag values must be strings or objects with an id");
            }
        }
        boolean replace = object.get("replace") != null && object.get("replace").getAsBoolean();
        TagKey key = new TagKey(registry, id);
        return new TagDefinition(key, path, replace, values);
    }

    private static AdvancementDefinition parseAdvancement(ResourceLocation id, String path, JsonElement element) {
        if (!element.isJsonObject()) throw invalid(path, "Advancement must be a JSON object");
        JsonObject root = element.getAsJsonObject();
        JsonElement criteriaElement = root.get("criteria");
        if (criteriaElement == null || !criteriaElement.isJsonObject()) throw invalid(path, "Advancement must contain a criteria object");
        Map<String, Criterion> criteria = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : criteriaElement.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) throw invalid(path, "Advancement criterion must be an object");
            JsonObject criterion = entry.getValue().getAsJsonObject();
            if (criterion.get("trigger") == null) throw invalid(path, "Advancement criterion is missing trigger");
            criteria.put(entry.getKey(), new Criterion(
                    entry.getKey(),
                    ResourceLocation.Companion.parse(criterion.get("trigger").getAsString(), "minecraft"),
                    criterion.getAsJsonObject("conditions")));
        }
        List<List<String>> requirements = new ArrayList<>();
        JsonElement requirementsElement = root.get("requirements");
        if (requirementsElement == null) {
            for (String name : criteria.keySet()) requirements.add(Collections.singletonList(name));
        } else {
            if (!requirementsElement.isJsonArray()) throw invalid(path, "Advancement requirements must be an array");
            for (JsonElement rowElement : requirementsElement.getAsJsonArray()) {
                if (!rowElement.isJsonArray()) throw invalid(path, "Advancement requirement rows must be arrays");
                List<String> row = new ArrayList<>();
                for (JsonElement value : rowElement.getAsJsonArray()) row.add(value.getAsString());
                requirements.add(row);
            }
        }
        AdvancementReward reward = new AdvancementReward();
        JsonObject rewards = root.getAsJsonObject("rewards");
        if (rewards != null) {
            int experience = rewards.get("experience") == null ? 0 : rewards.get("experience").getAsInt();
            List<ResourceLocation> loot = resourceList(rewards.getAsJsonArray("loot"));
            List<ResourceLocation> recipes = resourceList(rewards.getAsJsonArray("recipes"));
            ResourceLocation function = rewards.get("function") == null
                    ? null
                    : ResourceLocation.Companion.parse(rewards.get("function").getAsString(), "minecraft");
            reward = new AdvancementReward(experience, loot, recipes, function);
        }
        ResourceLocation parent = root.get("parent") == null
                ? null
                : ResourceLocation.Companion.parse(root.get("parent").getAsString(), "minecraft");
        return new AdvancementDefinition(id, path, root, parent, criteria, requirements, reward);
    }

    private static List<ResourceLocation> resourceList(JsonArray values) {
        if (values == null) return Collections.emptyList();
        List<ResourceLocation> result = new ArrayList<>();
        for (JsonElement value : values) result.add(ResourceLocation.Companion.parse(value.getAsString(), "minecraft"));
        return result;
    }

    private static List<ResourceLocation> resolveFunctionTag(
            String id,
            Map<String, TagDefinition> tags,
            Map<ResourceLocation, DatapackFunction> functions) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        resolveFunctionTag(id, tags, functions, result, new LinkedHashSet<>());
        return new ArrayList<>(result);
    }

    private static void resolveFunctionTag(
            String id,
            Map<String, TagDefinition> tags,
            Map<ResourceLocation, DatapackFunction> functions,
            Set<ResourceLocation> result,
            Set<String> visiting) {
        if (!visiting.add(id)) throw invalid(id, "Recursive function tag '#" + id + "'");
        TagDefinition definition = tags.get(id);
        if (definition != null) {
            for (TagValue value : definition.getValues()) {
                if (value.getId().startsWith("#")) {
                    resolveFunctionTag(value.getId().substring(1), tags, functions, result, visiting);
                } else {
                    ResourceLocation function = ResourceLocation.Companion.parse(value.getId(), "minecraft");
                    if (functions.containsKey(function)) result.add(function);
                    else if (value.getRequired()) throw invalid(definition.getFile(), "Missing function '" + function + "'");
                }
            }
        }
        visiting.remove(id);
    }

    private static ResourceIndexEntry indexEntry(String kind, ResourceLocation id, String path, int order) {
        return new ResourceIndexEntry(
                kind, id, path, "<browser>", order, true, null, null,
                ResourceBehaviorLevels.INSTANCE.forType(kind));
    }

    private static JsonElement parseJson(String content, String path) {
        try {
            return JsonValues.INSTANCE.parse(content, null);
        } catch (RuntimeException error) {
            throw invalid(path, "Invalid JSON: " + message(error));
        }
    }

    private static ResourceLocation resourceId(String namespace, String path, String source) {
        try {
            return new ResourceLocation(namespace, path);
        } catch (RuntimeException error) {
            throw invalid(source, "Invalid resource id '" + namespace + ":" + path + "': " + message(error));
        }
    }

    private static LocatedResource locate(String path) {
        int data = path.indexOf("data/");
        if (data < 0 || (data > 0 && path.charAt(data - 1) != '/')) return null;
        String value = path.substring(data + 5);
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) return null;
        return new LocatedResource(value.substring(0, slash), value.substring(slash + 1));
    }

    private static String underDirectory(String relative, List<String> directories, String suffix) {
        for (String directory : directories) {
            String prefix = directory + "/";
            if (relative.startsWith(prefix) && relative.endsWith(suffix)) {
                return relative.substring(prefix.length(), relative.length() - suffix.length());
            }
        }
        return null;
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static IllegalArgumentException invalid(String path, String detail) {
        return new IllegalArgumentException(detail + " (" + path + ")");
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class LocatedResource {
        final String namespace;
        final String relative;

        LocatedResource(String namespace, String relative) {
            this.namespace = namespace;
            this.relative = relative;
        }
    }
}
