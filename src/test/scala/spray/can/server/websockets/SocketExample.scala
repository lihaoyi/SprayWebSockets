package spray.can.server.websockets

import org.scalatest.FreeSpec
import org.scalatest.concurrent.Eventually
import akka.io.{Tcp, IO}
import spray.can.{Http}
import spray.can.server.ServerSettings
import akka.actor.{ActorRef, ActorSystem, Actor}
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.can.server.websockets.model.OpCode.Text
import akka.util.ByteString
import akka.testkit.TestActorRef
import java.net.InetSocketAddress
import spray.http.{HttpResponse, HttpHeaders, HttpMethods, HttpRequest}
import akka.io.Tcp.{Register, Connected}
import spray.can.client.ClientConnectionSettings
import scala.concurrent.Await
import akka.pattern._
import scala.concurrent.duration._
import HttpHeaders._
class SocketExample extends FreeSpec with Eventually{

  "Hello World" in {
    implicit val system = ActorSystem()
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET,  "/mychat", List(
      Host("server.example.com", 80),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    class SocketServer extends Actor{
      def receive = {
        case x: Tcp.Connected => sender ! Register(self) // normal Http server init

        case req: HttpRequest =>
          // Upgrade the connection to websockets if you think the incoming
          // request looks good
          if (true){
            // upgrade the pipeline
            sender ! Sockets.UpgradeServer(Sockets.acceptAllFunction(req), self)
          }

        case Sockets.Upgraded => // do nothing

        case f @ Frame(fin, rsv, Text, maskingKey, data) =>
          // Reply to frames with the text content capitalized
          sender ! Frame(
            opcode = OpCode.Text,
            data = ByteString(f.stringData.toUpperCase)
          )
      }
    }


    class SocketClient extends Actor{
      var result: Frame = null

      def receive = {
        case x: Tcp.Connected =>
          // send an upgrade request immediately when connected
          sender ! Sockets.UpgradeClient(upgradeReq, self)

        case resp: HttpResponse =>
          // by the time this comes back, the server's pipeline should
          // already be upgraded
          sender ! Frame(
            opcode = OpCode.Text,
            maskingKey = Some(12345),
            data = ByteString("i am cow")
          )

        case Sockets.Upgraded =>
          // The client's pipeline is upgraded, but the server's may not be

        case f: Frame =>
          result = f // save the result
      }
    }
    val server = TestActorRef(new SocketServer)

    IO(Sockets) ! Http.Bind(
      server,
      "localhost",
      12345
    )

    implicit val client = TestActorRef(new SocketClient)
    IO(Sockets) ! Http.Connect(
      "localhost",
      12345
    )

    val result = eventually{client.underlyingActor.result.stringData}

    assert(result == "I AM COW")
  }
}
