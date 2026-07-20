package moe.afox.dpsandbox.render

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val output = Path.of(args.firstOrNull() ?: "renderer/build/reports/render-benchmark.json").toAbsolutePath().normalize()
    val results = JsonArray()
    listOf(16, 32, 64).forEach { size ->
        val sparse = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        val stride = (size / 8).coerceAtLeast(1)
        for (x in 0 until size step stride) {
            for (y in 0 until size step stride) {
                for (z in 0 until size step stride) {
                    sparse.world.setBlock(BlockPos(x, y, z), SandboxBlock(ResourceLocation.parse("minecraft:stone")))
                }
            }
        }
        results.add(benchmark("sparse-${size}cube", sparse, 960, 540))

        val solidSurface = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        for (x in 0 until size) {
            for (y in 0 until size) {
                for (z in 0 until size) {
                    if (x == 0 || y == 0 || z == 0 || x == size - 1 || y == size - 1 || z == size - 1) {
                        solidSurface.world.setBlock(BlockPos(x, y, z), SandboxBlock(ResourceLocation.parse("minecraft:stone")))
                    }
                }
            }
        }
        results.add(benchmark("solid-surface-${size}cube", solidSurface, 960, 540))
    }

    val resolutionSandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
    for (x in 0 until 16) {
        for (z in 0 until 16) {
            resolutionSandbox.world.setBlock(BlockPos(x, 0, z), SandboxBlock(ResourceLocation.parse("minecraft:stone")))
        }
    }
    listOf(960 to 540, 1280 to 720, 1920 to 1080).forEach { (width, height) ->
        results.add(benchmark("resolution-${width}x$height", resolutionSandbox, width, height))
    }

    val repeatedRenderer = SandboxRenderer()
    results.add(benchmark("assets-cold", resolutionSandbox, 960, 540, repeatedRenderer))
    results.add(benchmark("assets-warm", resolutionSandbox, 960, 540, repeatedRenderer))
    results.add(
        benchmark(
            "camera-change",
            resolutionSandbox,
            960,
            540,
            repeatedRenderer,
            RenderCamera.Fixed(Position(8.0, 20.0, -18.0), 0.0, 28.0),
        ),
    )
    results.add(benchmark("world-unchanged", resolutionSandbox, 960, 540, repeatedRenderer))

    listOf(100, 1_000).forEach { count ->
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        repeat(count) { index ->
            sandbox.world.entities +=
                SandboxEntity(
                    type = ResourceLocation.parse("minecraft:zombie"),
                    position = Position((index % 32).toDouble(), 0.0, (index / 32).toDouble()),
                )
        }
        results.add(benchmark("entities-$count", sandbox, 960, 540))
    }

    val report =
        JsonObject().also { json ->
            json.addProperty("format", "dps-render-benchmark-v1")
            json.addProperty("java", System.getProperty("java.version"))
            json.addProperty("os", System.getProperty("os.name"))
            json.add("results", results)
        }
    output.parent?.let(Files::createDirectories)
    Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator())
    println("Renderer benchmark written: $output")
}

private fun benchmark(
    name: String,
    sandbox: moe.afox.dpsandbox.core.DatapackSandbox,
    width: Int,
    height: Int,
    renderer: SandboxRenderer = SandboxRenderer(),
    camera: RenderCamera = RenderCamera.Auto,
): JsonObject {
    val frame = renderer.render(sandbox, RenderRequest(width = width, height = height, camera = camera, renderDistance = 512.0))
    return JsonObject().also { json ->
        json.addProperty("name", name)
        json.addProperty("width", width)
        json.addProperty("height", height)
        json.addProperty("blocks", frame.metadata.visibleBlocks)
        json.addProperty("entities", frame.metadata.visibleEntities)
        json.addProperty("triangles", frame.metadata.triangles)
        json.addProperty("worldCaptureNanos", frame.metadata.worldCaptureNanos)
        json.addProperty("assetResolveNanos", frame.metadata.assetResolveNanos)
        json.addProperty("sceneBuildNanos", frame.metadata.sceneBuildNanos)
        json.addProperty("rasterizeNanos", frame.metadata.rasterizeNanos)
        json.addProperty("pngEncodeNanos", frame.metadata.pngEncodeNanos)
        json.addProperty("renderNanos", frame.metadata.renderNanos)
        json.addProperty("pngBytes", frame.pngBytes().size)
    }
}
