package websockets


import scala.concurrent.duration._
import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import spray.util._
import spray.io._
import java.net.InetSocketAddress
import spray.io.IOBridge.Key
import spray.http.{HttpHeaders, HttpRequest}
import java.nio.{ByteBuffer, CharBuffer}
import java.security.MessageDigest
import websockets.Frame.BitWriter

object Main  {
  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("echo-server")

  HttpHeaders
  spray.http.HttpHeader
  def main(args: Array[String]){
    // and our actual server "service" actor
    val server = system.actorOf(Props(new EchoServer), name = "echo-server")

    // we bind the server to a port on localhost and hook
    // in a continuation that informs us when bound
    server.ask(IOServer.Bind("localhost", 80))(1 second span)
          .onSuccess { case IOServer.Bound(endpoint, _) => println("\nBound echo-server to " + endpoint) }
  }
}

class EchoServer extends IOServer {
  val ioBridge = IOExtension(context.system).ioBridge()

  override def bound(endpoint: InetSocketAddress, bindingKey: Key, bindingTag: Any): Receive =
    super.bound(endpoint, bindingKey, bindingTag) orElse {

      case IOBridge.Received(handle, buffer) =>
        buffer.array.asString.trim match {
          case "STOP" =>
            ioBridge ! IOBridge.Send(handle, BufferBuilder("Shutting down...").toByteBuffer)
            println("Shutting down")
            context.system.shutdown()
          case x =>
            println("Received")
            println(x)
            try {
              val headers = x.lines.drop(1).map(_.split(": ")).map(x => x(0) -> x(1)).toMap
              val byteHash = MessageDigest.getInstance("SHA-1")
                .digest(
                (headers("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
              )
              println(byteHash.length)
              val returnValue =new sun.misc.BASE64Encoder().encode(byteHash)

              val b = "HTTP/1.1 101 Switching Protocols\r\n" +
                      "Upgrade: websocket\r\n" +
                      "Connection: Upgrade\r\n" +
                      s"Sec-WebSocket-Accept: $returnValue\r\n\r\n"

              ioBridge ! IOBridge.Send(handle, ByteBuffer.wrap(b.getBytes), Some('SentOk))
            }catch{case _: Exception =>
              println("Exception!")
              val f = Frame.make(buffer.array)
              val newF = f.copy(maskingKey = None)
              ioBridge ! IOBridge.Send(handle, ByteBuffer.wrap(newF.write), Some('SentOk))
            }
        }

      case 'SentOk =>
        println("Send completed")

      case IOBridge.Closed(_, reason) =>
        println("Connection closed: {}", reason)
    }
}

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
    def byte = in(cursor)
    def next = {cursor += 1; in(cursor)}

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
    println("cursor: " + cursor)
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
