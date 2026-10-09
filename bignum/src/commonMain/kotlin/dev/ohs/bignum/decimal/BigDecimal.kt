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

import dev.ohs.bignum.BigNumber
import dev.ohs.bignum.CommonBigNumberOperations
import dev.ohs.bignum.NarrowingOperations
import dev.ohs.bignum.integer.BigInteger
import dev.ohs.bignum.integer.Platform
import dev.ohs.bignum.integer.RuntimePlatform
import dev.ohs.bignum.integer.Sign
import dev.ohs.bignum.integer.chosenArithmetic
import dev.ohs.bignum.integer.toBigInteger
import dev.ohs.bignum.integer.util.times
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of floating-point arbitrary precision arithmetic.
 *
 * Each object of this class represents an immutable large floating point number. The underlying
 * implementation uses [BigInteger] to represent sign and significand, and [Long] to represent the
 * exponent.
 *
 * [DecimalMode] defines the precision and desired [RoundingMode] when using instances of this class
 * in arithmetic operations, but if [DecimalMode] is supplied to the operation it will override
 * instances [DecimalMode]
 *
 * Scale, or the number of digits to the right of the decimal, can also be specified. Default is no
 * scale, which puts no restriction on number of digits to the right of the decimal. When scale is
 * specified, a [RoundingMode] other than [RoundingMode.NONE] is also required.
 *
 * When arithmetic operations have both operands unlimited precision and no scaling, the result is
 * also unlimited precision and no scale. When an operation mixes an unlimited precision operand and
 * a scaled operand, the result is unlimited precision. When both operands have scale, whether
 * unlimited precision or limited precision, then these rules for scale of the result are used:
 * <ul>
 * <li>add, subtract - max of the two scales</li>
 * <li>multiply - sum of the two scales</li>
 * <li>divide - min of the two scales</li>
 * </ul>
 *
 * Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 23-Mar-2019
 */
