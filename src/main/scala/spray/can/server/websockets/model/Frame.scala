package spray.can.server.websockets.model

import java.nio.ByteBuffer
import java.io.{DataOutputStream, ByteArrayOutputStream}
import akka.util.ByteString

object Frame{
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
        in.getShort
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

      for{
        m <- maskingKey
        i <- 0 until data.length
      }{
        val j = 3 - i % 4
        data(i) = (data(i) ^ (m >> (8 * j)) & 0xff).toByte
      }

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
        case x if x < (2 << 16) => 126
        case x => 127
      }
    )

    out.writeByte(b1)

    data.length match {
      case x if x <= 125 => ()
      case x if x < (2 << 16) => out.writeShort(data.length)
      case x => out.writeLong(data.length)
    }

    for (m <- maskingKey){
      out.writeInt(m)
    }

    val array = data.toArray
    for{
      m <- maskingKey
      i <- 0 until data.length
    }{
      val j = 3 - i % 4
      array(i) = (array(i) ^ (m >> (8 * j)) & 0xff).toByte
    }
    out.write(array)
    byteOutStream.toByteArray
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

