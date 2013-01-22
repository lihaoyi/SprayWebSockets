package websockets.model

import websockets.model.Frame.BitWriter

/**
 * Created with IntelliJ IDEA.
 * User: Haoyi
 * Date: 1/22/13
 * Time: 2:50 AM
 * To change this template use File | Settings | File Templates.
 */
object Frame{
  class BitReader(in: Array[Byte]){
    var cursor = 0
    def bit: Boolean = {
      val res = (in(cursor / 8) & (128 >> (cursor % 8))) != 0
      cursor += 1
      res
    }
    def bits(n: Int): Long = {
      var total = 0L
      for(i <- 1 to n){
        total *= 2
        if (bit) total += 1
      }
      total
    }
  }
  class BitWriter{
    var out: List[Byte] = Nil
    var cursor = 0
    def toArray = out.reverse.toArray
    def bit(b: Boolean) = {
      if(cursor % 8 == 0) out = 0.toByte :: out

      if (b) out = (out.head + (128 >> (cursor % 8))).toByte :: out.tail

      cursor = cursor + 1
    }
    def bits(n: Int, l: Long) = {
      for(i <- n-1 to 0 by -1) bit(((l >> i) & 1) != 0)
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
      in.drop(cursor / 8)
        .take(payloadLength.toInt)
        .zipWithIndex
        .map {case (b, i) =>
          val j = 3 - i % 4
          (b ^ ((maskingKey.getOrElse(0) >> (8 * j)) & 0xff)).toByte
        }

    Frame(FIN, RSV, opcode, payloadLength, maskingKey, new String(data, "UTF-8"))
  }
}
case class Frame(FIN: Boolean,
                 RSV: (Boolean, Boolean, Boolean),
                 opcode: Byte,
                 payloadLength: Long,
                 maskingKey: Option[Int],
                 data: String){

  def write: Array[Byte] = {
    val writer = new BitWriter()
    import writer._
    bit(FIN)
    bit(RSV._1)
    bit(RSV._2)
    bit(RSV._3)
    bits(4, opcode)
    bit(maskingKey.isDefined)
    payloadLength match{
      case 126 => bits(7, 126); bits(16, payloadLength - 126)
      case 127 => bits(7, 127); bits(64, payloadLength - 127)
      case x => bits(7, x)
    }
    maskingKey match {
      case Some(m) => bits(32, m)
      case _ => ()
    }
    bytes(data.getBytes.zipWithIndex
      .map {case (b, i) =>
      val j = 3 - i % 4
      (b ^ ((maskingKey.getOrElse(0) >> (8 * j)) & 0xff)).toByte
    })
    writer.toArray
  }
}

