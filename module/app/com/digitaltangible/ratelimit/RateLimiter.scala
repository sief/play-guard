package com.digitaltangible.ratelimit

import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.Logger

/**
 * Holds a TokenBucketGroup for rate limiting. You can share an instance if you want different Actions to use the same TokenBucketGroup.
 *
 * @param size
 * @param rate
 * @param logPrefix
 * @param clock
 */
class RateLimiter(val size: Long, val rate: Double, logPrefix: String = "", clock: Clock = CurrentTimeClock) extends Serializable {

  @transient private lazy val logger: Logger = Logger(this.getClass)

  private lazy val tokenBucketGroup = new TokenBucketGroup(size, rate, clock)

  /**
   * Checks if the bucket for the given key has at least one token left.
   * If available, the token is consumed.
   *
   * @param key
   * @return
   */
  def consumeAndCheck(key: Any): Boolean = consumeAndCheck(key, 1, _ >= 0)

  /**
   * Checks if the bucket for the given key has at least one token left.
   *
   * @param key bucket key
   * @return
   */
  def check(key: Any): Boolean = consumeAndCheck(key, 0, _ > 0)

  private def consumeAndCheck(key: Any, amount: Int, check: Long => Boolean): Boolean = {
    val remaining: Long = tokenBucketGroup.consume(key, amount)
    if (check(remaining)) {
      if (remaining < size.toFloat / 2) logger.info(s"$logPrefix remaining tokens for $key below 50%: $remaining")
      true
    } else {
      logger.warn(s"$logPrefix rate limit for $key exceeded")
      false
    }
  }

  /**
   * Consumes one token for the given key
   *
   * @param key
   * @return
   */
  def consume(key: Any): Long = tokenBucketGroup.consume(key, 1)
}
