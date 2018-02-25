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
class TokenBucketGroup(size: Long, rate: Double, clock: Clock = CurrentTimeClock) {
  require(size > 0)
  require(rate >= 0.000001f)
  require(rate < NanosPerSecond)

  private[this] lazy val NanosPerSecond = 1000000000

  private[this] val intervalNanos: Long = (NanosPerSecond / rate).toLong

  private[this] val ratePerNano: Double = rate / NanosPerSecond

  // encapsulated mutable state
  private[this] var lastRefill: Long = clock.now

  var buckets = Map.empty[Any, Long]

  /**
    * First refills all buckets at the given rate, then tries to consume the required amount.
    * If no bucket exists for the given key, a new full one is created.
    * @param key
    * @param required number of tokens to consume
    * @return
    */
  def consume(key: Any, required: Int): Long = this.synchronized {
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
    val tokensToAdd: Long = (diff * ratePerNano).toLong
    if (tokensToAdd > 0) {
      buckets = buckets.mapValues(_ + tokensToAdd).filterNot(_._2 >= size)
      lastRefill = now - diff % intervalNanos
    }
  }
}