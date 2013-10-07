package spray.can.server.websockets

import org.scalatest.FreeSpec
import org.scalatest.concurrent.Eventually
import akka.io.{Tcp, IO}
import spray.can.Http
import spray.can.server.ServerSettings
import akka.actor.{ActorSystem, Actor}
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.can.server.websockets.model.OpCode.Text
import akka.util.ByteString
import akka.testkit.TestActorRef
import java.net.InetSocketAddress
import spray.http.HttpRequest
import akka.io.Tcp.{Register, Connected}


class SocketExample extends FreeSpec with Eventually{

//  "Hello World" in {
//    implicit val system = ActorSystem()
//
//    // Define the server that accepts http requests and upgrades them
//    class Server extends Actor{
//      def receive = {
//        case req: HttpRequest =>
//          sender ! Sockets.acceptAllFunction(req)
//          sender ! Sockets.Upgrade(self)
//
//        case x: Connected =>
//          sender ! Register(self)
//
//        case f @ Frame(fin, rsv, Text, maskingKey, data) =>
//          sender ! Frame(fin, rsv, Text, None, ByteString(f.stringData.toUpperCase))
//      }
//    }
//
//    IO(Sockets) ! Http.Bind(TestActorRef(new Server), "localhost", 12345)
//
//    // A crappy ad-hoc websocket client
//    val client = TestActorRef(new Util.TestClientActor(ssl = false))
//
//    IO(Tcp).!(Tcp.Connect(new InetSocketAddress("localhost", 12345)))(client)
//
//    // Send the http-ish handshake
//    client await Tcp.Write(ByteString(
//      "GET /mychat HTTP/1.1\r\n" +
//      "Host: server.example.com\r\n" +
//      "Upgrade: websocket\r\n" +
//      "Connection: Upgrade\r\n" +
//      "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"
//    ))
//
//    // Send the websocket frame
//    val res = client await Frame(true, (false, false, false), OpCode.Text, Some(12345123), ByteString("i am cow"))
//    assert(res.data.decodeString("UTF-8") == "I AM COW")
//  }

}
