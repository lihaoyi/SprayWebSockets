package spray.can.server.websockets.model

import java.nio.ByteBuffer
import java.io._
import akka.util.ByteString
import spray.io._
import scala.Some
import scala.Some
import scala.Some
import spray.can.server.websockets.model.OpCode.{Ping, ConnectionClose}
import java.nio.charset.{CodingErrorAction, CharacterCodingException, Charset}
import scala.Some
import java.util.regex.Pattern

/**
 * Deals with serializing/deserializing Frames from Bytes
 */
object Frame{
  /**
   * Mutates the given byte array by XORing it with the given Int mask
   */
  def maskArray(array: Array[Byte], mask: Int) = {
    var i = 0
    while (i < array.length){
      val j = 3 - i % 4
      array(i) = (array(i) ^ (mask >> (8 * j)) & 0xff).toByte
      i += 1
    }
  }

  sealed trait ParsedFrame
  case class Successful(frame: Frame) extends ParsedFrame
  case object Incomplete extends ParsedFrame
  case object TooLarge extends ParsedFrame
  case object Invalid extends ParsedFrame
  def read(in: DataInputStream, maxMessageLength: Long = Long.MaxValue): ParsedFrame = {
    if (in.available() < 2) return Incomplete

    val b0 = in.readByte()
    val FIN = ((b0 >> 7) & 1) != 0

    val RSV = (b0 >> 4) & 7

    val opcode = OpCode.all.get(b0 & 0xf) match{
      case Some(x) => x
      case None => return Invalid
    }

    val b1 = in.readByte()
    val mask = (b1 >> 7) & 1
    val payloadLength = (b1 & 127) match{
      case 126 =>
        if (in.available() < 2) return Incomplete
        in.readShort & 0xffff
      case 127 =>
        if (in.available() < 4) return Incomplete
        in.readLong

      case x => x
    }
    val maskingKey =
      if (mask != 0) {
        if (in.available() < 4) return Incomplete
        Some(in.readInt)
      } else {
        None
      }

    if (payloadLength > maxMessageLength) TooLarge
    else if (in.available() < payloadLength) Incomplete
    else {
      val data = new Array[Byte](payloadLength.toInt)
      in.read(data)
      for(m <- maskingKey) maskArray(data, m)

      val frame = Frame(FIN, RSV.toByte, opcode, maskingKey, ByteString(data))
      Successful(frame)
    }
  }

  def write(f: Frame): ByteString = {
    import f._
    val byteOutStream = new ByteArrayOutputStream()
    val out = new DataOutputStream(byteOutStream)

    out.writeByte(
      (FIN.b << 7) |
      (RSV << 4) |
      opcode.value
    )
    val b1 = (maskingKey.isDefined.b << 7) | (
      data.length match {
        case x if x <= 125 => x
        case x if x < (1 << 16) => 126
        case x => 127
      }
    )

    out.writeByte(b1)
    (b1 & 127) match {
      case x if x <= 125 => ()
      case 126 => out.writeShort(data.length)
      case 127 => out.writeLong(data.length)
    }

    for (m <- maskingKey){
      out.writeInt(m)
    }

    val array = data.toArray
    for(m <- maskingKey) maskArray(array, m)
    out.write(array)
    ByteString(byteOutStream.toByteArray)
  }


}

case class Frame(FIN: Boolean = true,
                 RSV: Byte = 0,
                 opcode: OpCode,
                 maskingKey: Option[Int] = None,
                 data: ByteString = ByteString.empty){

  lazy val stringData = data.utf8String

  implicit class x(bool: Boolean){ def b = if (bool) 1 else 0 }

}

