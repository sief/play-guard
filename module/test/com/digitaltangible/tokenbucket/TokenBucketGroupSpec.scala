package com.digitaltangible.tokenbucket

import com.digitaltangible.FakeClock
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, MustMatchers}

class TokenBucketGroupSpec extends FlatSpecLike with MustMatchers with ScalaFutures with GeneratorDrivenPropertyChecks {

  "new TokenBucketGroup" should
    "allow only values for size and rate in their range" in {
    forAll { (size: Int, rate: Float) =>
      if ((size > 0) && (rate >= 0.000001f)) {
        new TokenBucketGroup(size, rate)
      } else {
        intercept[IllegalArgumentException] {
          new TokenBucketGroup(size, rate)
        }
      }
      ()
    }
    new TokenBucketGroup(1, 0.000001f)
    intercept[IllegalArgumentException] {
      new TokenBucketGroup(1, 0.0000001f)
    }
  }

  "TokenBucketGroup.consume" should
    "allow 'token count' <= 'bucket size' at the same moment" in {
    val fakeClock = new FakeClock
    forAll(Gen.choose(0, 1000)) { (i: Int) =>
      val ref = new TokenBucketGroup(1000, 2, fakeClock)
      ref.consume("x", i).futureValue mustBe 1000 - i
    }
  }

  it should "not allow 'token count' > 'bucket size' at the same moment" in {
    val fakeClock = new FakeClock
    forAll(Gen.posNum[Int]) { (i: Int) =>
      val ref = new TokenBucketGroup(1000, 2, fakeClock)
      ref.consume("x", i + 1000).futureValue mustBe -i
    }
  }

  it should "not allow negative token count" in {
    val ref = new TokenBucketGroup(100, 2)
    forAll(Gen.negNum[Int]) { (i: Int) =>
      intercept[IllegalArgumentException] {
        ref.consume("x", i)
      }
    }
  }

  it should "handle different keys separately" in {
    val fakeClock = new FakeClock
    val ref = new TokenBucketGroup(1000, 2, fakeClock)
    ref.consume("asdf", 1000).futureValue mustBe 0
    ref.consume("qwer", 1000).futureValue mustBe 0
    ref.consume(1, 1000).futureValue mustBe 0
    ref.consume(2, 1000).futureValue mustBe 0
    ref.consume(fakeClock, 1000).futureValue mustBe 0
    ref.consume(2, 1).futureValue mustBe -1
    ref.consume("asdf", 1).futureValue mustBe -1
    ref.consume(fakeClock, 1).futureValue mustBe -1
  }

  it should "regain tokens at specified rate" in {
    val fakeClock = new FakeClock
    val ref = new TokenBucketGroup(100, 10, fakeClock)
    ref.consume("x", 100).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 50
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 51
    ref.consume("x", 1).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 350
    ref.consume("x", 2).futureValue mustBe 1
    ref.consume("x", 1).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 650
    ref.consume("x", 3).futureValue mustBe 4
    ref.consume("x", 4).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
  }

  it should "regain tokens at specified rate < 1" in {
    val fakeClock = new FakeClock
    val ref = new TokenBucketGroup(10, 0.1f, fakeClock)
    ref.consume("x", 10).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
    ref.consume("x", 0).futureValue mustBe 0

    fakeClock.ts += 9999
    ref.consume("x", 0).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
    ref.consume("x", 0).futureValue mustBe 0

    fakeClock.ts += 2
    ref.consume("x", 0).futureValue mustBe 1
    ref.consume("x", 1).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 30000
    ref.consume("x", 0).futureValue mustBe 3
    ref.consume("x", 2).futureValue mustBe 1
    ref.consume("x", 1).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 70000
    ref.consume("x", 0).futureValue mustBe 7
    ref.consume("x", 3).futureValue mustBe 4
    ref.consume("x", 4).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
  }

  it should "not overflow" in {
    val fakeClock = new FakeClock
    val ref = new TokenBucketGroup(1000, 100, fakeClock)
    ref.consume("x", 1000).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 100000
    ref.consume("x", 1000).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1

    fakeClock.ts += 1000000
    ref.consume("x", 1000).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
  }

  it should "not underflow" in {
    val fakeClock = new FakeClock
    val ref = new TokenBucketGroup(100, 10, fakeClock)
    ref.consume("x", 100).futureValue mustBe 0
    ref.consume("x", 100).futureValue mustBe -100
    ref.consume("x", 0).futureValue mustBe 0

    fakeClock.ts += 101
    ref.consume("x", 1).futureValue mustBe 0
    ref.consume("x", 1).futureValue mustBe -1
  }


  /**
    * NOTE: this realtime test might fail on slow machines
    */
  it should "regain tokens at specified rate with real clock" in {
    val ref = new TokenBucketGroup(200, 1000)
    ref.consume("x", 200).futureValue must be >= 0
    ref.consume("x", 200).futureValue must be < 0

    Thread.sleep(100)

    ref.consume("x", 100).futureValue must be >= 0
    ref.consume("x", 100).futureValue must be < 0

    Thread.sleep(200)

    ref.consume("x", 200).futureValue must be >= 0
    ref.consume("x", 200).futureValue must be < 0

    Thread.sleep(300)

    ref.consume("x", 200).futureValue must be >= 0
    ref.consume("x", 200).futureValue must be < 0
  }
}