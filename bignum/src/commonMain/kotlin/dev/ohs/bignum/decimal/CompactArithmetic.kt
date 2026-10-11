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

import dev.ohs.bignum.integer.Sign

/**
 * Overflow-checked `Long` arithmetic for the compact [BigDecimal] representation.
 *
 * A [BigDecimal] keeps its significand in a `Long` whenever the value fits, and only materialises a
 * `BigInteger` when an operation would overflow. Every helper here signals overflow by returning
 * [INFLATED], which is `Long.MIN_VALUE`. That value can never be a valid compact significand
 * because its magnitude does not fit in the 63-bit limbs the integer arithmetic uses, so it doubles
 * as the sentinel without losing any representable value.
 *
 * All helpers are pure and assume their inputs are not [INFLATED].
 */
internal object CompactArithmetic {
  /** Sentinel for "does not fit in a compact significand". */
  const val INFLATED: Long = Long.MIN_VALUE

  /** Largest power of ten that fits in a `Long`. */
  const val MAX_POWER_OF_TEN: Int = 18

  /** 10^0 through 10^18. */
  private val POWERS_OF_TEN: LongArray =
    LongArray(MAX_POWER_OF_TEN + 1).also { table ->
      table[0] = 1L
      for (i in 1..MAX_POWER_OF_TEN) {
        table[i] = table[i - 1] * 10L
      }
    }

  fun powerOfTen(exponent: Int): Long = POWERS_OF_TEN[exponent]

