package com.digitaltangible.tokenbucket

/**
  * For mocking the current time.
  */
trait Clock {
  def now: Long
}

object CurrentTimeClock extends Clock {
  override def now: Long = System.nanoTime()
}
