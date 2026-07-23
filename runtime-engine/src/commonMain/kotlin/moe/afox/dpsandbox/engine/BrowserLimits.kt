package moe.afox.dpsandbox.engine

data class BrowserLimits(
    val maximumCellBytes: Int = 64 * 1024,
    val maximumOutputBytes: Int = 1024 * 1024,
    val maximumCommands: Int = 10_000,
    val maximumOutputEvents: Int = 2_000,
    val maximumRenderWidth: Int = 1920,
    val maximumRenderHeight: Int = 1080,
    val maximumCheckpoints: Int = 32,
    val maximumCheckpointBytes: Int = 8 * 1024 * 1024,
    val maximumAnimationFrames: Int = 120,
    val maximumAnimationBytes: Int = 64 * 1024 * 1024,
    val maximumImportBytes: Int = 64 * 1024 * 1024,
    val maximumImportFiles: Int = 8_192,
) {
    init {
        require(maximumCellBytes > 0)
        require(maximumOutputBytes > 0)
        require(maximumCommands > 0)
        require(maximumOutputEvents > 0)
        require(maximumRenderWidth in 16..4096)
        require(maximumRenderHeight in 16..4096)
        require(maximumCheckpoints in 1..256)
        require(maximumCheckpointBytes > 0)
        require(maximumAnimationFrames in 1..1000)
        require(maximumAnimationBytes > 0)
        require(maximumImportBytes > 0)
        require(maximumImportFiles > 0)
    }
}
