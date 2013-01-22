package websockets


import scala.concurrent.duration._
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.pattern.ask
import spray.util._
import spray.io._
import java.net.InetSocketAddress
import spray.io.IOBridge.{Connection, Key}
import java.nio.ByteBuffer
import java.security.MessageDigest
import spray.can.server.HttpServer
import spray.can.server.StatsSupport.StatsHolder
import spray.http.{HttpResponse, HttpRequest}

import scala.Some
import spray.http.HttpResponse

object Main  {
  def main(args: Array[String]){
    implicit val system = ActorSystem("echo-server")

    val server = system.actorOf(Props(new EchoServer), name = "echo-server")

    server.ask(IOServer.Bind("localhost", 80))(1 second span)
          .onSuccess { case IOServer.Bound(endpoint, _) => println("\nBound echo-server to " + endpoint) }
  }

}


class EchoServer extends IOServer {
  val ioBridge = IOExtension(context.system).ioBridge()

  override def bound(endpoint: InetSocketAddress, bindingKey: Key, bindingTag: Any): Receive =
    super.bound(endpoint, bindingKey, bindingTag) orElse {
      case IOBridge.Received(connection, buffer) =>
        try {

          val headers = buffer.array.asString.trim.lines.drop(1).map(_.split(": ")).map(x => x(0) -> x(1)).toMap

          val byteHash = MessageDigest.getInstance("SHA-1")
            .digest(
            (headers("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
          )

          val returnValue =new sun.misc.BASE64Encoder().encode(byteHash)

          val b = "HTTP/1.1 101 Switching Protocols\r\n" +
                  "Upgrade: websocket\r\n" +
                  "Connection: Upgrade\r\n" +
                  s"Sec-WebSocket-Accept: $returnValue\r\n\r\n"

          ioBridge ! IOBridge.Send(connection, ByteBuffer.wrap(b.getBytes), Some('SentOk))
        }catch{ case _: Exception =>
          println("Exception!")
          val f = model.Frame.make(buffer.array)
          val newF = f.copy(maskingKey = None, data = f.data.toUpperCase())
          ioBridge ! IOBridge.Send(connection, ByteBuffer.wrap(newF.write), Some('SentOk))
        }

      case IOBridge.Closed(_, reason) =>
        println("Connection closed: {}", reason)
    }
}



