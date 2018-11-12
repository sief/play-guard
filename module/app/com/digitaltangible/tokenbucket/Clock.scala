package com.digitaltangible.tokenbucket

/**
  * For mocking the current time.
  */
trait Clock {
  def now: Long
}

object CurrentTimeClock extends Clock with Serializable {
  override def now: Long = System.nanoTime()
}
