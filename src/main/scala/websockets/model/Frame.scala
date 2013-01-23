package websockets.model

import websockets.model.Frame.BitWriter
import java.nio.ByteBuffer

object Frame{
  class BitWriter{
    private[this] var out: List[Byte] = Nil
    private[this] var cursor = 0
    def toArray = out.reverse.toArray
    def bit(b: Boolean) = {
      if(cursor % 8 == 0) out = 0.toByte :: out
      if (b) out = (out.head + (128 >> (cursor % 8))).toByte :: out.tail
      cursor = cursor + 1
    }

    def bits(n: Int, l: Long) = {
      var count = n
      while(count > 0){ count -= 1
        bit(((l >> count) & 1) != 0)
      }
    }
    def bytes(b: Array[Byte]) = {
      out = b.toList.reverse ::: out
    }
  }

  def make(in: ByteBuffer) = {


    val b0 = in.get
    val FIN = ((b0 >> 7) & 1) != 0
    val RSV = (
      ((b0 >> 6) & 1) != 0,
      ((b0 >> 5) & 1) != 0,
      ((b0 >> 4) & 1) != 0
    )
    val opcode = b0 & 0xf
    val b1 = in.get
    val mask = (b1 >> 7) & 1
    val payloadLength = (b1 & 127) match{
      case 126 => 126 + in.getShort
      case 127 => 127 + in.getLong
      case x => x
    }

    val maskingKey = if (mask != 0) Some(in.getInt) else None


    val data = new Array[Byte](payloadLength.toInt)
    in.get(data)

    for{
      m <- maskingKey
      i <- 0 until data.length
    }{
      val j = 3 - i % 4
      data(i) = (data(i) ^ (m >> (8 * j)) & 0xff).toByte
    }

    Frame(FIN, RSV, OpCode(opcode), maskingKey, data)
  }
}

case class Frame(FIN: Boolean,
                 RSV: (Boolean, Boolean, Boolean),
                 opcode: OpCode,
                 maskingKey: Option[Int],
                 data: Array[Byte]){

  def stringData = new String(data, "UTF-8")
  def write: Array[Byte] = {
    val writer = new BitWriter()
    import writer._
    bit(FIN)
    bit(RSV._1)
    bit(RSV._2)
    bit(RSV._3)
    bits(4, opcode.value)
    bit(maskingKey.isDefined)
    val payloadLength = data.length
    payloadLength match{
      case 126 => bits(7, 126); bits(16, payloadLength - 126)
      case 127 => bits(7, 127); bits(64, payloadLength - 127)
      case x => bits(7, x)
    }
    maskingKey match {
      case Some(m) => bits(32, m)
      case _ => ()
    }
    bytes(data.zipWithIndex
      .map {case (b, i) =>
      val j = 3 - i % 4
      (b ^ ((maskingKey.getOrElse(0) >> (8 * j)) & 0xff)).toByte
    })
    writer.toArray
  }
}

