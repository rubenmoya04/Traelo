package dev.rubenmoya.traelo.core

/**
 * Velocidad media de los ultimos segundos y tiempo restante. Puro: el reloj se pasa en cada
 * muestra, en milisegundos.
 */
class TransferMeter(private val windowMs: Long = 4_000) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    fun reset() {
        times.clear()
        totals.clear()
    }

    /** Apunta que a las [nowMs] llevamos [bytesDone] bytes. */
    fun sample(nowMs: Long, bytesDone: Long) {
        times.addLast(nowMs)
        totals.addLast(bytesDone)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            totals.removeFirst()
        }
    }

    /** Bytes por segundo en la ventana, o 0 si aun no hay datos suficientes. */
    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0
        val elapsed = times.last() - times.first()
        if (elapsed < MIN_ELAPSED_MS) return 0
        val bytes = totals.last() - totals.first()
        return if (bytes <= 0) 0 else bytes * 1000 / elapsed
    }

    /** Segundos que faltan para llegar a [bytesTotal], o null si no se puede estimar. */
    fun etaSeconds(bytesTotal: Long): Long? {
        val speed = bytesPerSecond()
        if (speed <= 0 || bytesTotal <= 0 || totals.isEmpty()) return null
        val left = (bytesTotal - totals.last()).coerceAtLeast(0)
        return (left + speed - 1) / speed
    }

    private companion object {
        const val MIN_ELAPSED_MS = 300L
    }
}

/**
 * Junta el progreso que llega de varios hilos y lo manda a la vista como mucho cada
 * [intervalMs] (los cambios de fase salen siempre). Con [intervalMs] 0 lo manda todo.
 */
class ProgressTracker(
    private val sink: (SyncProgress) -> Unit,
    private val intervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val meter = TransferMeter()
    private var phase = SyncPhase.CHECKING
    private var done = 0
    private var total = 0
    private var bytesDone = 0L
    private var bytesTotal = 0L
    private var path: String? = null
    private var lastEmit = Long.MIN_VALUE

    @Synchronized
    fun phase(next: SyncPhase, total: Int = 0, bytesTotal: Long = 0) {
        phase = next
        done = 0
        this.total = total
        bytesDone = 0
        this.bytesTotal = bytesTotal
        path = null
        meter.reset()
        emit(force = true)
    }

    @Synchronized
    fun bytes(count: Int) {
        bytesDone += count
        emit(force = false)
    }

    @Synchronized
    fun fileDone(path: String) {
        done++
        this.path = path
        emit(force = intervalMs == 0L)
    }

    @Synchronized
    private fun emit(force: Boolean) {
        val now = clock()
        if (!force && lastEmit != Long.MIN_VALUE && now - lastEmit < intervalMs) return
        lastEmit = now
        meter.sample(now, bytesDone)
        val shownBytesTotal = if (bytesTotal > 0) maxOf(bytesTotal, bytesDone) else 0
        sink(
            SyncProgress(
                phase = phase,
                done = done,
                total = total,
                path = path,
                bytesDone = bytesDone,
                bytesTotal = shownBytesTotal,
                bytesPerSecond = meter.bytesPerSecond(),
                etaSeconds = meter.etaSeconds(shownBytesTotal),
            ),
        )
    }
}
