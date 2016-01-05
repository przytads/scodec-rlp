package com.github.sethereum

import java.lang.{Integer => javaInt, Long => javaLong}
import java.nio.charset.Charset

import scodec.Attempt.Successful
import scodec.bits.{ByteVector, BitVector}
import scodec.codecs._
import scodec._
import shapeless._

import scala.language.implicitConversions
import scala.reflect.ClassTag

/**
 * Codec support for RLP encoding, as defined by the Ethereum project.
 *
 * See: https://github.com/ethereum/wiki/wiki/RLP
 * See: http://gavwood.com/paper.pdf (Appendix B)
 */
package object rlp {

  // Basic data type codecs

  private def integralRlpCodec[A](c: RlpString[A] => Codec[A], f: A => RlpString[A]) =
    RlpCodec(RlpString.codec[A].narrow[A](s => c(s).decode(s.bits).map(_.value), f))

  implicit val rlpBool = integralRlpCodec[Boolean](s => bool(s.bits.length.toInt), RlpString.apply)
  implicit val rlpByte = integralRlpCodec[Byte](s => byte(s.bits.length.toInt), RlpString.apply)
  implicit val rlpShort = integralRlpCodec[Short](_ => LeftTrimmedShortCodec, RlpString.apply)
  implicit val rlpInt = integralRlpCodec[Int](_ => LeftTrimmedIntCodec, RlpString.apply)
  implicit val rlpLong = integralRlpCodec[Long](_ => LeftTrimmedLongCodec, RlpString.apply)

  // BigInt codec
  // Note that BigInts are already left trimmed
  private val bigIntCodec: Codec[BigInt] = bytes.xmap(bytes => BigInt(bytes.toArray), x => ByteVector(x.toByteArray))
  implicit val rlpBigInt = integralRlpCodec[BigInt](_ => bigIntCodec, RlpString.apply)

  implicit def rlpChar(implicit charset: Charset) = RlpCodec(RlpString.codec[Char].narrow[Char](
    s => string.decode(s.bits).map(_.value.charAt(0)),
    RlpString.apply
  ))

  implicit def rlpString(implicit charset: Charset) = RlpCodec(RlpString.codec[String].narrow[String](
    s => string.decode(s.bits).map(_.value),
    s => RlpString.apply(BitVector(s.getBytes(charset)))
  ))

  // Array codec

  // TODO: Possible efficiency improvement, if needed
  implicit def rlpArray[A : ClassTag](implicit itemCodec: RlpCodec[A]): RlpCodec[Array[A]] =
    RlpCodec(rlpList(itemCodec).xmap[Array[A]](_.toArray, _.toList))


  // List codecs

  implicit def rlpList[A](implicit itemCodec: RlpCodec[A]): RlpCodec[List[A]] = {
    val lengthCodec = RlpList.codec[A].xmap[Int](_.length.toInt, l => RlpList(l))
    RlpCodec(listOfN(lengthCodec, itemCodec))
  }

  // Codec for heterogeneous lists (HList)
  implicit def rlpHList[H <: HList](codec: Codec[H]): Codec[H] = {

    def encode(h: H) = for {
      length <- RlpList.codec.encode(RlpList(h.runtimeLength))
      items <- codec.encode(h)
    } yield length ++ items

    def decode(bits: BitVector) = for {
      list <- RlpList.codec[H].decode(bits)
      items <- codec.decode(list.remainder).flatMap { result => result.value.runtimeLength match {
        case list.value.length => Attempt.successful(result)
        case invalid => Attempt.failure(Err(s"invalid list length (expected: ${list.value.length}, actual: ${invalid})"))
      }}
    } yield items

    RlpCodec(Codec[H](encode _, decode _))
  }


  // Utility functions

  def leftTrimmedBytesLength(value: Int) = Math.max(javaInt.SIZE - javaInt.numberOfLeadingZeros(value), 8) / 8
  def leftTrimmedBytesLength(value: Long) = Math.max(javaLong.SIZE - javaLong.numberOfLeadingZeros(value), 8) / 8

}
