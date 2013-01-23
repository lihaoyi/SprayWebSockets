package websockets.model

import websockets.model.Frame.BitWriter

object Frame{
  class BitReader(in: Array[Byte]){
    private[this] var cursor = 0
    def getCursor = cursor
    def bit: Boolean = {
      val res = (in(cursor / 8) & (128 >> (cursor % 8))) != 0
      cursor += 1
      res
    }
    def bits(n: Int): Long = {
      var total = 0L
      var count = 0
      while(count < n){ count += 1
        total *= 2
        if (bit) total += 1
      }
      total
    }
  }
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

  def make(in: Array[Byte]) = {
    val s = new BitReader(in)
    import s._

    val FIN = bit
    val RSV = (bit, bit, bit)
    val opcode = bits(4).toByte
    val mask = bit
    val payloadLength = bits(7) match{
      case 126 => 126 + bits(16)
      case 127 => 127 + bits(64)
      case x => x
    }
    val maskingKey = if (mask) Some(bits(32).toInt) else None

    val data =
      in.drop(getCursor / 8)
        .take(payloadLength.toInt)
        .zipWithIndex
        .map {case (b, i) =>
          val j = 3 - i % 4
          (b ^ ((maskingKey.getOrElse(0) >> (8 * j)) & 0xff)).toByte
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

