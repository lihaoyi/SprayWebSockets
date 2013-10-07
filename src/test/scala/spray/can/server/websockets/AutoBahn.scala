package spray.can.server.websockets

import org.scalatest.FreeSpec
import org.scalatest.concurrent.Eventually
import akka.io.{Tcp, IO}
import spray.can.Http
import akka.actor.{Props, ActorSystem, Actor}
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.can.server.websockets.model.OpCode.Text
import akka.util.ByteString
import spray.http.{HttpResponse, HttpHeaders, HttpMethods, HttpRequest}
import akka.io.Tcp.Register
import scala.concurrent.duration._
import HttpHeaders._
import spray.can.server.ServerSettings

class AutoBahn extends FreeSpec with Eventually{

  "Hello World" in {
    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET,  "/mychat", List(
      Host("server.example.com", 80),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    class SocketServer extends Actor{
      def receive = {

        case x: Tcp.Connected =>
          println("Connected")
          sender ! Register(self) // normal Http server init

        case req: HttpRequest =>
          println("Request" + req)
          // Upgrade the connection to websockets if you think the incoming
          // request looks good
          if (true){
            sender ! Sockets.acceptAllFunction(req) // helper to craft http response
            sender ! Sockets.UpgradeServer(self) // upgrade the pipeline
          }

        case Sockets.Upgraded =>
          println("Upgraded!")
          // do nothing

        case f @ Frame(fin, rsv, opcode, maskingKey, data) =>
          println("Got Frame " + opcode)
          // Reply to frames with the text content capitalized

          sender ! f.copy(maskingKey = None)
          system.scheduler.schedule(0 seconds, 1 second){
            sender ! f.copy(maskingKey = None, data = ByteString("i am cow"))
          }

        case x =>
          println("Unknown " + x)
      }
    }

    implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")
    val server = system.actorOf(Props(new SocketServer))

    IO(Sockets) ! Http.Bind(
      server,
      "localhost",
      9001,
      settings=Some(ServerSettings(system).copy(sslEncryption = true))
    )

    Thread.sleep(100000000000000000L)
  }
}