class BigDecimal
private constructor(
  _compact: Long,
  _inflated: BigInteger?,
  _exponent: Long,
  _decimalMode: DecimalMode?,
  _applyScale: Boolean = true,
) :
  BigNumber<BigDecimal>,
  CommonBigNumberOperations<BigDecimal>,
  NarrowingOperations<BigDecimal>,
  Comparable<Any> {

  /**
   * Builds from a [BigInteger] significand, storing it compactly when its magnitude fits in a
   * single 63-bit word. Every single-word magnitude fits in a `Long`, so the only value that needs
   * the inflated form is one with two or more words.
   */
  private constructor(
    _significand: BigInteger,
    _exponent: Long = 0L,
    _decimalMode: DecimalMode? = null,
  ) : this(compactOf(_significand), _significand, _exponent, _decimalMode)

  /**
   * Number of decimal digits in [significand]. Counting digits costs a division for large values,
   * and most intermediate results of a calculation are never asked for it, so it is computed on
   * first use rather than in the constructor. Scaled decimals set it eagerly because their
   * [decimalMode] is derived from it.
   */
  private var precisionCache: Long = -1L

  val precision: Long
    get() {
      var cached = precisionCache
      if (cached < 0L) {
        cached =
          if (compact != CompactArithmetic.INFLATED) {
            CompactArithmetic.digitCount(compact)
          } else {
            significand.numberOfDecimalDigits()
          }
        precisionCache = cached
      }
      return cached
    }

  /**
   * The significand as a `Long`, or [CompactArithmetic.INFLATED] when it does not fit. Nearly all
   * real-world decimals have fewer than 19 digits, so the arithmetic below works on this field and
   * only falls back to [significand] when a result overflows.
   */
  private val compact: Long

  /**
   * The [BigInteger] form of the significand. Always set when [compact] is inflated, and filled on
   * first use otherwise. A value computed twice under contention is identical, so no locking is
   * needed.
   */
  private var inflatedCache: BigInteger?

  val significand: BigInteger
    get() {
      inflatedCache?.let {
        return it
      }
      val materialised = BigInteger.fromLong(compact)
      inflatedCache = materialised
      return materialised
    }

  val exponent: Long
  val decimalMode: DecimalMode?

  private val isCompact: Boolean
    get() = compact != CompactArithmetic.INFLATED

  init {
    if (_applyScale && _decimalMode != null && _decimalMode.usingScale) {
      val wrk =
        if (_compact != CompactArithmetic.INFLATED) {
          applyScaleCompact(_compact, _exponent, _decimalMode)
            ?: applyScale(_inflated ?: BigInteger.fromLong(_compact), _exponent, _decimalMode)
        } else {
          applyScale(_inflated!!, _exponent, _decimalMode)
        }
      compact = wrk.compact
      inflatedCache = wrk.inflatedCache
      if (wrk.isZero().not()) {
        exponent = wrk.exponent
        val newPrecision = wrk.precision
        precisionCache = newPrecision
        decimalMode = _decimalMode.copy(decimalPrecision = newPrecision)
      } else {
        exponent = wrk.exponent.times(_decimalMode.decimalPrecision + _decimalMode.scale)
        precisionCache = _decimalMode.decimalPrecision + _decimalMode.scale
        decimalMode = _decimalMode.copy(decimalPrecision = precisionCache)
      }
    } else {
      compact = _compact
      inflatedCache = _inflated
      exponent = _exponent
      decimalMode = _decimalMode
    }
  }

  /** Same value with a different exponent or mode, keeping the compact form when there is one. */
  private fun rebuild(
    exponent: Long = this.exponent,
    decimalMode: DecimalMode? = this.decimalMode,
  ) = BigDecimal(compact, inflatedCache, exponent, decimalMode)

  /** Whether the significand is currently held as a `Long`. Exposed for tests. */
  internal fun isCompactForTesting(): Boolean = isCompact

  /**
   * The same value held only as a [BigInteger], with every cached field copied. Tests use it to
   * check that the compact fast paths produce exactly what the BigInteger paths produce.
   */
  internal fun inflatedForTesting(): BigDecimal {
    val copy =
      BigDecimal(
        CompactArithmetic.INFLATED,
        significand,
        exponent,
        decimalMode,
        _applyScale = false,
      )
    copy.precisionCache = precisionCache
    return copy
  }

  /**
   * [precisionLimit] is zero if precision unlimited. Otherwise returns
   * [DecimalMode.decimalPrecision].
   */
  val precisionLimit = decimalMode?.decimalPrecision ?: 0
  val roundingMode = decimalMode?.roundingMode ?: RoundingMode.NONE

  val scale = decimalMode?.scale ?: -1
  val usingScale = scale >= 0

  companion object : BigNumber.Creator<BigDecimal> {
    override val ZERO = fromCompact(0L, 0L, null)
    override val ONE = fromCompact(1L, 0L, null)
    override val TWO = fromCompact(2L, 0L, null)
    override val TEN = fromCompact(10L, 1L, null)

    var useToStringExpanded: Boolean = false

    /** Lets tests compare the single-pass parser against the general one on the same input. */
    internal var compactParseEnabledForTesting: Boolean = true

    /**
     * The compact form of [bigInteger], or [CompactArithmetic.INFLATED] when its magnitude needs
     * more than one 63-bit word.
     */
    private fun compactOf(bigInteger: BigInteger): Long {
      val magnitude = bigInteger.magnitude
      return when (magnitude.size) {
        0 -> 0L
        1 -> {
          val word = magnitude[0]
          if (word > Long.MAX_VALUE.toULong()) {
            CompactArithmetic.INFLATED
          } else {
            word.toLong() * bigInteger.signum()
          }
        }
        else -> CompactArithmetic.INFLATED
      }
    }

    private fun fromCompact(significand: Long, exponent: Long, decimalMode: DecimalMode?) =
      BigDecimal(significand, null, exponent, decimalMode)

    /**
     * Compact-or-fallback constructor: builds from a `Long` when [significand] is not inflated, and
     * otherwise from the supplied [BigInteger] producer.
     */
    private inline fun fromCompactOr(
      significand: Long,
      exponent: Long,
      decimalMode: DecimalMode?,
      inflated: () -> BigInteger,
    ): BigDecimal =
      if (significand != CompactArithmetic.INFLATED) {
        fromCompact(significand, exponent, decimalMode)
      } else {
        BigDecimal(inflated(), exponent, decimalMode)
      }

    /**
     * Powers of 10 which can be represented exactly in `double`. From java BigDecimal, hopefully
     * significantly more efficient for most conversions than toStrings.
     */
    private val double10pow =
      doubleArrayOf(
        1.0e0,
        1.0e1,
        1.0e2,
        1.0e3,
        1.0e4,
        1.0e5,
        1.0e6,
        1.0e7,
        1.0e8,
        1.0e9,
        1.0e10,
        1.0e11,
        1.0e12,
        1.0e13,
        1.0e14,
        1.0e15,
        1.0e16,
        1.0e17,
        1.0e18,
        1.0e19,
        1.0e20,
        1.0e21,
        1.0e22,
      )
    private val maximumDouble = fromDouble(Double.MAX_VALUE)
    private val leastSignificantDouble = fromDouble(Double.MIN_VALUE)

    /** Powers of 10 which can be represented exactly in {@code float}. */
    private val float10pow =
      floatArrayOf(
        1.0e0f,
        1.0e1f,
        1.0e2f,
        1.0e3f,
        1.0e4f,
        1.0e5f,
        1.0e6f,
        1.0e7f,
        1.0e8f,
        1.0e9f,
        1.0e10f,
      )
    private val maximumFloat = fromFloat(Float.MAX_VALUE)
    private val leastSignificantFloat = fromFloat(Float.MIN_VALUE)

    private fun roundOrDont(
      significand: BigInteger,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal {
      return if (decimalMode.isPrecisionUnlimited) {
        BigDecimal(significand, exponent)
      } else {
        roundSignificand(significand, exponent, decimalMode)
      }
    }

    private enum class SignificantDecider {
      FIVE,
      LESS_THAN_FIVE,
      MORE_THAN_FIVE,
      ZERO,
    }

    // ── Compact (Long) rounding ──────────────────────────────────────────────
    //
    // Each function below mirrors the BigInteger function of the same name step for step, and
    // returns null (or INFLATED) when an intermediate value would not fit in a Long. The caller
    // then runs the BigInteger version from scratch, so a fallback never produces a partially
    // compact result.

    /** [roundOrDont] on a compact significand, or null when the rounding does not fit. */
    private fun roundOrDontCompact(
      significand: Long,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal? {
      return if (decimalMode.isPrecisionUnlimited) {
        fromCompact(significand, exponent, null)
      } else {
        roundSignificandCompact(significand, exponent, decimalMode)
      }
    }

    /** [roundOrDont] that keeps the compact form of [value] when it has one. */
    private fun roundOrDont(
      value: BigDecimal,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal {
      if (value.isCompact) {
        roundOrDontCompact(value.compact, exponent, decimalMode)?.let {
          return it
        }
      }
      return roundOrDont(value.significand, exponent, decimalMode)
    }

    private fun determineDeciderCompact(discarded: Long): SignificantDecider {
      val scale =
        CompactArithmetic.powerOfTen((CompactArithmetic.digitCount(discarded) - 1).toInt())
      val significant = (discarded / scale).let { if (it < 0) -it else it }
      val rest = (discarded % scale).let { if (it < 0) -it else it }
      return when {
        significant == 5L -> {
          if (rest == 0L) {
            SignificantDecider.FIVE
          } else {
            SignificantDecider.MORE_THAN_FIVE
          }
        }
        significant > 5L -> SignificantDecider.MORE_THAN_FIVE
        significant < 5L -> SignificantDecider.LESS_THAN_FIVE
        else -> throw RuntimeException("Couldn't determine decider")
      }
    }

    /** [roundDiscarded] on compact values, or INFLATED when a step does not fit. */
    private fun roundDiscardedCompact(
      significand: Long,
      discarded: Long,
      decimalMode: DecimalMode,
    ): Long {
      val toDiscard = CompactArithmetic.digitCount(significand) - decimalMode.decimalPrecision
      val result: Long
      val remainder: Long
      if (toDiscard > 0) {
        if (toDiscard > CompactArithmetic.MAX_POWER_OF_TEN) {
          return CompactArithmetic.INFLATED
        }
        val divisor = CompactArithmetic.powerOfTen(toDiscard.toInt())
        result = significand / divisor
        remainder = CompactArithmetic.add(significand % divisor, discarded / divisor)
        if (remainder == CompactArithmetic.INFLATED) {
          return CompactArithmetic.INFLATED
        }
      } else {
        result = significand
        remainder = discarded
      }
      val decider = determineDeciderCompact(remainder)
      return roundWithDeciderCompact(
        result,
        decimalMode.roundingMode,
        CompactArithmetic.signOf(significand),
        decider,
        CompactArithmetic.isEven(result),
      )
    }

    /** [roundWithDecider] on a compact value, or INFLATED if the adjustment overflows. */
    private fun roundWithDeciderCompact(
      numberToRound: Long,
      roundingMode: RoundingMode,
      sign: Sign,
      decider: SignificantDecider,
      isEven: Boolean,
    ): Long {
      if (decider == SignificantDecider.ZERO) {
        return numberToRound
      }
      val up = CompactArithmetic.add(numberToRound, 1L)
      val down = CompactArithmetic.subtract(numberToRound, 1L)
      return when (roundingMode) {
        RoundingMode.AWAY_FROM_ZERO -> if (sign == Sign.POSITIVE) up else down
        RoundingMode.TOWARDS_ZERO -> numberToRound
        RoundingMode.CEILING -> if (sign == Sign.POSITIVE) up else numberToRound
        RoundingMode.FLOOR -> if (sign == Sign.POSITIVE) numberToRound else down
        RoundingMode.ROUND_HALF_AWAY_FROM_ZERO ->
          when (sign) {
            Sign.POSITIVE -> if (decider != SignificantDecider.LESS_THAN_FIVE) up else numberToRound
            Sign.NEGATIVE ->
              if (decider != SignificantDecider.LESS_THAN_FIVE) down else numberToRound
            Sign.ZERO -> numberToRound
          }
        RoundingMode.ROUND_HALF_TOWARDS_ZERO ->
          when (sign) {
            Sign.POSITIVE -> if (decider == SignificantDecider.MORE_THAN_FIVE) up else numberToRound
            Sign.NEGATIVE ->
              if (decider == SignificantDecider.MORE_THAN_FIVE) down else numberToRound
            Sign.ZERO -> numberToRound
          }
        RoundingMode.ROUND_HALF_CEILING ->
          when (sign) {
            Sign.POSITIVE -> if (decider != SignificantDecider.LESS_THAN_FIVE) up else numberToRound
            Sign.NEGATIVE ->
              if (decider == SignificantDecider.MORE_THAN_FIVE) down else numberToRound
            Sign.ZERO -> numberToRound
          }
        RoundingMode.ROUND_HALF_FLOOR ->
          when (sign) {
            Sign.POSITIVE -> if (decider == SignificantDecider.MORE_THAN_FIVE) up else numberToRound
            Sign.NEGATIVE ->
              if (decider != SignificantDecider.LESS_THAN_FIVE) down else numberToRound
            Sign.ZERO -> numberToRound
          }
        RoundingMode.ROUND_HALF_TO_EVEN ->
          when (decider) {
            SignificantDecider.FIVE ->
              if (isEven) {
                numberToRound
              } else {
                when (sign) {
                  Sign.POSITIVE -> up
                  Sign.NEGATIVE -> down
                  Sign.ZERO -> numberToRound
                }
              }
            SignificantDecider.MORE_THAN_FIVE ->
              when (sign) {
                Sign.POSITIVE -> up
                Sign.NEGATIVE -> down
                Sign.ZERO -> numberToRound
              }
            else -> numberToRound
          }
        RoundingMode.ROUND_HALF_TO_ODD ->
          when (decider) {
            SignificantDecider.FIVE ->
              if (isEven.not()) {
                numberToRound
              } else {
                when (sign) {
                  Sign.POSITIVE -> up
                  Sign.NEGATIVE -> down
                  Sign.ZERO -> numberToRound
                }
              }
            SignificantDecider.MORE_THAN_FIVE ->
              when (sign) {
                Sign.POSITIVE -> up
                Sign.NEGATIVE -> down
                Sign.ZERO -> numberToRound
              }
            SignificantDecider.LESS_THAN_FIVE -> numberToRound
            SignificantDecider.ZERO -> numberToRound
          }
        RoundingMode.NONE ->
          throw ArithmeticException(
            "Non-terminating result of division operation " +
              "(i.e. 1/3 = 0.3333... library needs to know when to stop and how to round up at that point). " +
              "Specify rounding mode inside your decimal mode."
          )
      }
    }

    /** [handleZeroRounding] on a compact value, or null if the adjustment overflows. */
    private fun handleZeroRoundingCompact(
      significand: Long,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal? {
      val adjusted =
        when {
          significand > 0L ->
            when (decimalMode.roundingMode) {
              RoundingMode.CEILING,
              RoundingMode.AWAY_FROM_ZERO -> CompactArithmetic.add(significand, 1L)
              else -> significand
            }
          significand < 0L ->
            when (decimalMode.roundingMode) {
              RoundingMode.FLOOR,
              RoundingMode.AWAY_FROM_ZERO -> CompactArithmetic.subtract(significand, 1L)
              else -> significand
            }
          else -> significand
        }
      if (adjusted == CompactArithmetic.INFLATED) {
        return null
      }
      if (adjusted == significand) {
        return fromCompact(significand, exponent, decimalMode)
      }
      val exponentModifier =
        CompactArithmetic.digitCount(adjusted) - CompactArithmetic.digitCount(significand)
      return fromCompact(adjusted, exponent + exponentModifier, decimalMode)
    }

    /** [roundSignificand] on a compact value, or null when the rounding does not fit in a Long. */
    private fun roundSignificandCompact(
      significand: Long,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal? {
      if (significand == 0L) {
        return fromCompact(0L, exponent, decimalMode)
      }
      val significandDigits = CompactArithmetic.digitCount(significand)
      val desiredPrecision =
        if (decimalMode.usingScale) {
          decimalMode.decimalPrecision + decimalMode.scale
        } else {
          decimalMode.decimalPrecision
        }
      return when {
        desiredPrecision > significandDigits && !decimalMode.usingScale -> {
          val extendedSignificand =
            CompactArithmetic.scaleByPowerOfTen(significand, desiredPrecision - significandDigits)
          if (extendedSignificand == CompactArithmetic.INFLATED) {
            return null
          }
          fromCompact(extendedSignificand, exponent, decimalMode)
        }

        desiredPrecision < significandDigits -> {
          val dropped = significandDigits - desiredPrecision
          if (dropped > CompactArithmetic.MAX_POWER_OF_TEN) {
            return null
          }
          val divisor = CompactArithmetic.powerOfTen(dropped.toInt())
          val quotient = significand / divisor
          val remainder = significand % divisor
          if (remainder == 0L) {
            return fromCompact(quotient, exponent, decimalMode)
          }
          // Check if remainder was .0XXX if so handle it
          if (
            significandDigits ==
              CompactArithmetic.digitCount(quotient) + CompactArithmetic.digitCount(remainder)
          ) {
            val newSignificand = roundDiscardedCompact(quotient, remainder, decimalMode)
            if (newSignificand == CompactArithmetic.INFLATED) {
              return null
            }
            val exponentModifier =
              CompactArithmetic.digitCount(newSignificand) - CompactArithmetic.digitCount(quotient)
            fromCompact(newSignificand, exponent + exponentModifier, decimalMode)
          } else {
            handleZeroRoundingCompact(quotient, exponent, decimalMode)
          }
        }

        else -> {
          fromCompact(significand, exponent, decimalMode)
        }
      }
    }

    /** [applyScale] on a compact value, or null when a step does not fit in a Long. */
    private fun applyScaleCompact(
      significand: Long,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal? {
      val workMode =
        if (exponent >= 0) {
          DecimalMode(exponent + decimalMode.scale + 1, decimalMode.roundingMode)
        } else {
          DecimalMode(decimalMode.scale + 1, decimalMode.roundingMode)
        }
      if (exponent >= 0) {
        return roundSignificandCompact(significand, exponent, workMode)
      }
      val signum = CompactArithmetic.signumOf(significand)
      val nudge =
        if (decimalMode.roundingMode == RoundingMode.ROUND_HALF_TO_EVEN) signum * 2 else signum
      val temp = fromCompact(significand, exponent, null) + nudge
      if (!temp.isCompact) {
        return null
      }
      val rounded = roundSignificandCompact(temp.compact, temp.exponent, workMode) ?: return null
      return rounded - nudge
    }

    private fun determineDecider(discarded: BigInteger): SignificantDecider {
      val scale = (BigInteger.TEN.pow(discarded.numberOfDecimalDigits() - 1))
      val divrem = discarded.divrem(scale)
      val significant = divrem.quotient.abs().intValue(true)
      val rest = divrem.remainder.abs()
      return when {
        significant == 5 -> {
          if (rest == BigInteger.ZERO) {
            SignificantDecider.FIVE
          } else {
            SignificantDecider.MORE_THAN_FIVE
          }
        }

        significant > 5 -> SignificantDecider.MORE_THAN_FIVE
        significant < 5 -> SignificantDecider.LESS_THAN_FIVE
        else -> throw RuntimeException("Couldn't determine decider")
      }
    }

    /**
     * Use this rounding when part of number needs to be discarded because the precision is
     * narrowing or extended because precision is increasing. Discarded parameter influences the
     * least significant digit of the result
     */
    @Suppress("UNUSED_EXPRESSION")
    private fun roundDiscarded(
      significand: BigInteger,
      discarded: BigInteger,
      decimalMode: DecimalMode,
    ): BigInteger {
      val toDiscard = significand.numberOfDecimalDigits() - decimalMode.decimalPrecision
      val (result, remainder) =
        if (toDiscard > 0) {
          val additionallyDiscarded = (significand divrem BigInteger.TEN.pow(toDiscard))
          Pair(
            additionallyDiscarded.quotient,
            additionallyDiscarded.remainder + discarded / BigInteger.TEN.pow(toDiscard),
          )
        } else {
          Pair(significand, discarded)
        }

      val decider = determineDecider(remainder)
      val isEven = result.isEven()
      val modifiedResult =
        roundWithDecider(result, decimalMode.roundingMode, significand.sign, decider, isEven)

      return modifiedResult
    }

    private fun roundWithDecider(
      numberToRound: BigInteger,
      roundingMode: RoundingMode,
      sign: Sign,
      decider: SignificantDecider,
      isEven: Boolean,
    ): BigInteger {
      if (decider == SignificantDecider.ZERO) {
        return numberToRound
      }
      return when (roundingMode) {
        RoundingMode.AWAY_FROM_ZERO -> {
          if (sign == Sign.POSITIVE) {
            numberToRound + 1
          } else {
            numberToRound - 1
          }
        }

        RoundingMode.TOWARDS_ZERO -> {
          numberToRound
        }

        RoundingMode.CEILING -> {
          if (sign == Sign.POSITIVE) {
            numberToRound + 1
          } else {
            numberToRound
          }
        }

        RoundingMode.FLOOR -> {
          if (sign == Sign.POSITIVE) {
            numberToRound
          } else {
            numberToRound - 1
          }
        }

        RoundingMode.ROUND_HALF_AWAY_FROM_ZERO -> {
          when (sign) {
            Sign.POSITIVE -> {
              if (decider != SignificantDecider.LESS_THAN_FIVE) {
                numberToRound + 1
              } else {
                numberToRound
              }
            }

            Sign.NEGATIVE -> {
              if (decider != SignificantDecider.LESS_THAN_FIVE) {
                numberToRound - 1
              } else {
                numberToRound
              }
            }

            Sign.ZERO -> {
              numberToRound
            }
          }
        }

        RoundingMode.ROUND_HALF_TOWARDS_ZERO -> {
          when (sign) {
            Sign.POSITIVE -> {
              if (decider == SignificantDecider.MORE_THAN_FIVE) {
                numberToRound + 1
              } else {
                numberToRound
              }
            }

            Sign.NEGATIVE -> {
              if (decider == SignificantDecider.MORE_THAN_FIVE) {
                numberToRound - 1
              } else {
                numberToRound
              }
            }

            Sign.ZERO -> {
              numberToRound
            }
          }
        }

        RoundingMode.ROUND_HALF_CEILING -> {
          when (sign) {
            Sign.POSITIVE -> {
              if (decider != SignificantDecider.LESS_THAN_FIVE) {
                numberToRound + 1
              } else {
                numberToRound
              }
            }

            Sign.NEGATIVE -> {
              if (decider == SignificantDecider.MORE_THAN_FIVE) {
                numberToRound - 1
              } else {
                numberToRound
              }
            }

            Sign.ZERO -> {
              numberToRound
            }
          }
        }

        RoundingMode.ROUND_HALF_FLOOR -> {
          when (sign) {
            Sign.POSITIVE -> {
              if (decider == SignificantDecider.MORE_THAN_FIVE) {
                numberToRound + 1
              } else {
                numberToRound
              }
            }

            Sign.NEGATIVE -> {
              if (decider != SignificantDecider.LESS_THAN_FIVE) {
                numberToRound - 1
              } else {
                numberToRound
              }
            }

            Sign.ZERO -> {
              numberToRound
            }
          }
        }

        RoundingMode.ROUND_HALF_TO_EVEN -> {
          when (decider) {
            SignificantDecider.FIVE -> {
              // significand was here
              if (isEven) {
                numberToRound
              } else {
                // RoundingMode.HALF_CEILING if the digit to the left of the discarded fraction is
                // odd
                when (sign) {
                  Sign.POSITIVE -> {
                    numberToRound + 1
                  }

                  Sign.NEGATIVE -> {
                    numberToRound - 1
                  }

                  Sign.ZERO -> {
                    numberToRound
                  }
                }
              }
            }

            SignificantDecider.MORE_THAN_FIVE -> {
              when (sign) {
                Sign.POSITIVE -> numberToRound + 1
                Sign.NEGATIVE -> numberToRound - 1
                Sign.ZERO -> numberToRound
              }
            }

            else -> {
              numberToRound
            }
          }
        }

        RoundingMode.ROUND_HALF_TO_ODD -> {
          when (decider) {
            SignificantDecider.FIVE -> {
              if (isEven.not()) {
                numberToRound
              } else {
                // RoundingMode.HALF_CEILING if the digit to the left of the discarded fraction is
                // even
                when (sign) {
                  Sign.POSITIVE -> {
                    numberToRound + 1
                  }

                  Sign.NEGATIVE -> {
                    numberToRound - 1
                  }

                  Sign.ZERO -> {
                    numberToRound
                  }
                }
              }
            }

            SignificantDecider.MORE_THAN_FIVE -> {
              when (sign) {
                Sign.POSITIVE -> numberToRound + 1
                Sign.NEGATIVE -> numberToRound - 1
                Sign.ZERO -> numberToRound
              }
            }

            SignificantDecider.LESS_THAN_FIVE -> {
              numberToRound
            }

            SignificantDecider.ZERO -> {
              numberToRound
            }
          }
        }

        RoundingMode.NONE -> {
          throw ArithmeticException(
            "Non-terminating result of division operation " +
              "(i.e. 1/3 = 0.3333... library needs to know when to stop and how to round up at that point). " +
              "Specify rounding mode inside your decimal mode."
          )
        }
      }
    }

    fun handleZeroRounding(
      significand: BigInteger,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal {
      return when {
        significand.sign == Sign.POSITIVE -> {
          when (decimalMode.roundingMode) {
            RoundingMode.CEILING,
            RoundingMode.AWAY_FROM_ZERO -> {
              val increasedSignificand = significand.inc()
              val exponentModifier =
                increasedSignificand.numberOfDecimalDigits() - significand.numberOfDecimalDigits()
              BigDecimal(increasedSignificand, exponent + exponentModifier, decimalMode)
            }

            else -> BigDecimal(significand, exponent, decimalMode)
          }
        }

        significand.sign == Sign.NEGATIVE -> {
          when (decimalMode.roundingMode) {
            RoundingMode.FLOOR,
            RoundingMode.AWAY_FROM_ZERO -> {
              val increasedSignificand = significand.dec()
              val exponentModifier =
                increasedSignificand.numberOfDecimalDigits() - significand.numberOfDecimalDigits()
              BigDecimal(increasedSignificand, exponent + exponentModifier, decimalMode)
            }

            else -> BigDecimal(significand, exponent, decimalMode)
          }
        }

        else -> BigDecimal(significand, exponent, decimalMode)
      }
    }

    private fun roundSignificand(
      significand: BigInteger,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal {
      if (significand.isZero()) {
        return BigDecimal(BigInteger.ZERO, exponent, decimalMode)
      }
      val significandDigits = significand.numberOfDecimalDigits()
      val desiredPrecision =
        if (decimalMode.usingScale) {
          decimalMode.decimalPrecision + decimalMode.scale
        } else {
          decimalMode.decimalPrecision
        }
      return when {
        desiredPrecision > significandDigits && !decimalMode.usingScale -> {
          val extendedSignificand =
            significand * BigInteger.TEN.pow(desiredPrecision - significandDigits)
          BigDecimal(extendedSignificand, exponent, decimalMode)
        }

        desiredPrecision < significandDigits -> {
          val divRem = significand divrem BigInteger.TEN.pow(significandDigits - desiredPrecision)
          val resolvedRemainder = divRem.remainder
          if (divRem.remainder == BigInteger.ZERO) {
            return BigDecimal(divRem.quotient, exponent, decimalMode)
          }
          // Check if remainder was .0XXX if so handle it
          if (
            significand.numberOfDecimalDigits() ==
              divRem.quotient.numberOfDecimalDigits() + divRem.remainder.numberOfDecimalDigits()
          ) {
            val newSignificand = roundDiscarded(divRem.quotient, resolvedRemainder, decimalMode)
            val exponentModifier =
              newSignificand.numberOfDecimalDigits() - divRem.quotient.numberOfDecimalDigits()
            BigDecimal(newSignificand, exponent + exponentModifier, decimalMode)
          } else {
            handleZeroRounding(divRem.quotient, exponent, decimalMode)
          }
        }

        else -> {
          BigDecimal(significand, exponent, decimalMode)
        }
      }
    }

    /**
     * Round the BigDecimal to a specific length AFTER the decimal point (scale). If position is set
     * to 0 a integer value is returned
     *
     * I.e.
     *
     * 1234.5678 digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 1234.568 123.456
     * digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 123.456 0.0012345678
     * digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 0.001 0.0012345678
     * digitPosition 5, rounding mode HALF_TOWARDS_ZERO will produce 0.00123
     */
    private fun applyScale(
      significand: BigInteger,
      exponent: Long,
      decimalMode: DecimalMode,
    ): BigDecimal {
      if (!decimalMode.usingScale) {
        return BigDecimal(significand, exponent, decimalMode)
      }
      val workMode =
        when {
          exponent >= 0 ->
            DecimalMode(
              exponent + decimalMode.scale + 1,
              decimalMode.roundingMode,
            )

          exponent < 0 ->
            DecimalMode(
              decimalMode.scale + 1,
              decimalMode.roundingMode,
            )

          else -> throw RuntimeException("Unexpected state")
        }
      return if (exponent >= 0) {
        roundSignificand(significand, exponent, workMode)
      } else {
        if (decimalMode.roundingMode == RoundingMode.ROUND_HALF_TO_EVEN) {
          val temp = BigDecimal(significand, exponent) + (significand.signum() * 2)
          roundSignificand(temp.significand, temp.exponent, workMode) - (significand.signum() * 2)
        } else {
          val temp = BigDecimal(significand, exponent) + significand.signum()
          roundSignificand(temp.significand, temp.exponent, workMode) - significand.signum()
        }
      }
    }

    /**
     * New BigDecimal from existing, with different DecimalMode.
     *
     * @param bigDecimal Long value to conver
     * @return BigDecimal representing input
     */
    fun fromBigDecimal(bigDecimal: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
      return bigDecimal.rebuild(decimalMode = decimalMode).roundSignificand(decimalMode)
    }

    /**
     * Convert a Long into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param bigInteger Long value to conver
     * @return BigDecimal representing input
     */
    fun fromBigInteger(bigInteger: BigInteger, decimalMode: DecimalMode? = null): BigDecimal {
      return BigDecimal(bigInteger, bigInteger.numberOfDecimalDigits() - 1, decimalMode)
        .roundSignificand(decimalMode)
    }

    /** A compact value with its exponent set so the decimal point follows the first digit. */
    private fun fromCompactValue(value: Long, decimalMode: DecimalMode?): BigDecimal =
      fromCompact(value, CompactArithmetic.digitCount(value) - 1, decimalMode)
        .roundSignificand(decimalMode)

    /**
     * Convert a Long into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param long Long value to conver
     * @return BigDecimal representing input
     */
    fun fromLong(long: Long, decimalMode: DecimalMode? = null): BigDecimal {
      if (long == CompactArithmetic.INFLATED) {
        val bigint = BigInteger.fromLong(long)
        return BigDecimal(bigint, bigint.numberOfDecimalDigits() - 1, decimalMode)
          .roundSignificand(decimalMode)
      }
      return fromCompactValue(long, decimalMode)
    }

    /**
     * Convert a Long into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param uLong Long value to conver
     * @return BigDecimal representing input
     */
    fun fromULong(uLong: ULong, decimalMode: DecimalMode? = null): BigDecimal {
      if (uLong > Long.MAX_VALUE.toULong()) {
        val bigint = BigInteger.fromULong(uLong)
        return BigDecimal(bigint, bigint.numberOfDecimalDigits() - 1, decimalMode)
          .roundSignificand(decimalMode)
      }
      return fromCompactValue(uLong.toLong(), decimalMode)
    }

    /**
     * Convert a Int into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param int Int value to conver
     * @return BigDecimal representing input
     */
    fun fromInt(int: Int, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(int.toLong(), decimalMode)

    /**
     * Convert a Int into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param uInt Int value to conver
     * @return BigDecimal representing input
     */
    fun fromUInt(uInt: UInt, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(uInt.toLong(), decimalMode)

    /**
     * Convert a Short into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param uShort Short value to conver
     * @return BigDecimal representing input
     */
    fun fromUShort(uShort: UShort, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(uShort.toLong(), decimalMode)

    /**
     * Convert a Short into a BigDecimal.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param short Short value to conver
     * @return BigDecimal representing input
     */
    fun fromShort(short: Short, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(short.toLong(), decimalMode)

    /**
     * Convert a Byte into a BigDecimal.
     *
     * i.e. 11 -> 1.1E+2
     *
     * @param uByte Byte value to conver
     * @return BigDecimal representing input
     */
    fun fromUByte(uByte: UByte, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(uByte.toLong(), decimalMode)

    /**
     * Convert a Byte into a BigDecimal.
     *
     * i.e. 11 -> 1.1E+2
     *
     * @param byte Byte value to conver
     * @return BigDecimal representing input
     */
    fun fromByte(byte: Byte, decimalMode: DecimalMode? = null): BigDecimal =
      fromCompactValue(byte.toLong(), decimalMode)

    /**
     * Convert a Long into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param long Long value to conver
     * @return BigDecimal representing input
     */
    fun fromLongAsSignificand(long: Long, decimalMode: DecimalMode? = null) =
      fromCompactOr(long, 0L, decimalMode) { BigInteger.fromLong(long) }
        .roundSignificand(decimalMode)

    /**
     * Convert a Int into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param int Int value to conver
     * @return BigDecimal representing input
     */
    fun fromIntAsSignificand(int: Int, decimalMode: DecimalMode? = null) =
      fromCompact(int.toLong(), 0L, decimalMode).roundSignificand(decimalMode)

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param short Short value to conver
     * @return BigDecimal representing input
     */
    fun fromShortAsSignificand(short: Short, decimalMode: DecimalMode? = null) =
      fromCompact(short.toLong(), 0L, decimalMode).roundSignificand(decimalMode)

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param byte Short value to convert
     * @return BigDecimal representing input
     */
    fun fromByteAsSignificand(byte: Byte, decimalMode: DecimalMode? = null) =
      fromCompact(byte.toLong(), 0L, decimalMode).roundSignificand(decimalMode)

    /**
     * Convert a float into a BigDecimal
     *
     * i.e. 71.11 -> 7.111E+2
     *
     * @param float Float value to conver
     * @return BigDecimal representing input
     */
    fun fromFloat(float: Float, decimalMode: DecimalMode? = null): BigDecimal {
      val floatString = float.toString()
      return if (floatString.contains('.') && !floatString.contains('E', true)) {
        parseStringWithMode(floatString.dropLastWhile { it == '0' }, decimalMode)
          .roundSignificand(decimalMode)
      } else {
        parseStringWithMode(floatString, decimalMode).roundSignificand(decimalMode)
      }
    }

    /**
     * Convert a Double into a BigDecimal
     *
     * i.e. 71.11 -> 7.111E+2
     *
     * @param double Double value to conver
     * @return BigDecimal representing input
     */
    fun fromDouble(double: Double, decimalMode: DecimalMode? = null): BigDecimal {
      val doubleString = double.toString()
      return if (doubleString.contains('.') && !doubleString.contains('E', true)) {
        parseStringWithMode(doubleString.dropLastWhile { it == '0' }, decimalMode)
          .roundSignificand(decimalMode)
      } else {
        parseStringWithMode(doubleString, decimalMode)
          .roundSignificand(decimalMode)
          .roundSignificand(decimalMode)
      }
    }

    /** Create BigDecimal from BigInteger significand and exponent */
    fun fromBigIntegerWithExponent(
      bigInteger: BigInteger,
      exponent: Long,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      return BigDecimal(bigInteger, exponent, decimalMode).roundSignificand(decimalMode)
    }

    /** Create BigDecimal from Long significand and BigInteger exponent */
    fun fromLongWithExponent(
      long: Long,
      exponent: Long,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      return fromCompactOr(long, exponent, decimalMode) { BigInteger.fromLong(long) }
        .roundSignificand(decimalMode)
    }

    /** Create BigDecimal from Int significand and BigInteger exponent */
    fun fromIntWithExponent(
      int: Int,
      exponent: Long,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      return fromCompact(int.toLong(), exponent, decimalMode).roundSignificand(decimalMode)
    }

    /** Create BigDecimal from Short significand and BigInteger exponent */
    fun fromShortWithExponent(
      short: Short,
      exponent: Long,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      return fromCompact(short.toLong(), exponent, decimalMode).roundSignificand(decimalMode)
    }

    /** Create BigDecimal from Short significand and BigInteger exponent */
    fun fromByteWithExponent(
      byte: Byte,
      exponent: Long,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      return fromCompact(byte.toLong(), exponent, decimalMode).roundSignificand(decimalMode)
    }

    /**
     * Convert a BigInteger into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param bigInteger Long value to conver
     * @return BigDecimal representing input
     */
    override fun fromBigInteger(bigInteger: BigInteger): BigDecimal {
      return fromBigInteger(bigInteger, null)
    }

    /**
     * Convert a Long into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param uLong Long value to conver
     * @return BigDecimal representing input
     */
    override fun fromULong(uLong: ULong): BigDecimal {
      return fromULong(uLong, null)
    }

    /**
     * Convert a Int into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param uInt Int value to conver
     * @return BigDecimal representing input
     */
    override fun fromUInt(uInt: UInt): BigDecimal {
      return fromUInt(uInt, null)
    }

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param uShort Short value to conver
     * @return BigDecimal representing input
     */
    override fun fromUShort(uShort: UShort): BigDecimal {
      return fromUShort(uShort, null)
    }

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param uByte Short value to conver
     * @return BigDecimal representing input
     */
    override fun fromUByte(uByte: UByte): BigDecimal {
      return fromUByte(uByte, null)
    }

    /**
     * Convert a Long into a BigDecimal
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param long Long value to conver
     * @return BigDecimal representing input
     */
    override fun fromLong(long: Long): BigDecimal {
      return fromLong(long, null)
    }

    /**
     * Convert a Int into a BigDecimal
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param int Int value to conver
     * @return BigDecimal representing input
     */
    override fun fromInt(int: Int): BigDecimal {
      return fromInt(int, null)
    }

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+3
     *
     * @param short Short value to conver
     * @return BigDecimal representing input
     */
    override fun fromShort(short: Short): BigDecimal {
      return fromShort(short, null)
    }

    /**
     * Convert a Short into a BigDecimal, but use supplied value directly as significant.
     *
     * i.e. 7111 -> 7.111E+0
     *
     * @param byte Short value to conver
     * @return BigDecimal representing input
     */
    override fun fromByte(byte: Byte): BigDecimal {
      return fromByte(byte, null)
    }

    override fun tryFromFloat(float: Float, exactRequired: Boolean): BigDecimal {
      return fromFloat(float, null)
    }

    override fun tryFromDouble(double: Double, exactRequired: Boolean): BigDecimal {
      return fromDouble(double, null)
    }

    override fun parseString(string: String, base: Int): BigDecimal {
      return parseStringWithMode(string, null)
    }

    fun parseString(string: String): BigDecimal {
      return parseStringWithMode(string)
    }

    /**
     * Parse BigDecimal from a supplied string. The string can be in either of two different
     * formats:
     *
     * * **Scientific** i.e. 1.234E-9
     *
     * or
     *
     * * **Expanded** ie 0.000000001234
     *
     * @return Parsed string
     * @throws ArithmeticException if parsing fails
     */
    fun parseStringWithMode(
      floatingPointString: String,
      decimalMode: DecimalMode? = null,
    ): BigDecimal {
      if (floatingPointString.isEmpty()) {
        throw ArithmeticException("Empty string is not a valid decimal number")
      }
      if (compactParseEnabledForTesting) {
        parseCompactPlain(floatingPointString, decimalMode)?.let {
          return it
        }
      }
      if (floatingPointString.contains('E', true)) {
        // Sci notation
        val split =
          if (floatingPointString.contains('.').not()) {
            // As is case with JS Double.MIN_VALUE
            val splitAroundE = floatingPointString.split('E', 'e')
            listOf(splitAroundE[0], "0E" + splitAroundE[1])
          } else {
            floatingPointString.split('.')
          }
        when (split.size) {
          2 -> {
            val signPresent = (floatingPointString[0] == '-' || floatingPointString[0] == '+')
            val leftStart =
              if (signPresent) {
                1
              } else {
                0
              }
            var sign =
              if (signPresent) {
                if (floatingPointString[0] == '-') {
                  Sign.NEGATIVE
                } else {
                  Sign.POSITIVE
                }
              } else {
                Sign.POSITIVE
              }
            val left = split[0].substring(startIndex = leftStart)
            val rightSplit = split[1].split('E', 'e')
            val right = rightSplit[0]
            val exponentSplit = rightSplit[1]
            val exponentSignPresent = (exponentSplit[0] == '-' || exponentSplit[0] == '+')
            val exponentSign =
              if (exponentSplit[0] == '-') {
                Sign.NEGATIVE
              } else {
                Sign.POSITIVE
              }
            val skipSignIfPresent =
              if (exponentSignPresent) {
                1
              } else {
                0
              }
            val exponentString = exponentSplit.substring(startIndex = skipSignIfPresent)
            val exponent =
              if (exponentSign == Sign.POSITIVE) {
                exponentString.toLong(10)
              } else {
                exponentString.toLong(10) * -1
              }

            var leftFirstNonZero = left.indexOfFirst { it != '0' }

            if (leftFirstNonZero == -1) {
              leftFirstNonZero = 0
            }

            var rightLastNonZero = right.indexOfLast { it != '0' }

            if (rightLastNonZero == -1) {
              rightLastNonZero = right.length - 1
            }
            val leftTruncated = left.substring(leftFirstNonZero, left.length)
            val rightTruncated = right.substring(0, rightLastNonZero + 1)
            val digitString = leftTruncated + rightTruncated
            val compactMagnitude = CompactArithmetic.parseDigits(digitString)
            if (compactMagnitude != CompactArithmetic.INFLATED) {
              val compactSignificand =
                if (sign == Sign.NEGATIVE) -compactMagnitude else compactMagnitude
              val compactExponent =
                if (leftTruncated != "0") {
                  exponent + leftTruncated.length - 1
                } else {
                  exponent -
                    (rightTruncated.length - CompactArithmetic.digitCount(compactSignificand)) -
                    1
                }
              return fromCompact(compactSignificand, compactExponent, decimalMode)
            }
            var significand = BigInteger.parseString(digitString, 10)

            if (significand == BigInteger.ZERO) {
              sign = Sign.ZERO
            }
            if (sign == Sign.NEGATIVE) {
              significand = significand.negate()
            }
            // here we need to cover mixed scientific and expanded notationtions, such as
            // 0.00375E-20 = 3.75E-22
            val exponentModifiedByFloatingPointPosition =
              if (leftTruncated != "0") {
                // i.e. 375E-37 = 3.75*10^2*10^-37 =4.75 * 10^-35
                exponent + leftTruncated.length - 1
              } else {
                // i.e. 0.375E-20 = 3.75 * 10^E-23
                exponent - (rightTruncated.length - significand.numberOfDecimalDigits()) - 1
              }
            return BigDecimal(significand, exponentModifiedByFloatingPointPosition, decimalMode)
          }

          else ->
            throw ArithmeticException(
              "Invalid (or unsupported) floating point number format: $floatingPointString"
            )
        }
      } else {
        // Expanded notation
        if (floatingPointString.contains('.')) {
          val split = floatingPointString.split('.')
          when (split.size) {
            2 -> {
              val signPresent = (floatingPointString[0] == '-' || floatingPointString[0] == '+')
              val leftStart =
                if (signPresent) {
                  1
                } else {
                  0
                }
              var sign =
                if (signPresent) {
                  if (floatingPointString[0] == '-') {
                    Sign.NEGATIVE
                  } else {
                    Sign.POSITIVE
                  }
                } else {
                  Sign.POSITIVE
                }
              val left = split[0].substring(startIndex = leftStart)
              val right = split[1]
              var leftFirstNonZero = left.indexOfFirst { it != '0' }

              if (leftFirstNonZero == -1) {
                leftFirstNonZero = 0
              }

              var rightLastNonZero = right.indexOfLast { it != '0' }

              if (rightLastNonZero == -1) {
                rightLastNonZero = right.length - 1
              }
              val leftTruncated = left.substring(leftFirstNonZero, left.length)
              val rightTruncated = right.substring(0, rightLastNonZero + 1)
              val exponent =
                if (leftTruncated.isNotEmpty() && leftTruncated[0] != '0') {
                  leftTruncated.length - 1
                } else {
                  (rightTruncated.indexOfFirst { it != '0' } + 1) * -1
                }
              val digitString = leftTruncated + rightTruncated
              val compactMagnitude = CompactArithmetic.parseDigits(digitString)
              if (compactMagnitude != CompactArithmetic.INFLATED) {
                val compactSignificand =
                  if (sign == Sign.NEGATIVE) -compactMagnitude else compactMagnitude
                return fromCompact(compactSignificand, exponent.toLong(), decimalMode)
              }
              var significand = BigInteger.parseString(digitString, 10)

              if (significand == BigInteger.ZERO) {
                sign = Sign.ZERO
              }
              if (sign == Sign.NEGATIVE) {
                significand = significand.negate()
              }
              return BigDecimal(significand, exponent.toLong(), decimalMode)
            }

            else ->
              throw ArithmeticException(
                "Invalid (or unsupported) floating point number format: $floatingPointString"
              )
          }
        } else {
          val negative = floatingPointString[0] == '-'
          val unsignedStart = if (negative || floatingPointString[0] == '+') 1 else 0
          val compactMagnitude =
            CompactArithmetic.parseDigits(floatingPointString.substring(unsignedStart))
          if (compactMagnitude != CompactArithmetic.INFLATED) {
            val compactSignificand = if (negative) -compactMagnitude else compactMagnitude
            return fromCompact(
              compactSignificand,
              CompactArithmetic.digitCount(compactSignificand) - 1,
              decimalMode,
            )
          }
          val significand = BigInteger.parseString(floatingPointString, 10)
          return BigDecimal(
            significand,
            significand.numberOfDecimalDigits() - 1,
            decimalMode,
          )
        }
      }
    }

    /**
     * Single-pass parse of the plain `[+-]digits[.digits]` form into a compact value, with the same
     * truncation and exponent rules as the general parser below. Returns null for anything else,
     * including inputs with more than 18 significant characters, and the general parser then
     * accepts or rejects them exactly as before.
     */
    private fun parseCompactPlain(input: String, decimalMode: DecimalMode?): BigDecimal? {
      val length = input.length
      var index = 0
      val negative = input[0] == '-'
      if (negative || input[0] == '+') {
        index++
      }
      if (index == length) {
        return null
      }
      val leftStart = index
      while (index < length && input[index] != '.') {
        val char = input[index]
        if (char < '0' || char > '9') {
          return null
        }
        index++
      }
      val leftEnd = index
      if (index == length) {
        // Plain integer.
        val digits = leftEnd - leftStart
        if (digits == 0 || digits > CompactArithmetic.MAX_POWER_OF_TEN) {
          return null
        }
        var value = 0L
        for (i in leftStart until leftEnd) {
          value = value * 10L + (input[i] - '0')
        }
        val signed = if (negative) -value else value
        return fromCompact(signed, CompactArithmetic.digitCount(signed) - 1, decimalMode)
      }
      index++
      val rightStart = index
      while (index < length) {
        val char = input[index]
        if (char < '0' || char > '9') {
          return null
        }
        index++
      }
      val rightEnd = length
      // Leading zeros of the integer part are dropped unless it is all zeros.
      var leftFirstNonZero = leftStart
      while (leftFirstNonZero < leftEnd && input[leftFirstNonZero] == '0') {
        leftFirstNonZero++
      }
      val leftTruncatedStart = if (leftFirstNonZero == leftEnd) leftStart else leftFirstNonZero
      val leftTruncatedLength = leftEnd - leftTruncatedStart
      // Trailing zeros of the fraction are dropped unless it is all zeros.
      var rightLastNonZero = rightEnd - 1
      while (rightLastNonZero >= rightStart && input[rightLastNonZero] == '0') {
        rightLastNonZero--
      }
      val rightTruncatedEnd = if (rightLastNonZero < rightStart) rightEnd else rightLastNonZero + 1
      val rightTruncatedLength = rightTruncatedEnd - rightStart
      val totalLength = leftTruncatedLength + rightTruncatedLength
      if (totalLength == 0 || totalLength > CompactArithmetic.MAX_POWER_OF_TEN) {
        return null
      }
      var value = 0L
      for (i in leftTruncatedStart until leftEnd) {
        value = value * 10L + (input[i] - '0')
      }
      for (i in rightStart until rightTruncatedEnd) {
        value = value * 10L + (input[i] - '0')
      }
      val exponent =
        if (leftTruncatedLength > 0 && input[leftTruncatedStart] != '0') {
          (leftTruncatedLength - 1).toLong()
        } else {
          var firstNonZero = rightStart
          while (firstNonZero < rightTruncatedEnd && input[firstNonZero] == '0') {
            firstNonZero++
          }
          if (firstNonZero == rightTruncatedEnd) 0L else -((firstNonZero - rightStart) + 1).toLong()
        }
      val signed = if (negative) -value else value
      return fromCompact(signed, exponent, decimalMode)
    }

    private fun resolveDecimalMode(
      firstDecimalMode: DecimalMode?,
      secondDecimalMode: DecimalMode?,
      suppliedDecimalMode: DecimalMode?,
    ): DecimalMode {
      return if (suppliedDecimalMode != null) {
        suppliedDecimalMode
      } else {
        if (firstDecimalMode == null && secondDecimalMode == null) {
          return DecimalMode()
        }
        if (firstDecimalMode == null && secondDecimalMode != null) {
          return secondDecimalMode
        }
        if (secondDecimalMode == null && firstDecimalMode != null) {
          return firstDecimalMode
        }
        if (firstDecimalMode!!.roundingMode != secondDecimalMode!!.roundingMode) {
          throw ArithmeticException(
            "Different rounding modes! This: ${firstDecimalMode.roundingMode} Other: ${secondDecimalMode.roundingMode}"
          )
        }
        val unifiedDecimalMode =
          if (firstDecimalMode.decimalPrecision >= secondDecimalMode.decimalPrecision) {
            firstDecimalMode
          } else {
            secondDecimalMode
          }
        unifiedDecimalMode
      }
    }
  }

  /**
   * @param scale a value between 0 and if precision is limited, precision - 1.
   * @return a new BigDecimal with same precision and RoundingMode, but new scale applied. If
   *   current DecimalMode is null and scale is not null, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO is
   *   default
   */
  fun scale(scale: Long): BigDecimal {
    if (scale < 0) throw ArithmeticException("Negative Scale is unsupported.")
    val mode =
      if (decimalMode == null) {
        if (scale == -1L) {
          DecimalMode.DEFAULT
        } else {
          DecimalMode(0, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO, scale)
        }
      } else {
        DecimalMode(
          decimalMode.decimalPrecision - decimalMode.scale,
          decimalMode.roundingMode,
          scale,
        )
      }
    return rebuild(decimalMode = mode)
  }

  fun removeScale(): BigDecimal {
    return rebuild(
      decimalMode =
        DecimalMode(
          decimalMode?.decimalPrecision ?: 0,
          decimalMode?.roundingMode ?: RoundingMode.NONE,
          -1,
        )
    )
  }

  override fun getCreator(): BigNumber.Creator<BigDecimal> = BigDecimal

  override fun getInstance(): BigDecimal = this

  /**
   * Add two BigDecimal and return result in a new instance of BigDecimal. Default decimal mode will
   * be used.
   *
   * @param other BigDecimal (addend)
   * @return BigDecimal containing result of the operation
   */
  override fun add(other: BigDecimal): BigDecimal {
    return add(other, computeMode(other, ScaleOps.Max))
  }

  /**
   * Add two BigDecimal and return result in a new instance of BigDecimal.
   *
   * @param other BigDecimal (addend)
   * @return BigDecimal containing result of the operation
   */
  fun add(other: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    if (this.isZero()) {
      return roundOrDont(other, other.exponent, resolvedDecimalMode)
    }
    if (other.isZero()) {
      return roundOrDont(this, this.exponent, resolvedDecimalMode)
    }
    if (isCompact && other.isCompact) {
      addOrSubtractCompact(other, resolvedDecimalMode, false, resolvedDecimalMode.usingScale)?.let {
        return it
      }
    }
    val (first, second, _) = bringSignificandToSameExponent(this, other)
    // Temporary way to detect a carry happened, proper solution is to add
    // methods that return information about carry in arithmetic classes, this way it's going
    // to be rather slow
    val firstNumOfDigits = first.numberOfDecimalDigits()
    val secondNumOfDigits = second.numberOfDecimalDigits()
    val newSignificand = first + second
    val newSignificandNumOfDigit = newSignificand.numberOfDecimalDigits()
    val largerOperand =
      if (firstNumOfDigits > secondNumOfDigits) {
        firstNumOfDigits
      } else {
        secondNumOfDigits
      }
    val carryDetected = newSignificandNumOfDigit - largerOperand
    val newExponent = max(this.exponent, other.exponent) + carryDetected

    return if (resolvedDecimalMode.usingScale) {
      roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode.copy(decimalPrecision = newSignificandNumOfDigit),
      )
    } else {
      roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode,
      )
    }
  }

  /**
   * Subtract two BigDecimal and return result in a new instance of BigDecimal. Default decimal mode
   * will be used
   *
   * @param other BigDecimal (subtrahend)
   * @return BigDecimal containing result of the operation
   */
  override fun subtract(other: BigDecimal): BigDecimal {
    return subtract(other, computeMode(other, ScaleOps.Max))
  }

  /**
   * Subtract two BigDecimal and return result in a new instance of BigDecimal
   *
   * @param other BigDecimal (subtrahend)
   * @return BigDecimal containing result of the operation
   */
  fun subtract(other: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)

    if (this.isZero()) {
      return if (other.isCompact) {
        roundOrDontCompact(-other.compact, other.exponent, resolvedDecimalMode)
          ?: roundOrDont(other.significand.negate(), other.exponent, resolvedDecimalMode)
      } else {
        roundOrDont(other.significand.negate(), other.exponent, resolvedDecimalMode)
      }
    }
    if (other.isZero()) {
      return roundOrDont(this, this.exponent, resolvedDecimalMode)
    }
    if (isCompact && other.isCompact) {
      addOrSubtractCompact(other, resolvedDecimalMode, true, usingScale)?.let {
        return it
      }
    }

    val (first, second, _) = bringSignificandToSameExponent(this, other)

    val firstNumOfDigits = first.numberOfDecimalDigits()
    val secondNumOfDigits = second.numberOfDecimalDigits()

    val newSignificand = first - second

    val newSignificandNumOfDigit = newSignificand.numberOfDecimalDigits()

    val largerOperand =
      if (firstNumOfDigits > secondNumOfDigits) {
        firstNumOfDigits
      } else {
        secondNumOfDigits
      }
    val borrowDetected = newSignificandNumOfDigit - largerOperand

    val newExponent = max(this.exponent, other.exponent) + borrowDetected
    if (usingScale) {
      return roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode.copy(decimalPrecision = newSignificandNumOfDigit),
      )
    } else {
      return roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode,
      )
    }
  }

  /**
   * Multiply two BigDecimal and return result in a new instance of BigDecimal Default decimal mode
   * will be used
   *
   * @param other BigDecimal (multiplicand)
   * @return BigDecimal containing result of the operation
   */
  override fun multiply(other: BigDecimal): BigDecimal {
    return multiply(other, computeMode(other, ScaleOps.Max))
  }

  /**
   * Multiply two BigDecimal and return result in a new instance of BigDecimal
   *
   * @param other BigDecimal (multiplicand)
   * @return BigDecimal containing result of the operation
   */
  fun multiply(other: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    if (isCompact && other.isCompact) {
      val product = CompactArithmetic.multiply(compact, other.compact)
      if (product != CompactArithmetic.INFLATED) {
        val newSignificandNumOfDigit = CompactArithmetic.digitCount(product)
        val moveExponent =
          newSignificandNumOfDigit -
            (CompactArithmetic.digitCount(compact) + CompactArithmetic.digitCount(other.compact))
        val newExponent = this.exponent + other.exponent + moveExponent + 1
        val rounded =
          if (resolvedDecimalMode.usingScale) {
            roundOrDontCompact(
              product,
              newExponent,
              resolvedDecimalMode.copy(decimalPrecision = newSignificandNumOfDigit),
            )
          } else {
            roundOrDontCompact(product, newExponent, resolvedDecimalMode)
          }
        if (rounded != null) {
          return rounded
        }
      }
    }
    // Temporary way to detect a carry happened, proper solution is to add
    // methods that return information about carry in arithmetic classes, this way it's going
    // to be rather slow
    val firstNumOfDigits = this.significand.numberOfDecimalDigits()
    val secondNumOfDigits = other.significand.numberOfDecimalDigits()

    val newSignificand = this.significand * other.significand

    val newSignificandNumOfDigit = newSignificand.numberOfDecimalDigits()
    val moveExponent = newSignificandNumOfDigit - (firstNumOfDigits + secondNumOfDigits)

    val newExponent = this.exponent + other.exponent + moveExponent + 1
    return if (resolvedDecimalMode.usingScale) {
      roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode.copy(decimalPrecision = newSignificandNumOfDigit),
      )
    } else {
      roundOrDont(
        newSignificand,
        newExponent,
        resolvedDecimalMode,
      )
    }
  }

  override fun divide(other: BigDecimal): BigDecimal {
    return divide(other, computeMode(other, ScaleOps.Max))
  }

  private fun isEven(quotient: BigInteger): Boolean {
    return quotient.isEven()
  }

  private fun isHalfRounding(roundingMode: RoundingMode): Boolean {
    return when (roundingMode) {
      RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
      RoundingMode.ROUND_HALF_TOWARDS_ZERO,
      RoundingMode.ROUND_HALF_CEILING,
      RoundingMode.ROUND_HALF_FLOOR,
      RoundingMode.ROUND_HALF_TO_EVEN,
      RoundingMode.ROUND_HALF_TO_ODD -> true

      else -> false
    }
  }

  private fun getDecider(
    divRem: BigInteger.QuotientAndRemainder,
    divRemToSize: BigInteger.QuotientAndRemainder,
    isHalfRounding: Boolean,
  ): SignificantDecider {
    val quotient = divRem.quotient
    val remainder = divRem.remainder
    val quotientToSize = divRemToSize.quotient
    val remainderToSize = divRemToSize.remainder
    return if (
      remainderToSize.numberOfDecimalDigits() + quotientToSize.numberOfDecimalDigits() <
        quotient.numberOfDecimalDigits() && isHalfRounding
    ) {
      SignificantDecider.ZERO
    } else {
      val border = BigInteger.TEN.pow(remainderToSize.numberOfDecimalDigits() - 1) * 5
      when {
        remainderToSize.abs() < border -> {
          SignificantDecider.LESS_THAN_FIVE
        }

        remainderToSize.abs() > border -> {
          SignificantDecider.MORE_THAN_FIVE
        }

        remainderToSize.abs() == border -> {
          if (remainder == BigInteger.ZERO) {
            SignificantDecider.FIVE
          } else {
            SignificantDecider.MORE_THAN_FIVE
          }
        }

        else -> {
          throw RuntimeException("Unreachable code.")
        }
      }
    }
  }

  /**
   * Divide two BigDecimal and return result in a new instance of BigDecimal
   *
   * @param other BigDecimal (divisor)
   * @param decimalMode DecimalMode
   * @return BigDecimal containing result of the operation
   */
  fun divide(other: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
    if (other.isZero()) {
      throw ArithmeticException("Can not divide by zero.")
    }
    if (this.isZero()) {
      return ZERO
    }

    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    if (isCompact && other.isCompact) {
      divideCompact(other, resolvedDecimalMode)?.let {
        return it
      }
    }
    if (resolvedDecimalMode.isPrecisionUnlimited && resolvedDecimalMode.usingScale.not()) {
      val newExponent = this.exponent - other.exponent
      val power = (other.precision * 2 + 6)
      val thisPrepared = this.significand * BigInteger.TEN.pow(power)
      val divRem = thisPrepared divrem other.significand
      val result = divRem.quotient
      val expectedDiff = other.precision - 1
      val exponentModifier =
        expectedDiff + (result.numberOfDecimalDigits() - thisPrepared.numberOfDecimalDigits())

      if (divRem.remainder != BigInteger.ZERO) {
        throw ArithmeticException(
          "Non-terminating result of division operation " +
            "(i.e. 1/3 = 0.3333... library needs to know when to stop and how to round up at that point). " +
            "Specify decimalPrecision inside your decimal mode."
        )
      }
      return BigDecimal(
        result,
        newExponent + exponentModifier,
        resolvedDecimalMode,
      )
    } else {
      val desiredPrecision = resolvedDecimalMode.decimalPrecision

      val power =
        if (desiredPrecision + other.precision - this.precision > 0) {
          desiredPrecision + other.precision - this.precision + 5
        } else {
          this.precision - desiredPrecision - other.precision + 5
        }

      val thisPrepared = significand * 10.toBigInteger().pow(power)

      val divRem = thisPrepared divrem other.significand
      val quotient = divRem.quotient

      val expectedNumOfDigitsInQuotient = thisPrepared.numberOfDecimalDigits() - other.precision
      val exponentModifier = quotient.numberOfDecimalDigits() - expectedNumOfDigitsInQuotient - 1
      val precisionDifference = quotient.numberOfDecimalDigits() - desiredPrecision

      val divRemToSize = quotient divrem 10.toBigInteger().pow(precisionDifference)

      val quotientToSize = divRemToSize.quotient
      val remainderToSize = divRemToSize.remainder
      return if (remainderToSize == BigInteger.ZERO) {
        BigDecimal(
          quotientToSize,
          this.exponent - other.exponent + exponentModifier,
          resolvedDecimalMode,
        )
      } else {

        val signOfTheResultOfDivision =
          if (this.signum() == other.signum()) {
            Sign.POSITIVE
          } else {
            Sign.NEGATIVE
          }
        val isEven = isEven(divRemToSize.quotient)
        val isHalfRounding = isHalfRounding(resolvedDecimalMode.roundingMode)
        val newDecider = getDecider(divRem, divRemToSize, isHalfRounding)

        val roundedQuotient =
          roundWithDecider(
            quotientToSize,
            resolvedDecimalMode.roundingMode,
            signOfTheResultOfDivision,
            newDecider,
            isEven,
          )
        // this is needed if the rounding changes the number of digits
        val roundedQuotientExponentModifier =
          roundedQuotient.numberOfDecimalDigits() - quotientToSize.numberOfDecimalDigits()
        BigDecimal(
          roundedQuotient,
          this.exponent - other.exponent + exponentModifier + roundedQuotientExponentModifier,
          resolvedDecimalMode,
        )
      }
    }
  }

  /**
   * Remainder of **integer** division on this bigDecimal. If there is no rounding mode defined in
   * decimal mode #RoundingMode.FLOOR will be used
   */
  override fun remainder(other: BigDecimal): BigDecimal {
    return divideAndRemainder(other).second
  }

  /**
   * Quotient and remainder of **integer** division on this bigDecimal. If there is no rounding mode
   * defined in decimal mode #RoundingMode.TOWARDS_ZERO will be used. Uses truncating division to
   * determine quotient, which means that the sign of remainder will be same as sign of dividend
   */
  override fun divideAndRemainder(other: BigDecimal): Pair<BigDecimal, BigDecimal> {
    if (other.abs() > this.abs()) {
      return Pair(ZERO, this)
    }
    val roundingMode = DecimalMode(exponent - other.exponent + 1, RoundingMode.TOWARDS_ZERO)
    val quotient = divide(other, roundingMode)
    val quotientWithoutDecimal =
      quotient.roundToDigitPositionAfterDecimalPoint(0, roundingMode.roundingMode)
    val remainder = this - (quotientWithoutDecimal * other)
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    return if (resolvedDecimalMode.usingScale) {
      val roundedQuotient =
        roundOrDont(
          quotientWithoutDecimal,
          quotientWithoutDecimal.exponent,
          resolvedDecimalMode.copy(decimalPrecision = quotient.significandDigits()),
        )
      val roundedRemainder =
        roundOrDont(
          remainder,
          remainder.exponent,
          resolvedDecimalMode.copy(decimalPrecision = remainder.significandDigits()),
        )
      Pair(roundedQuotient, roundedRemainder)
    } else {
      val roundedQuotient =
        roundOrDont(quotientWithoutDecimal, quotientWithoutDecimal.exponent, resolvedDecimalMode)
      val roundedRemainder = roundOrDont(remainder, remainder.exponent, resolvedDecimalMode)
      Pair(roundedQuotient, roundedRemainder)
    }
  }

  override fun isZero(): Boolean {
    if (isCompact) {
      return compact == 0L
    }
    return significand.isZero()
  }

  /** Digits in the significand itself, independent of any eagerly cached scaled precision. */
  private fun significandDigits(): Long =
    if (isCompact) CompactArithmetic.digitCount(compact) else significand.numberOfDecimalDigits()

  // ── Compact (Long) arithmetic ─────────────────────────────────────────────
  //
  // Mirrors of the BigInteger operations below. Each returns null when any intermediate value
  // would not fit in a Long, and the caller then runs the BigInteger version from scratch.

  /**
   * [add] or [subtract] when both operands are compact. [scaleToDigits] reproduces the mode copy
   * the BigInteger versions make for scaled modes; add keys it off the resolved mode and subtract
   * off this instance's mode.
   */
  private fun addOrSubtractCompact(
    other: BigDecimal,
    decimalMode: DecimalMode,
    subtract: Boolean,
    scaleToDigits: Boolean,
  ): BigDecimal? {
    // Align both significands to the smaller unit exponent, as bringSignificandToSameExponent does.
    val thisUnitExponent = exponent - CompactArithmetic.digitCount(compact) + 1
    val otherUnitExponent = other.exponent - CompactArithmetic.digitCount(other.compact) + 1
    var first = compact
    var second = other.compact
    if (thisUnitExponent > otherUnitExponent) {
      first = CompactArithmetic.scaleByPowerOfTen(first, thisUnitExponent - otherUnitExponent)
    } else if (otherUnitExponent > thisUnitExponent) {
      second = CompactArithmetic.scaleByPowerOfTen(second, otherUnitExponent - thisUnitExponent)
    }
    if (first == CompactArithmetic.INFLATED || second == CompactArithmetic.INFLATED) {
      return null
    }
    val firstNumOfDigits = CompactArithmetic.digitCount(first)
    val secondNumOfDigits = CompactArithmetic.digitCount(second)
    val newSignificand =
      if (subtract) CompactArithmetic.subtract(first, second)
      else CompactArithmetic.add(first, second)
    if (newSignificand == CompactArithmetic.INFLATED) {
      return null
    }
    val newSignificandNumOfDigit = CompactArithmetic.digitCount(newSignificand)
    val largerOperand = max(firstNumOfDigits, secondNumOfDigits)
    val carryDetected = newSignificandNumOfDigit - largerOperand
    val newExponent = max(this.exponent, other.exponent) + carryDetected
    return if (scaleToDigits) {
      roundOrDontCompact(
        newSignificand,
        newExponent,
        decimalMode.copy(decimalPrecision = newSignificandNumOfDigit),
      )
    } else {
      roundOrDontCompact(newSignificand, newExponent, decimalMode)
    }
  }

  /** [divide] when both operands are compact and non-zero. */
  private fun divideCompact(other: BigDecimal, decimalMode: DecimalMode): BigDecimal? {
    val divisor = other.compact
    if (decimalMode.isPrecisionUnlimited && decimalMode.usingScale.not()) {
      val newExponent = this.exponent - other.exponent
      val power = (other.precision * 2 + 6)
      val quotientAndRemainder = LongArray(2)
      if (!CompactArithmetic.scaledDivide(compact, divisor, power, quotientAndRemainder)) {
        return null
      }
      val result = quotientAndRemainder[0]
      val remainder = quotientAndRemainder[1]
      val thisPreparedDigits = CompactArithmetic.digitCount(compact) + power
      val expectedDiff = other.precision - 1
      val exponentModifier =
        expectedDiff + (CompactArithmetic.digitCount(result) - thisPreparedDigits)
      if (remainder != 0L) {
        throw ArithmeticException(
          "Non-terminating result of division operation " +
            "(i.e. 1/3 = 0.3333... library needs to know when to stop and how to round up at that point). " +
            "Specify decimalPrecision inside your decimal mode."
        )
      }
      return fromCompact(result, newExponent + exponentModifier, decimalMode)
    }
    val desiredPrecision = decimalMode.decimalPrecision
    val power =
      if (desiredPrecision + other.precision - this.precision > 0) {
        desiredPrecision + other.precision - this.precision + 5
      } else {
        this.precision - desiredPrecision - other.precision + 5
      }
    val quotientAndRemainder = LongArray(2)
    if (!CompactArithmetic.scaledDivide(compact, divisor, power, quotientAndRemainder)) {
      return null
    }
    val quotient = quotientAndRemainder[0]
    val remainder = quotientAndRemainder[1]
    val quotientDigits = CompactArithmetic.digitCount(quotient)
    val thisPreparedDigits = CompactArithmetic.digitCount(compact) + power
    val expectedNumOfDigitsInQuotient = thisPreparedDigits - other.precision
    val exponentModifier = quotientDigits - expectedNumOfDigitsInQuotient - 1
    val precisionDifference = quotientDigits - desiredPrecision
    if (precisionDifference < 0 || precisionDifference > CompactArithmetic.MAX_POWER_OF_TEN) {
      return null
    }
    val sizeDivisor = CompactArithmetic.powerOfTen(precisionDifference.toInt())
    val quotientToSize = quotient / sizeDivisor
    val remainderToSize = quotient % sizeDivisor
    if (remainderToSize == 0L) {
      return fromCompact(
        quotientToSize,
        this.exponent - other.exponent + exponentModifier,
        decimalMode,
      )
    }
    val signOfTheResultOfDivision =
      if (this.signum() == other.signum()) Sign.POSITIVE else Sign.NEGATIVE
    val isEven = CompactArithmetic.isEven(quotientToSize)
    val isHalfRounding = isHalfRounding(decimalMode.roundingMode)
    val remainderToSizeDigits = CompactArithmetic.digitCount(remainderToSize)
    val decider =
      if (
        remainderToSizeDigits + CompactArithmetic.digitCount(quotientToSize) < quotientDigits &&
          isHalfRounding
      ) {
        SignificantDecider.ZERO
      } else {
        val border = CompactArithmetic.powerOfTen((remainderToSizeDigits - 1).toInt()) * 5L
        val remainderMagnitude = if (remainderToSize < 0) -remainderToSize else remainderToSize
        when {
          remainderMagnitude < border -> SignificantDecider.LESS_THAN_FIVE
          remainderMagnitude > border -> SignificantDecider.MORE_THAN_FIVE
          else ->
            if (remainder == 0L) SignificantDecider.FIVE else SignificantDecider.MORE_THAN_FIVE
        }
      }
    val roundedQuotient =
      roundWithDeciderCompact(
        quotientToSize,
        decimalMode.roundingMode,
        signOfTheResultOfDivision,
        decider,
        isEven,
      )
    if (roundedQuotient == CompactArithmetic.INFLATED) {
      return null
    }
    val roundedQuotientExponentModifier =
      CompactArithmetic.digitCount(roundedQuotient) - CompactArithmetic.digitCount(quotientToSize)
    return fromCompact(
      roundedQuotient,
      this.exponent - other.exponent + exponentModifier + roundedQuotientExponentModifier,
      decimalMode,
    )
  }

  /**
   * The integer part as a `Long`, or [CompactArithmetic.INFLATED] when this value is not compact or
   * the integer part does not fit. Mirrors [toBigInteger].
   */
  private fun integerPartCompact(): Long {
    if (!isCompact) {
      return CompactArithmetic.INFLATED
    }
    if (exponent < 0) {
      return 0L
    }
    val precisionExponentDiff = exponent - precision
    return when {
      precisionExponentDiff > 0 ->
        CompactArithmetic.scaleByPowerOfTen(compact, precisionExponentDiff + 1)
      precisionExponentDiff < 0 -> {
        val dropped = -precisionExponentDiff - 1
        if (dropped > CompactArithmetic.MAX_POWER_OF_TEN) {
          CompactArithmetic.INFLATED
        } else {
          compact / CompactArithmetic.powerOfTen(dropped.toInt())
        }
      }
      else -> CompactArithmetic.multiply(compact, 10L)
    }
  }

  /**
   * Creates a copy of this BigDecimal with some different elements
   * (significand/exponent/decimalMode)
   */
  fun copy(
    significand: BigInteger = this.significand,
    exponent: Long = this.exponent,
    decimalMode: DecimalMode? = this.decimalMode,
  ): BigDecimal {
    return BigDecimal(significand, exponent, decimalMode)
  }

  /**
   * Moves the decimal point by creating a new instance with a different exponent. Positive values
   * move the decimal point to the right, negative values move the decimal point to the left.
   */
  fun moveDecimalPoint(places: Int): BigDecimal {
    if (places == 0) {
      return this
    }
    return rebuild(exponent = exponent + places)
  }

  /**
   * Moves the decimal point by creating a new instance with a different exponent. Positive values
   * move the decimal point to the right, negative values move the decimal point to the left.
   */
  fun moveDecimalPoint(places: Long): BigDecimal {
    if (places == 0L) {
      return this
    }
    return rebuild(exponent = exponent + places)
  }

  // TODO in 0.3.x
  //    override fun pow(exponent: BigDecimal): BigDecimal {
  //        if (exponent.signum() < 0) {
  //            throw RuntimeException("BigDecimal exponentiation with negative numbers is not
  // supported. Exponent: ${exponent}")
  //        }
  //        //TODO Naive implementation
  //        var result = ONE
  //        for (i in 1.toBigInteger() .. exponent.toBigInteger()) {
  //            result = result * this
  //        }
  //        val remainder = exponent.subtract(exponent.floor(), DecimalMode.DEFAULT)
  //        return result
  //
  //    }
  /** Exponentiate this BigDecimal by some exponent */
  override fun pow(exponent: Int): BigDecimal {
    return pow(exponent.toLong())
  }

  // TODO in 0.3.x
  //    /**
  //     * Natural logarithm of this BigDecimal
  //     */
  //    fun log() {
  //
  //    }

  //    /**
  //     * Arithmetic-geometric mean
  //     */
  //    fun agm(other : BigDecimal, decimalMode: DecimalMode? = null) : BigDecimal {
  //        var a = this
  //        var g = other
  //        var nextA : BigDecimal
  //        val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode,
  // decimalMode)
  //        if (resolvedDecimalMode.decimalPrecision == 0L) {
  //            throw ArithmeticException("Agm not available with infinite precision")
  //        }
  //        while (true) {
  //            nextA = (a + g) / TWO
  //            if ((a - g).abs().precision <= resolvedDecimalMode.decimalPrecision) {
  //                return nextA
  //            } else {
  //                g = (a * g).sqrt()
  //                a = nextA
  //            }
  //
  //        }
  //    }
  //
  //    fun sqrt() : BigDecimal {
  //        val root = significand.sqrt()
  //        val exponent =
  //    }
  /** Return this truncated by using floor rounding */
  fun floor(): BigDecimal {
    if (exponent < 0) {
      return when {
        signum() > 0 -> ZERO
        signum() < 0 -> ONE.negate()
        else -> ZERO
      }
    }
    return roundSignificand(DecimalMode(exponent + 1, RoundingMode.FLOOR))
  }

  /** Return this truncated by using ceil rounding */
  fun ceil(): BigDecimal {
    if (isZero()) {
      return ZERO
    }
    if (exponent < 0) {
      return when {
        signum() > 0 -> ONE
        signum() < 0 -> ZERO
        else -> ZERO
      }
    }
    return roundSignificand(DecimalMode(exponent + 1, RoundingMode.CEILING))
  }

  /** Convert to big integer by truncating */
  fun toBigInteger(): BigInteger {
    if (exponent < 0) {
      return BigInteger.ZERO
    }
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      return BigInteger.fromLong(compactIntegerPart)
    }
    val precisionExponentDiff = exponent - precision
    return when {
      precisionExponentDiff > 0 -> {
        significand * 10.toBigInteger().pow(precisionExponentDiff + 1)
      }

      precisionExponentDiff < 0 -> {
        significand / 10.toBigInteger().pow(precisionExponentDiff.absoluteValue - 1)
      }

      else -> {
        significand * 10
      }
    }
  }

  /**
   * Returns the number of digits representing this number i.e. 12.345 will return 5 0.001 will
   * return 4 123000 will return 6 0.010000 will return 3 (trailing zeros are not counted)
   */
  override fun numberOfDecimalDigits(): Long {
    val numberOfDigits =
      when {
        exponent in 1L until precision -> precision
        exponent > 0 && exponent > precision ->
          exponent + 1 // Significand is already 10^1 when exponent is > 0
        exponent > 0 && exponent == precision -> precision + 1 // Same as above
        exponent < 0 -> exponent.absoluteValue + precision
        exponent == 0L -> {
          if (isZero()) return 1L
          var zeroCount = -1L
          if (isCompact) {
            var s = compact
            do {
              val remainder = s % 10L
              s /= 10L
              zeroCount++
            } while (remainder == 0L)
          } else {
            var s = significand
            do {
              val res = s.divrem(BigInteger.TEN)
              s = res.quotient
              zeroCount++
            } while (res.remainder.isZero())
          }
          precision - zeroCount
        }

        else -> throw RuntimeException("Invalid case when getting number of decimal digits")
      }
    return numberOfDigits
  }

  /**
   * Used to squeeze significands into minimal precision i.e. 12340000 to 1234 because both numbers
   * still represent the same number i.e. 1.234
   */
  private fun removeTrailingZeroes(bigDecimal: BigDecimal): BigDecimal {
    if (bigDecimal.isZero()) return this
    var significand = bigDecimal.significand
    var divisionResult = BigInteger.QuotientAndRemainder(bigDecimal.significand, BigInteger.ZERO)
    do {
      divisionResult = divisionResult.quotient.divrem(BigInteger.TEN)
      if (divisionResult.remainder == BigInteger.ZERO) {
        significand = divisionResult.quotient
      }
    } while (divisionResult.remainder == BigInteger.ZERO)
    return BigDecimal(significand, bigDecimal.exponent)
  }

  override fun toString(base: Int): String {
    if (base != 10) {
      throw RuntimeException("BigDecimal in base other than 10 is not supported yet")
    }
    return toString()
  }

  // TODO
  private fun integerDiv(other: BigDecimal, decimalMode: DecimalMode? = null): BigDecimal {
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    //        val (first, second) = bringSignificandToSameExponent(this, other)
    val newExponent = this.exponent - other.exponent
    val newSignificand = this.significand / other.significand
    return roundOrDont(newSignificand, newExponent, resolvedDecimalMode)
  }

  // TODO
  private fun divrem(
    other: BigDecimal,
    decimalMode: DecimalMode? = null,
  ): Pair<BigDecimal, BigDecimal> {
    val resolvedDecimalMode = resolveDecimalMode(this.decimalMode, other.decimalMode, decimalMode)
    val newExponent = max(this.exponent, other.exponent)
    val newSignificand = this.significand / other.significand
    val newRemainderSignificand = this.significand % other.significand
    return Pair(
      roundOrDont(newSignificand, newExponent, resolvedDecimalMode),
      roundOrDont(newRemainderSignificand, newExponent, resolvedDecimalMode),
    )
  }

  infix fun divrem(other: BigDecimal): Pair<BigDecimal, BigDecimal> {
    return divideAndRemainder(other)
  }

  private enum class ScaleOps {
    Max,
    Min,
    Add,
  }

  private fun computeMode(other: BigDecimal, op: ScaleOps): DecimalMode {
    return if (
      decimalMode == null ||
        decimalMode.isPrecisionUnlimited ||
        other.decimalMode == null ||
        other.decimalMode.isPrecisionUnlimited
    )
      DecimalMode.DEFAULT
    else {
      DecimalMode(
        max(decimalMode.decimalPrecision, other.decimalMode.decimalPrecision),
        decimalMode.roundingMode,
        if (decimalMode.usingScale && other.decimalMode.usingScale)
          when (op) {
            ScaleOps.Max -> max(decimalMode.scale, other.decimalMode.scale)
            ScaleOps.Min -> min(decimalMode.scale, other.decimalMode.scale)
            ScaleOps.Add -> decimalMode.scale + other.decimalMode.scale
          }
        else -1,
      )
    }
  }

  override operator fun plus(other: BigDecimal): BigDecimal {
    return this.add(other, computeMode(other, ScaleOps.Max))
  }

  override operator fun minus(other: BigDecimal): BigDecimal {
    return this.subtract(other, computeMode(other, ScaleOps.Max))
  }

  override operator fun times(other: BigDecimal): BigDecimal {
    return this.multiply(other, computeMode(other, ScaleOps.Max))
  }

  override operator fun div(other: BigDecimal): BigDecimal {
    return this.divide(other, computeMode(other, ScaleOps.Max))
  }

  override operator fun rem(other: BigDecimal): BigDecimal {
    return divideAndRemainder(other).second
  }

  /** Returns a new negated instance */
  override fun unaryMinus(): BigDecimal = negate()

  /**
   * Overwrites the inflated significand if one exists. A compact significand is an immutable `Long`
   * field and cannot be cleared.
   */
  override fun secureOverwrite() {
    inflatedCache?.secureOverwrite()
  }

  /** Incerement by one */
  fun inc(): BigDecimal {
    return this + 1
  }

  /** Decrement by one */
  fun dec(): BigDecimal {
    return this - 1
  }

  /** Return Absolute value */
  override fun abs(): BigDecimal {
    if (isCompact) {
      return fromCompact(if (compact < 0) -compact else compact, exponent, decimalMode)
    }
    return BigDecimal(significand.abs(), exponent, decimalMode)
  }

  /** Negate this BigDecimal */
  override fun negate(): BigDecimal {
    if (isCompact) {
      return fromCompact(-compact, exponent, decimalMode)
    }
    return BigDecimal(significand.negate(), exponent, decimalMode)
  }

  /**
   * Exponentiate this BigDecimal by some exponent. Note that while exponentiation with positive
   * numbers can have infinite precision, exponentiation with negative numbers is division and as
   * such has the same limitation as division.
   */
  override fun pow(exponent: Long): BigDecimal {
    if (this.isZero() && exponent < 0) {
      throw ArithmeticException("Negative exponentiation of zero is not defined.")
    }
    var result = this
    return when {
      exponent > 0 -> {
        for (i in 0 until exponent - 1) {
          result *= this
        }
        result
      }

      exponent < 0 -> {
        if (exponent == Long.MIN_VALUE) {
          for (i in 0..Long.MAX_VALUE) {
            result /= this
          }
          // And another division beacuse min value is -9223372036854775808L and max value is
          // 9223372036854775807L
          result /= this
        } else {
          for (i in 0..exponent.absoluteValue) {
            result /= this
          }
        }

        result
      }

      else -> {
        ONE
      }
    }
  }

  /**
   * Signum function
   *
   * @return Result of signum function for this BigDecimal (-1 negative, 0 zero, 1 positive)
   */
  override fun signum(): Int =
    if (isCompact) CompactArithmetic.signumOf(compact) else significand.signum()

  /** The next group of functions are implementations of the NarrowingOperations interface */
  /**
   * Convert the current value to Int.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun intValue(exactRequired: Boolean): Int {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (
        exactRequired &&
          (compactIntegerPart > Int.MAX_VALUE.toLong() ||
            compactIntegerPart < Int.MIN_VALUE.toLong())
      ) {
        throw ArithmeticException("Cannot convert to int and provide exact value")
      }
      return compactIntegerPart.toInt()
    }
    return toBigInteger().intValue(exactRequired)
  }

  /**
   * Convert the current value to Long.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun longValue(exactRequired: Boolean): Long {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      return compactIntegerPart
    }
    return toBigInteger().longValue(exactRequired)
  }

  /**
   * Convert the current value to Byte.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun byteValue(exactRequired: Boolean): Byte {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (
        exactRequired &&
          (compactIntegerPart > Byte.MAX_VALUE.toLong() ||
            compactIntegerPart < Byte.MIN_VALUE.toLong())
      ) {
        throw ArithmeticException("Cannot convert to byte and provide exact value")
      }
      return compactIntegerPart.toByte()
    }
    return toBigInteger().byteValue(exactRequired)
  }

  /**
   * Convert the current value to Short.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun shortValue(exactRequired: Boolean): Short {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (
        exactRequired &&
          (compactIntegerPart > Short.MAX_VALUE.toLong() ||
            compactIntegerPart < Short.MIN_VALUE.toLong())
      ) {
        throw ArithmeticException("Cannot convert to short and provide exact value")
      }
      return compactIntegerPart.toShort()
    }
    return toBigInteger().shortValue(exactRequired)
  }

  /**
   * Convert the current value to UInt.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun uintValue(exactRequired: Boolean): UInt {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (
        exactRequired && (compactIntegerPart > UInt.MAX_VALUE.toLong() || compactIntegerPart < 0L)
      ) {
        throw ArithmeticException("Cannot convert to unsigned int and provide exact value")
      }
      return compactMagnitude(compactIntegerPart).toUInt()
    }
    return toBigInteger().uintValue(exactRequired)
  }

  /** Magnitude of a compact value as an unsigned word, mirroring `BigInteger.magnitude[0]`. */
  private fun compactMagnitude(value: Long): ULong =
    if (value < 0) (-value).toULong() else value.toULong()

  /**
   * Convert the current value to ULong.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun ulongValue(exactRequired: Boolean): ULong {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (exactRequired && compactIntegerPart < 0L) {
        throw ArithmeticException("Cannot convert to unsigned long and provide exact value")
      }
      return compactMagnitude(compactIntegerPart)
    }
    return toBigInteger().ulongValue(exactRequired)
  }

  /**
   * Convert the current value to UByte.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun ubyteValue(exactRequired: Boolean): UByte {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      if (
        exactRequired && (compactIntegerPart > UByte.MAX_VALUE.toLong() || compactIntegerPart < 0L)
      ) {
        throw ArithmeticException("Cannot convert to unsigned byte and provide exact value")
      }
      return compactMagnitude(compactIntegerPart).toUByte()
    }
    return toBigInteger().ubyteValue(exactRequired)
  }

  /**
   * Convert the current value to UShort.
   *
   * @param exactRequired True causes an ArithmeticException to be thrown if there is any loss of
   *   precision during the conversion, either side of the decimal. False truncates any precision to
   *   the right of the decimal.
   */
  override fun ushortValue(exactRequired: Boolean): UShort {
    checkWholeness(exactRequired)
    val compactIntegerPart = integerPartCompact()
    if (compactIntegerPart != CompactArithmetic.INFLATED) {
      // Mirrors the BigInteger check, which rejects negatives regardless of exactRequired.
      if (
        (exactRequired && compactIntegerPart > UShort.MAX_VALUE.toLong()) || compactIntegerPart < 0L
      ) {
        throw ArithmeticException("Cannot convert to unsigned short and provide exact value")
      }
      return compactMagnitude(compactIntegerPart).toUShort()
    }
    return toBigInteger().ushortValue(exactRequired)
  }

  /** @return true if "this" is a whole number, false if not */
  fun isWholeNumber(): Boolean {
    if (isCompact) {
      if (compact == 0L) {
        return true
      }
      // Digits below the unit position must all be zero.
      val unitExponent = exponent - CompactArithmetic.digitCount(compact) + 1
      if (unitExponent >= 0) {
        return true
      }
      val fractionalDigits = -unitExponent
      if (fractionalDigits > CompactArithmetic.MAX_POWER_OF_TEN) {
        return false
      }
      return compact % CompactArithmetic.powerOfTen(fractionalDigits.toInt()) == 0L
    }
    val res = abs().divrem(ONE)
    val isWholeNumber = res.second.isZero()
    return isWholeNumber
  }

  /**
   * @param exactRequired if not a whole number, throw an exception
   * @return true if this is a whole number, false if not
   * @throws ArithmeticException if any nonzero value to the right of the decimal
   */
  private fun checkWholeness(exactRequired: Boolean) {
    if (exactRequired && !isWholeNumber())
      throw ArithmeticException("Cannot convert to int and provide exact value")
  }

  /**
   * Narrow to a float.
   *
   * @param exactRequired if false, precision can be lost. If true, the current absolute value must
   *   be between Float.MAX_VALUE and Float.MIN_VALUE, with 7 or less digits of precision.
   * @throws ArithmeticException if exactRequired is true and any of the above conditions not met
   */
  override fun floatValue(exactRequired: Boolean): Float {
    if (exactRequired) {
      var exactPossible = true
      // IEEE 754 float
      // Exponent can be between -126 and 127 in base 2 or -38 and 38 in base 10 (2^-126 ~ 10^-38),
      // but subnormal
      // can go to -45, and since we check the significand part in a base 2 algorithm we should be
      // covering subnormal
      // values correctly as well
      if (exponent < -45L || exponent > 38L) {
        exactPossible = false
      }
      // For significand we can have a maximum of 24 bits (23 + 1 implicit)
      // If there is no decimal point at all we can count the bits after modifying for exponent,
      // but if there is we need to convert the fractional part to binary32 representation first.
      // Bit count:
      val totalBits =
        if (precision - exponent - 1 > 0) {
          // First find out where the decimal point will be
          val integerPart =
            if (exponent >= 0) {
              significand / BigInteger.TEN.pow(precision - exponent - 1)
            } else {
              BigInteger.ZERO
            }
          val integerPartBitLength = chosenArithmetic.bitLength(integerPart.magnitude)

          val fractionPart =
            (significand divrem BigInteger.TEN.pow(precision - exponent - 1)).remainder
          var fractionConvertTemp =
            BigDecimal(fractionPart, -1) // this will represent the integer xxxx as 0.xxxx
          val bitList = mutableListOf<Int>()
          var counter = 0
          while (fractionConvertTemp != ZERO && counter <= 24) {
            val multiplied = fractionConvertTemp * 2
            val bit =
              if (multiplied >= ONE) {
                1
              } else {
                0
              }
            bitList.add(bit)
            fractionConvertTemp =
              if (bit == 1) {
                (multiplied divrem ONE).second
              } else {
                multiplied
              }
            counter++
          }
          val bitSum = integerPartBitLength + bitList.size
          bitSum
        } else {
          // There is no fractional part so we need check if the distance between first non zero bit
          // and
          // last non zero bit is is less than or equal to 24
          val trailingZeroBits = chosenArithmetic.trailingZeroBits(significand.magnitude)
          val bitSum = chosenArithmetic.bitLength(significand.magnitude)
          bitSum - trailingZeroBits
        }

      if (totalBits > 24) {
        exactPossible = false
      }

      if (!exactPossible) {
        throw ArithmeticException("Value cannot be narrowed to float")
      }
    }

    /*
     * For large exponent values, use fallback string parse
     */
    val divExponent = precision - 1 - exponent
    val f = if (isCompact) compact else this.significand.longValue(exactRequired)
    return if (divExponent >= 0 && divExponent < float10pow.size) {
      f / float10pow[divExponent.toInt()]
    } else {
      this.toString().toFloat()
    }
  }

  /**
   * Narrow to a double.
   *
   * @param exactRequired if false, precision can be lost. If true, the current absolute value must
   *   be between Double.MAX_VALUE and Double.MIN_VALUE, with 16 or less digits of precision.
   * @throws ArithmeticException if exactRequired is true and any of the above conditions not met
   */
  override fun doubleValue(exactRequired: Boolean): Double {
    if (exactRequired) {
      var exactPossible = true
      // IEEE 754 double precision
      // Exponent can be between -1022 and 1023 in base 2 or -308 and 308 in base 10, but subnormal
      // numbers
      // can go to -324, and since we check the significand part in a base 2 algorithm we should be
      // covering subnormal
      // values correctly as well
      if (exponent < -324 || exponent > 308L) {
        exactPossible = false
      }
      // For significand we can have a maximum of 53 (52 + 1 implicit)
      // If there is no decimal point at all we can directly count the bits in significand and use
      // them,
      // but if there is we need to convert the fractional part to binary64 representation first.
      // Bit count:
      val totalBits =
        if (precision - exponent - 1 > 0) {
          // First find out where the decimal point will be
          val integerPart =
            if (exponent >= 0) {
              significand / BigInteger.TEN.pow(precision - exponent - 1)
            } else {
              BigInteger.ZERO
            }
          val integerPartBitLength = chosenArithmetic.bitLength(integerPart.magnitude)

          val fractionPart =
            (significand divrem BigInteger.TEN.pow(precision - exponent - 1)).remainder
          var fractionConvertTemp =
            BigDecimal(fractionPart, -1) // this will represent the integer xxxx as 0.xxxx
          val bitList = mutableListOf<Int>()
          var counter = 0
          while (fractionConvertTemp != ZERO && counter <= 53) {
            val multiplied = fractionConvertTemp * 2
            val bit =
              if (multiplied >= ONE) {
                1
              } else {
                0
              }
            bitList.add(bit)
            fractionConvertTemp =
              if (bit == 1) {
                (multiplied divrem ONE).second
              } else {
                multiplied
              }
            counter++
          }
          val bitSum = integerPartBitLength + bitList.size
          bitSum
        } else {
          // There is no fractional part so we need check if the distance between first non zero bit
          // and
          // last non zero bit is is less than or equal to 24
          // We can reuse the trailing zero bits to count number of zeroes from the right, because
          // we know
          // that in big integer first non-zero is always the leftmost bit
          val trailingZeroBits = chosenArithmetic.trailingZeroBits(significand.magnitude)
          val bitSum = chosenArithmetic.bitLength(significand.magnitude)
          bitSum - trailingZeroBits
        }

      if (totalBits > 53) {
        exactPossible = false
      }

      if (!exactPossible) {
        throw ArithmeticException("Value cannot be narrowed to float")
      }
    }

    /*
     *  For large exponent values, use fallback string parse
     */
    val divExponent = precision - 1 - exponent
    val l: Long
    if (isCompact) {
      l = compact
    } else {
      l = this.significand.longValue(exactRequired)
      if (this.significand > Long.MAX_VALUE || this.significand < Long.MIN_VALUE) {
        return toString().toDouble()
      }
    }
    return if (l.toDouble().toLong() == l && divExponent >= 0 && divExponent < double10pow.size) {
      (l / double10pow[divExponent.toInt()])
    } else {
      toString().toDouble()
    }
  }

  /**
   * Round using specific [DecimalMode] and return rounded instance. This is applied only to
   * significand.
   *
   * I.e.: 1.234E3 == 1234, rounded with DecimalMode(2, RoundingMode.CEILING) will return 1.3E3 ==
   * 1300
   *
   * 1.234E-3 == 0.001234, , rounded with DecimalMode(2, RoundingMode.CEILING) will return 1.3E-3 ==
   * 0.0013
   */
  fun roundSignificand(decimalMode: DecimalMode?): BigDecimal {
    if (decimalMode == null) {
      return this
    }
    if (isCompact) {
      roundSignificandCompact(compact, exponent, decimalMode)?.let {
        return it
      }
    }
    return Companion.roundSignificand(this.significand, this.exponent, decimalMode)
  }

  /**
   * Round the BigDecimal to a specific length. If position is set to 0, a ArithmeticException is
   * thrown. Use this for rounding when not using scale. I.e.
   *
   * 1234.5678 digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 1230 123.456
   * digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 123 0.0012345678 digitPosition 4,
   * rounding mode HALF_TOWARDS_ZERO will produce 0.001 0.0012345678 digitPosition 6, rounding mode
   * HALF_TOWARDS_ZERO will produce 0.00123
   */
  fun roundToDigitPosition(
    digitPosition: Long,
    roundingMode: RoundingMode = this.roundingMode,
  ): BigDecimal {
    if (digitPosition == 0L) {
      throw ArithmeticException("Rounding to 0 position is not supported")
    }
    val rounded =
      if (this.exponent >= 0) {
        roundSignificand(DecimalMode(digitPosition, roundingMode))
      } else {
        if (roundingMode == RoundingMode.ROUND_HALF_TO_EVEN) {
          (this + this.signum() * 2).roundSignificand(
            DecimalMode(
              digitPosition,
              roundingMode,
            )
          ) - this.signum() * 2
        } else {
          (this + this.signum()).roundSignificand(DecimalMode(digitPosition, roundingMode)) -
            this.signum()
        }
      }

    return rounded.rebuild(decimalMode = decimalMode)
  }

  /**
   * Round the BigDecimal to a specific length AFTER the decimal point. If position is set to 0 a
   * integer value is returned Use this only if not using scale.
   *
   * I.e.
   *
   * 1234.5678 digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 1234.568 123.456
   * digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 123.456 0.0012345678
   * digitPosition 3, rounding mode HALF_TOWARDS_ZERO will produce 0.001 0.0012345678 digitPosition
   * 5, rounding mode HALF_TOWARDS_ZERO will produce 0.00123
   */
  fun roundToDigitPositionAfterDecimalPoint(
    digitPosition: Long,
    roundingMode: RoundingMode,
  ): BigDecimal {
    if (digitPosition < 0) {
      throw ArithmeticException("This method doesn't support negative digit position")
    }
    val rounded =
      when {
        exponent >= 0 -> roundToDigitPosition(exponent + digitPosition + 1, roundingMode)
        exponent < 0 -> roundToDigitPosition(digitPosition + 1, roundingMode)
        else -> throw RuntimeException("Unexpected state")
      }
    return rounded.rebuild(decimalMode = decimalMode)
  }

  private fun getRidOfRadix(bigDecimal: BigDecimal): BigDecimal {
    val precision = bigDecimal.significandDigits()
    val newExponent = bigDecimal.exponent - precision + 1
    return bigDecimal.rebuild(exponent = newExponent, decimalMode = null)
  }

  /**
   * Brings the two big decimals to same exponents, which makes it easier to apply other operations
   */
  private fun bringSignificandToSameExponent(
    first: BigDecimal,
    second: BigDecimal,
  ): Triple<BigInteger, BigInteger, Long> {
    val firstPrepared = getRidOfRadix(first)
    val secondPrepared = getRidOfRadix(second)

    val firstPreparedExponent = firstPrepared.exponent
    val secondPreparedExponent = secondPrepared.exponent

    return when {
      first.exponent > second.exponent -> {
        val moveFirstBy = firstPreparedExponent - secondPreparedExponent
        if (moveFirstBy >= 0) {
          val movedFirst = firstPrepared.significand * 10.toBigInteger().pow(moveFirstBy)
          return Triple(movedFirst, second.significand, secondPreparedExponent)
        } else {
          val movedSecond = secondPrepared.significand * 10.toBigInteger().pow(moveFirstBy * -1)
          Triple(first.significand, movedSecond, firstPreparedExponent)
        }
      }

      first.exponent < second.exponent -> {
        val moveSecondBy = secondPreparedExponent - firstPreparedExponent
        return if (moveSecondBy >= 0) {
          val movedSecond = secondPrepared.significand * 10.toBigInteger().pow(moveSecondBy)
          Triple(first.significand, movedSecond, firstPreparedExponent)
        } else {
          val movedFirst = firstPrepared.significand * 10.toBigInteger().pow(moveSecondBy * -1)
          Triple(movedFirst, second.significand, firstPreparedExponent)
        }
      }

      first.exponent == second.exponent -> {
        val delta = firstPreparedExponent - secondPreparedExponent
        return when {
          delta > 0 -> {
            val movedFirst = first.significand * 10.toBigInteger().pow(delta)
            Triple(movedFirst, second.significand, firstPreparedExponent)
          }

          delta < 0 -> {
            val movedSecond = second.significand * 10.toBigInteger().pow(delta * -1)
            Triple(first.significand, movedSecond, firstPreparedExponent)
          }

          delta.compareTo(0) == 0 -> {
            Triple(first.significand, second.significand, firstPreparedExponent)
          }

          else -> throw RuntimeException("Invalid delta: $delta")
        }
      }

      else -> {
        throw RuntimeException(
          "Invalid comparison state BigInteger: ${first.exponent}, ${second.exponent}"
        )
      }
    }
  }

  /** Compare to other BigDecimal */
  fun compare(other: BigDecimal): Int {
    val sign = signum()
    val otherSign = other.signum()
    if (sign != otherSign) {
      return sign.compareTo(otherSign)
    }
    if (sign == 0) {
      return 0
    }
    // Both non-zero with the same sign. The representation keeps the magnitude within
    // [10^exponent, 10^(exponent + 1)), so differing exponents settle the order without any
    // arithmetic. Equal exponents only need the significands aligned by digit count, which is
    // bounded by the precision rather than by the exponent, so comparing values such as
    // 1E999999999 never materialises a power of ten of that size.
    if (exponent != other.exponent) {
      val magnitudeOrder = exponent.compareTo(other.exponent)
      return if (sign > 0) magnitudeOrder else -magnitudeOrder
    }
    val precisionDelta = precision - other.precision
    if (isCompact && other.isCompact) {
      when {
        precisionDelta == 0L -> return compact.compareTo(other.compact)
        precisionDelta > 0L -> {
          val aligned = CompactArithmetic.scaleByPowerOfTen(other.compact, precisionDelta)
          if (aligned != CompactArithmetic.INFLATED) {
            return compact.compareTo(aligned)
          }
        }
        else -> {
          val aligned = CompactArithmetic.scaleByPowerOfTen(compact, -precisionDelta)
          if (aligned != CompactArithmetic.INFLATED) {
            return aligned.compareTo(other.compact)
          }
        }
      }
    }
    return when {
      precisionDelta == 0L -> significand.compare(other.significand)
      precisionDelta > 0L ->
        significand.compare(other.significand * BigInteger.TEN.pow(precisionDelta))
      else -> (significand * BigInteger.TEN.pow(-precisionDelta)).compare(other.significand)
    }
  }

  override fun compareTo(other: Any): Int {
    if (other is Number) {
      if (
        RuntimePlatform.currentPlatform() == Platform.JS ||
          RuntimePlatform.currentPlatform() == Platform.WASMJS
      ) {
        return javascriptNumberComparison(other)
      }
    }
    return when (other) {
      is BigDecimal -> compare(other)
      is Long -> compare(fromLong(other))
      is Int -> compare(fromInt(other))
      is Short -> compare(fromShort(other))
      is Byte -> compare(fromByte(other))
      is Double -> compare(fromDouble(other))
      is Float -> compare(fromFloat(other))
      else ->
        throw RuntimeException("Invalid comparison type for BigDecimal: ${other::class.simpleName}")
    }
  }

  /**
   * Javascrpt doesn't have different types for float, integer, long, it's all just "number", so we
   * need to check if it's a decimal or integer number before comparing.
   */
  private fun javascriptNumberComparison(number: Number): Int {
    val double = number.toDouble()
    return when {
      double > Long.MAX_VALUE -> {
        compare(parseString(double.toString()))
      } // This whole block can be removed after 1.6.20 and
      // https://github.com/JetBrains/kotlin/pull/4364
      double % 1 == 0.0 -> compare(fromLong(number.toLong()))
      else -> compare(number.toDouble().toBigDecimal())
    }
  }

  override fun equals(other: Any?): Boolean {
    val comparison =
      when (other) {
        is BigDecimal -> compare(other)
        is Long -> compare(fromLong(other))
        is Int -> compare(fromInt(other))
        is Short -> compare(fromShort(other))
        is Byte -> compare(fromByte(other))
        is Double -> compare(fromDouble(other))
        is Float -> compare(fromFloat(other))
        else -> -1
      }
    return comparison == 0
  }

  override fun hashCode(): Int {
    if (this.isZero()) {
      return 0
    }
    if (isCompact) {
      // Same value as hashing the trailing-zero-stripped BigInteger form.
      return CompactArithmetic.significandHashCode(CompactArithmetic.removeTrailingZeros(compact)) +
        exponent.hashCode()
    }
    return removeTrailingZeroes(this).significand.hashCode() + exponent.hashCode()
  }

  /** Return this BigDecimal in scientific notation i.e. 1.23E+9 */
  override fun toString(): String {
    if (useToStringExpanded) {
      return toStringExpanded()
    }
    val significandString = if (isCompact) compact.toString() else significand.toString(10)
    val modifier =
      if (signum() < 0) {
        2
      } else {
        1
      }
    val expand =
      if (significandString.dropLastWhile { it == '0' }.length <= 1) {
        "0"
      } else {
        ""
      }

    return when {
      exponent > 0 -> {
        "${
                    placeADotInString(
                        significandString,
                        significandString.length - modifier,
                    )
                }${expand}E+$exponent"
      }

      exponent < 0 -> {

        "${
                    placeADotInString(
                        significandString,
                        significandString.length - modifier,
                    )
                }${expand}E$exponent"
      }

      exponent == 0L -> {
        "${
                    placeADotInString(
                        significandString,
                        significandString.length - modifier,
                    )
                }$expand"
      }

      else ->
        throw RuntimeException("Invalid state, please report a bug (Integer compareTo invalid)")
    }
  }

  /** Convenience method matching Java BigDecimal, same functionality as toStringExpanded */
  fun toPlainString(): String {
    // TODO this is a naive and inefficient implementation, it would be better to consider scale
    // before the
    // string is expanded.
    val expandedString = toStringExpanded()
    val finalString =
      if (usingScale && scale > 0) {
        val split = expandedString.split(".")
        if (split.size == 1) {
          expandedString + "." + '0' * scale
        } else {
          val missingZeroCount = scale - split[1].length
          if (missingZeroCount > 0) {
            expandedString + '0' * missingZeroCount
          } else {
            expandedString
          }
        }
      } else {
        expandedString
      }
    return finalString
  }

  /**
   * Return this big decimal in expanded notation. i.e. 123000000 for 1.23E+9 or 0.00000000123 for
   * 1.23E-9
   */
  fun toStringExpanded(): String {
    if (this.isZero()) {
      return "0"
    }
    val digits = significandDigits()
    if (exponent > Int.MAX_VALUE) {
      throw RuntimeException("Invalid toStringExpanded request (exponent > Int.MAX_VALUE)")
    }
    val significandString =
      if (isCompact) {
        (if (compact < 0) -compact else compact).toString()
      } else {
        significand.toStringWithoutSign(10)
      }
    val sign =
      if (signum() < 0) {
        "-"
      } else {
        ""
      }

    val adjusted =
      when {
        exponent > 0 -> {
          val diffBigInt = (exponent - digits + 1)

          if (diffBigInt > 0) {
            val expandZeros = diffBigInt * '0'
            significandString + expandZeros
          } else {
            placeADotInStringExpanded(
              significandString,
              significandString.length - exponent.toInt() - 1,
            )
          }
        }

        exponent < 0 -> {

          val diffInt = exponent.toInt().absoluteValue

          if (diffInt > 0) {
            val expandZeros = exponent.absoluteValue * '0'
            placeADotInStringExpanded(
              expandZeros + significandString,
              diffInt + significandString.length - 1,
            )
          } else {
            placeADotInStringExpanded(significandString, significandString.length - 1)
          }
        }

        exponent == 0L -> {
          if (digits == 1L) {
            return sign + significandString
          }
          placeADotInStringExpanded(significandString, significandString.length - 1)
        }

        else ->
          throw RuntimeException("Invalid state, please report a bug (Integer compareTo invalid)")
      }
    return sign + adjusted
  }

  private fun noExponentStringtoScientificNotation(input: String): String {
    return placeADotInString(input, input.length - 1) + "E+${input.length - 1}"
  }

  private fun placeADotInStringExpanded(input: String, position: Int): String {

    val prefix = input.substring(0 until input.length - position)
    val suffix =
      input.substring(input.length - position until input.length).dropLastWhile { it == '0' }

    return if (suffix.isNotEmpty()) {
      ("$prefix.$suffix")
    } else {
      prefix
    }
  }

  private fun placeADotInString(input: String, position: Int): String {

    val prefix = input.substring(0 until input.length - position)
    val suffix = input.substring(input.length - position until input.length)
    val prepared = "$prefix.$suffix"

    return prepared.dropLastWhile { it == '0' }
  }

  operator fun Long.times(char: Char): String {
    if (this < 0) {
      throw RuntimeException("Char cannot be multiplied with negative number")
    }
    var counter = this
    val stringBuilder = StringBuilder()
    while (counter > 0) {
      stringBuilder.append(char)
      counter--
    }
    return stringBuilder.toString()
  }
}
