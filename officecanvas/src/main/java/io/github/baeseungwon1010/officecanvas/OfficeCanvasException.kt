package io.github.baeseungwon1010.officecanvas

import java.io.IOException

/** Thrown when a file isn't a supported document or can't be read as one. */
class OfficeCanvasException(message: String, cause: Throwable? = null) : IOException(message, cause)
