/*
 * Copyright 2019 Ugljesa Jovanovic
 * Modifications Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ohs.bignum.decimal

import java.math.MathContext
import kotlin.test.Test

/**
 * Timing loop for the compact `BigDecimal` paths against `java.math.BigDecimal`. It only runs when
 * the `BIGNUM_BENCH` environment variable is set, so the regular suite stays fast:
 * ```
 * BIGNUM_BENCH=1 ./gradlew :bignum:jvmTest --tests '*BigDecimalCompactBenchmark*' -i
 * ```
 */
class BigDecimalCompactBenchmark {
  private inline fun time(label: String, iterations: Int, block: () -> Any?) {
    var sink = 0
    repeat(3) { repeat(iterations) { sink += block().hashCode() } }
    val start = System.nanoTime()
    repeat(iterations) { sink += block().hashCode() }
    val nanosPerOp = (System.nanoTime() - start).toDouble() / iterations
    println("BENCH %-26s %8.1f ns/op (sink=%d)".format(label, nanosPerOp, sink and 1))
  }

  @Test
  fun compactAgainstJava() {
    if (System.getenv("BIGNUM_BENCH") == null) {
      return
    }
    val iterations = 300_000
    // Results carry exactly the requested precision, so a 20-digit mode always needs a BigInteger
    // and shows the fallback cost; the 10-digit mode shows the compact path.
    val mode = DecimalMode(20, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    val smallMode = DecimalMode(10, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    val javaSmallContext = MathContext(10, java.math.RoundingMode.HALF_UP)
    val a = BigDecimal.parseString("1234.56")
    val b = BigDecimal.parseString("78.901")
    val inflatedA = a.inflatedForTesting()
    val inflatedB = b.inflatedForTesting()
    val javaA = java.math.BigDecimal("1234.56")
    val javaB = java.math.BigDecimal("78.901")
    val javaContext = MathContext(20, java.math.RoundingMode.HALF_UP)

    time("parse", iterations) { BigDecimal.parseString("1234.56") }
    time("java parse", iterations) { java.math.BigDecimal("1234.56") }
    time("add", iterations) { a + b }
    time("add (inflated)", iterations) { inflatedA + inflatedB }
    time("java add", iterations) { javaA.add(javaB) }
    time("multiply", iterations) { a * b }
    time("multiply (inflated)", iterations) { inflatedA * inflatedB }
    time("java multiply", iterations) { javaA.multiply(javaB) }
    time("add with mode 10", iterations) { a.add(b, smallMode) }
    time("add with mode 10 (infl.)", iterations) { inflatedA.add(inflatedB, smallMode) }
    time("java add with context 10", iterations) { javaA.add(javaB, javaSmallContext) }
    time("divide with mode 10", iterations) { a.divide(b, smallMode) }
    time("divide with mode 10 (infl.)", iterations) { inflatedA.divide(inflatedB, smallMode) }
    time("java divide with ctx 10", iterations) { javaA.divide(javaB, javaSmallContext) }
    time("add with mode 20", iterations) { a.add(b, mode) }
    time("add with mode 20 (infl.)", iterations) { inflatedA.add(inflatedB, mode) }
    time("java add with context 20", iterations) { javaA.add(javaB, javaContext) }
    time("divide with mode 20", iterations) { a.divide(b, mode) }
    time("divide with mode 20 (infl.)", iterations) { inflatedA.divide(inflatedB, mode) }
    time("java divide with ctx 20", iterations) { javaA.divide(javaB, javaContext) }
    time("compare", iterations) { a.compareTo(b) }
    time("java compare", iterations) { javaA.compareTo(javaB) }
    time("toString", iterations) { a.toString() }
    time("toString (inflated)", iterations) { inflatedA.toString() }
    time("java toString (cached)", iterations) { javaA.toString() }
    time("fromLong", iterations) { BigDecimal.fromLong(123456L) }
    time("java valueOf", iterations) { java.math.BigDecimal.valueOf(123456L) }
    time("US_CURRENCY add", iterations) {
      a.add(b, DecimalMode.US_CURRENCY)
    }
    time("US_CURRENCY add (inflated)", iterations) {
      inflatedA.add(inflatedB, DecimalMode.US_CURRENCY)
    }
  }
}
