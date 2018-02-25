package com.digitaltangible.tokenbucket

/**
  * Token Bucket implementation as described here http://en.wikipedia.org/wiki/Token_bucket
  */


/**
  * TokenBucketGroup which synchronizes the bucket token requests.
  *
  * @param size  bucket size
  * @param rate  refill rate in tokens per second
  * @param clock for mocking the current time.
  */
class TokenBucketGroup(size: Int, rate: Float, clock: Clock = CurrentTimeClock) {
  require(size > 0)
  require(rate >= 0.000001f)

  private[this] val intervalMillis: Int = (1000 / rate).toInt

  private[this] val ratePerMilli: Double = rate / 1000

  // encapsulated mutable state
  private[this] var lastRefill: Long = clock.now

  private[this] var buckets = Map.empty[Any, Int]

  /**
    * First refills all buckets at the given rate, then tries to consume the required amount.
    * If no bucket exists for the given key, a new full one is created.
    * @param key
    * @param required number of tokens to consume
    * @return
    */
  def consume(key: Any, required: Int): Int = this.synchronized {
    refillAll()
    val newLevel = buckets.getOrElse(key, size) - required
    if (newLevel >= 0) {
      buckets = buckets + (key -> newLevel)
    }
    newLevel
  }

  /**
    * Refills all buckets at the given rate. Full buckets are removed.
    */
  private def refillAll() {
    val now: Long = clock.now
    val diff: Long = now - lastRefill
    val tokensToAdd: Long = (diff * ratePerMilli).toLong
    if (tokensToAdd > 0) {
      buckets = buckets.mapValues(addTokens(_, tokensToAdd)).filterNot(_._2 >= size)
      lastRefill = now - diff % intervalMillis
    }
  }

  /**
    * Helper to avoid overflow.
    *
    * @param currentLevel
    * @param toAdd
    * @return the sum or Int.MaxValue in case of overflow
    */
  private def addTokens(currentLevel: Int, toAdd: Long): Int = {
    val r = currentLevel.toLong + toAdd
    if (r > Int.MaxValue) Int.MaxValue
    else r.toInt
  }
}