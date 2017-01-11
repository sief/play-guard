package com.digitaltangible

import com.digitaltangible.tokenbucket.Clock

class FakeClock extends Clock {
  var ts: Long = 0

  override def now: Long = ts
}
