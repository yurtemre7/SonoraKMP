package android.database

/**
 * Host-test shadow of the android.jar stub: the stub drops the message, which breaks Room's
 * `@Upsert` (it inspects the message for a uniqueness violation) when using the bundled driver on the JVM.
 */
open class SQLException : RuntimeException {
    constructor() : super()
    constructor(error: String?) : super(error)
    constructor(error: String?, cause: Throwable?) : super(error, cause)
}
