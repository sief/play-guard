package com.digitaltangible.ratelimit

import com.digitaltangible.FakeClock
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

class RateLimiterSpec extends AnyWordSpec with Matchers {

  "RateLimiter" should {
    "consumeAndCheck for rate limiting" in {
      val fakeClock   = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe false
      rateLimiter.consumeAndCheck("2") mustBe true
      fakeClock.ts = 501000000
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe false
    }

    "check and consume for failure rate limiting" in {
      val fakeClock   = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.check("1") mustBe true
      rateLimiter.check("1") mustBe true
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 1
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 0
      rateLimiter.check("1") mustBe false
      fakeClock.ts = 501000000
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 0
      rateLimiter.check("1") mustBe false
      rateLimiter.consume("2") mustBe 1
      rateLimiter.check("2") mustBe true
    }

    "serialize MyRateLimiter" in {
      val limiter: RateLimiter = new RateLimiter(1, 2)

      val baos: ByteArrayOutputStream = new ByteArrayOutputStream()
      val oos: ObjectOutputStream     = new ObjectOutputStream(baos)
      oos.writeObject(limiter)
      oos.close()

      val ois: ObjectInputStream = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
      val limiter2: RateLimiter  = ois.readObject.asInstanceOf[RateLimiter]
      ois.close()

      limiter2.consume("") mustBe 0 // not really interested in the result just that it doesn't throw
    }
  }
}
