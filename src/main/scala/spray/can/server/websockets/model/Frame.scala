package spray.can.server.websockets.model

import java.nio.ByteBuffer
import java.io.{DataOutputStream, ByteArrayOutputStream}
import akka.util.ByteString
import spray.io._
import scala.Some
import scala.Some
import scala.Some
import spray.can.server.websockets.model.OpCode.{Ping, ConnectionClose}

/**
 * Deals with serializing/deserializing Frames from Bytes
 */
object Frame{

  def serializeCloseCode(code: Short) = ByteString(
    ByteBuffer.allocate(2)
      .putShort(code)
      .rewind().asInstanceOf[ByteBuffer]
  )


  sealed trait ParsedFrame
  case class Successful(frame: Frame) extends ParsedFrame
  case object Incomplete extends ParsedFrame
  case object TooLarge extends ParsedFrame
  def read(in: ByteBuffer, maxMessageLength: Long = Long.MaxValue): ParsedFrame = {

    in.mark()
    if (in.remaining() < 2) {
      in.reset()
      return Incomplete
    }
    val b0 = in.get
    val FIN = ((b0 >> 7) & 1) != 0

    val RSV = (
      ((b0 >> 6) & 1) != 0,
      ((b0 >> 5) & 1) != 0,
      ((b0 >> 4) & 1) != 0
    )
    val opcode = OpCode(b0 & 0xf)

    val b1 = in.get
    val mask = (b1 >> 7) & 1
    val payloadLength = (b1 & 127) match{
      case 126 =>
        if (in.remaining() < 2) {
          in.reset()
          return Incomplete
        }
        in.getShort & 0xffff
      case 127 =>
        if (in.remaining() < 4) {
          in.reset()
          return Incomplete
        }
        in.getLong
      case x => x
    }
    val maskingKey = if (mask != 0) Some(in.getInt) else None

    if (payloadLength > maxMessageLength) {
      in.reset()
      TooLarge
    } else if (in.remaining() < payloadLength) {
      in.reset()
      Incomplete
    } else Successful{
      val data = new Array[Byte](payloadLength.toInt)
      in.get(data)

      for(m <- maskingKey) maskArray(data, m)

      Frame(FIN, RSV, opcode, maskingKey, ByteString(data))
    }
  }

  def write(f: Frame): Array[Byte] = {
    import f._
    val byteOutStream = new ByteArrayOutputStream()
    val out = new DataOutputStream(byteOutStream)

    out.writeByte(
      (FIN.b << 7) |
      (RSV._1.b << 6) |
      (RSV._2.b << 5) |
      (RSV._3.b << 4) |
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
    byteOutStream.toByteArray
  }

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
}

case class Frame(FIN: Boolean = true,
                 RSV: (Boolean, Boolean, Boolean) = (false, false, false),
                 opcode: OpCode,
                 maskingKey: Option[Int] = None,
                 data: ByteString = ByteString.empty){

  def stringData = new String(data.toArray, "UTF-8")
  implicit class x(bool: Boolean){ def b = if (bool) 1 else 0 }

}

