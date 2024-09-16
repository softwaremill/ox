package ox.util

import scala.util.control.ControlThrowable

class NastyControlThrowable(val message: String) extends ControlThrowable(message) {}
