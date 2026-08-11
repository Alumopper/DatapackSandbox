package moe.afox.dpsandbox.browser.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserCoreSessionTest {
    @Test
    void checksEditorCommandsInOrderWithoutMutatingTheSession() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);

        assertEquals("[]", session.check(
                "scoreboard objectives add qwq dummy\nscoreboard players set #value qwq 1"));

        JsonObject snapshot = JsonParser.parseString(session.renderSnapshot()).getAsJsonObject();
        assertFalse(snapshot.has("scores"));
    }

    @Test
    void realtimeTicksReturnTheFinalWorldWithoutTraceOrSnapshotDiffs() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);
        session.upsertFunction("demo:tick", "say realtime");

        JsonObject result = JsonParser.parseString(session.runRealtimeTicks(2, "demo:tick")).getAsJsonObject();

        assertEquals(2, result.get("commands").getAsInt());
        assertEquals(2, result.get("gameTime").getAsInt());
        assertEquals(2, result.getAsJsonArray("outputs").size());
        assertFalse(result.has("snapshotDiffs"));
        assertFalse(result.has("trace"));
        assertFalse(result.has("snapshot"));
        JsonObject renderSnapshot = JsonParser.parseString(session.renderSnapshot()).getAsJsonObject();
        assertEquals(2, renderSnapshot.get("gameTime").getAsInt());
        assertFalse(renderSnapshot.has("scores"));
    }

    @Test
    void compactRealtimeTicksDeferTheRenderSnapshot() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);
        session.upsertFunction("demo:tick", "say realtime");

        JsonObject result = JsonParser.parseString(session.runRealtimeTicksCompact(2, "demo:tick")).getAsJsonObject();

        assertEquals(2, result.get("gameTime").getAsInt());
        assertFalse(result.has("renderSnapshot"));
        assertEquals(2, JsonParser.parseString(session.renderSnapshot()).getAsJsonObject().get("gameTime").getAsInt());
    }

    @Test
    void executesImportedFunctionsWithCoreCommandAccounting() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);
        session.upsertFunction("demo:main", "say imported locally");

        session.beginExecution();
        assertTrue(JsonParser.parseString(session.executeLineSafe("function demo:main", 1))
                .getAsJsonObject().get("ok").getAsBoolean());
        JsonObject result = JsonParser.parseString(session.finishExecution()).getAsJsonObject();

        assertEquals(2, result.get("commands").getAsInt());
        assertEquals("<Server> imported locally", result.getAsJsonArray("outputs").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    void keepsWorldStateWhenResourcesAreRebuilt() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);
        session.beginExecution();
        session.executeLineSafe("scoreboard objectives add state dummy", 1);
        session.executeLineSafe("scoreboard players set #value state 7", 2);
        session.finishExecution();

        session.upsertFunction("demo:read", "scoreboard players get #value state");
        session.beginExecution();
        session.executeLineSafe("function demo:read", 1);
        JsonObject result = JsonParser.parseString(session.finishExecution()).getAsJsonObject();

        assertEquals("7", result.getAsJsonArray("outputs").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    void preservesTouchInputDeviceMetadata() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);

        JsonObject result = JsonParser.parseString(
                session.dispatchInput("Steve", "touch", "look", "move", 2.5, -1.0))
                .getAsJsonObject();
        JsonObject input = result.getAsJsonObject("snapshot")
                .getAsJsonObject("players")
                .getAsJsonObject("Steve")
                .getAsJsonArray("inputEvents")
                .get(0)
                .getAsJsonObject();

        assertEquals("touch", input.get("device").getAsString());
        assertEquals(2.5, input.get("x").getAsDouble());
    }

    @Test
    void loadsBrowserTextResourcesIntoTheJvmCoreModels() {
        BrowserCoreSession session = new BrowserCoreSession("26.2", 10_000, 1_000, 4_000_000);
        session.upsertDatapackEntry(
                "data/demo/function/load.mcfunction",
                "scoreboard objectives add loaded dummy\nscoreboard players set #state loaded 1");
        session.upsertDatapackEntry(
                "data/minecraft/tags/function/load.json",
                "{\"values\":[\"demo:load\"]}");
        session.upsertDatapackEntry("data/demo/predicate/always.json", "true");
        session.upsertDatapackEntry(
                "data/demo/advancement/tick.json",
                "{\"criteria\":{\"tick\":{\"trigger\":\"minecraft:tick\"}}}");
        session.upsertDatapackEntry(
                "data/demo/recipe/marker.json",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[],\"result\":{\"id\":\"minecraft:stone\",\"count\":1}}");

        JsonObject load = JsonParser.parseString(session.runLoad()).getAsJsonObject();
        assertEquals(2, load.get("commands").getAsInt());

        session.beginExecution();
        assertTrue(JsonParser.parseString(session.executeLineSafe(
                "execute if predicate demo:always run scoreboard players add #state loaded 1", 1))
                .getAsJsonObject().get("ok").getAsBoolean());
        assertTrue(JsonParser.parseString(session.executeLineSafe("recipe give Steve demo:marker", 2))
                .getAsJsonObject().get("ok").getAsBoolean());
        assertTrue(JsonParser.parseString(session.executeLineSafe("advancement grant Steve only demo:tick", 3))
                .getAsJsonObject().get("ok").getAsBoolean());
        session.executeLineSafe("scoreboard players get #state loaded", 4);
        JsonObject result = JsonParser.parseString(session.finishExecution()).getAsJsonObject();

        assertEquals("2", result.getAsJsonArray("outputs").get(result.getAsJsonArray("outputs").size() - 1)
                .getAsJsonObject().get("text").getAsString());
    }
}