  /**
   * Number of decimal digits in the magnitude of [value]. Zero counts as one digit, matching
   * `BigInteger.numberOfDecimalDigits`.
   */
  fun digitCount(value: Long): Long {
    val magnitude = if (value < 0) -value else value
    // Binary search over the power table: 19 digits is the maximum for a Long magnitude.
    var digits = 1
    var hi = MAX_POWER_OF_TEN
    var lo = 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (magnitude >= POWERS_OF_TEN[mid]) {
        digits = mid + 1
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    return digits.toLong()
  }

  fun signOf(value: Long): Sign =
    when {
      value > 0L -> Sign.POSITIVE
      value < 0L -> Sign.NEGATIVE
      else -> Sign.ZERO
    }

  fun signumOf(value: Long): Int =
    when {
      value > 0L -> 1
      value < 0L -> -1
      else -> 0
    }

  fun isEven(value: Long): Boolean = (value and 1L) == 0L

  /** [first] + [second], or [INFLATED] if the sum does not fit. */
  fun add(first: Long, second: Long): Long {
    val sum = first + second
    // Overflow iff both operands have the same sign and the result has the opposite one.
    if (((first xor sum) and (second xor sum)) < 0L || sum == INFLATED) {
      return INFLATED
    }
    return sum
  }

  /** [first] - [second], or [INFLATED] if the difference does not fit. */
  fun subtract(first: Long, second: Long): Long {
    val difference = first - second
    if (((first xor second) and (first xor difference)) < 0L || difference == INFLATED) {
      return INFLATED
    }
    return difference
  }

  /** [first] * [second], or [INFLATED] if the product does not fit. */
  fun multiply(first: Long, second: Long): Long {
    if (first == 0L || second == 0L) {
      return 0L
    }
    val product = first * second
    val firstMagnitude = if (first < 0) -first else first
    val secondMagnitude = if (second < 0) -second else second
    // Both magnitudes below 2^31 cannot overflow, so skip the division on the common path.
    if ((firstMagnitude or secondMagnitude) ushr 31 != 0L) {
      if (product / second != first) {
        return INFLATED
      }
    }
    if (product == INFLATED) {
      return INFLATED
    }
    return product
  }

  /** [value] * 10^[exponent], or [INFLATED] if it does not fit or the exponent is out of range. */
  fun scaleByPowerOfTen(value: Long, exponent: Long): Long {
    if (value == 0L) {
      return 0L
    }
    if (exponent < 0L || exponent > MAX_POWER_OF_TEN) {
      return INFLATED
    }
    return multiply(value, POWERS_OF_TEN[exponent.toInt()])
  }

  /**
   * Truncating division of `dividend * 10^power` by [divisor] without materialising the scaled
   * dividend. The quotient goes to `out[0]` and the remainder (sign of the dividend, as with
   * `BigInteger.divrem`) to `out[1]`. Returns false when the quotient does not fit in a `Long`.
   *
   * The scaled dividend usually has more than 19 digits, so the division runs in chunks: the
   * running remainder is below the divisor's magnitude, so multiplying it by 10^s with `s = 18 -
   * digits(divisor)` cannot overflow, and each chunk needs a single `Long` division.
   */
  fun scaledDivide(dividend: Long, divisor: Long, power: Long, out: LongArray): Boolean {
    if (divisor == 0L) {
      return false
    }
    val dividendMagnitude = if (dividend < 0) -dividend else dividend
    val divisorMagnitude = if (divisor < 0) -divisor else divisor
    var quotient = dividendMagnitude / divisorMagnitude
    var remainder = dividendMagnitude % divisorMagnitude
    val step = (MAX_POWER_OF_TEN - digitCount(divisorMagnitude)).toInt().coerceAtLeast(1)
    var remaining = power
    while (remaining > 0) {
      val s = if (remaining < step) remaining.toInt() else step
      val scale = POWERS_OF_TEN[s]
      val scaledRemainder = multiply(remainder, scale)
      if (scaledRemainder == INFLATED) {
        return false
      }
      quotient = multiply(quotient, scale)
      if (quotient == INFLATED) {
        return false
      }
      quotient = add(quotient, scaledRemainder / divisorMagnitude)
      if (quotient == INFLATED) {
        return false
      }
      remainder = scaledRemainder % divisorMagnitude
      remaining -= s
    }
    val negativeQuotient = (dividend < 0) != (divisor < 0)
    out[0] = if (negativeQuotient) -quotient else quotient
    out[1] = if (dividend < 0) -remainder else remainder
    return true
  }

  /**
   * Parses an unsigned digit string into a compact value, or [INFLATED] when its value exceeds
   * [Long.MAX_VALUE]. Leading zeros carry no value and so never push a string over the limit; what
   * matters is the magnitude, the same rule the rest of the compact form uses.
   */
  fun parseDigits(digits: String): Long {
    if (digits.isEmpty()) {
      return INFLATED
    }
    var index = 0
    while (index < digits.length - 1 && digits[index] == '0') {
      index++
    }
    if (digits.length - index > MAX_COMPACT_DIGITS) {
      return INFLATED
    }
    var result = 0L
    while (index < digits.length) {
      val digit = digits[index] - '0'
      if (digit < 0 || digit > 9) {
        return INFLATED
      }
      result = accumulateDigit(result, digit)
      if (result < 0L) {
        return INFLATED
      }
      index++
    }
    return result
  }

  /** Digits a compact magnitude can have: Long.MAX_VALUE is 9223372036854775807. */
  const val MAX_COMPACT_DIGITS: Int = 19

  /** `value * 10 + digit`, or -1 when that would exceed [Long.MAX_VALUE]. */
  fun accumulateDigit(value: Long, digit: Int): Long =
    if (value > (Long.MAX_VALUE - digit) / 10L) -1L else value * 10L + digit

  /** Strips trailing decimal zeros from a non-zero value. */
  fun removeTrailingZeros(value: Long): Long {
    var result = value
    while (result != 0L && result % 10L == 0L) {
      result /= 10L
    }
    return result
  }

  /** Hash of a compact significand, equal to the hash of the equivalent single-word BigInteger. */
  fun significandHashCode(value: Long): Int {
    val magnitude = if (value < 0) (-value).toULong() else value.toULong()
    return magnitude.hashCode() + signOf(value).hashCode()
  }
}
