package spray.can.server.websockets

import org.scalatest.FreeSpec
import org.scalatest.concurrent.Eventually
import akka.io.{Tcp, IO}
import spray.can.Http
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.can.server.websockets.model.OpCode.{Binary, Text}
import akka.util.ByteString
import spray.http.{HttpResponse, HttpHeaders, HttpMethods, HttpRequest}
import akka.io.Tcp.Register
import scala.concurrent.duration._
import spray.http.HttpHeaders.{Connection, RawHeader, Host}

class AutoBahn extends FreeSpec with Eventually{

  "Server" in {
    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request

    class SocketServer extends Actor{
      def receive = {
        case x: Tcp.Connected => sender ! Register(self) // normal Http server init
        case req: HttpRequest => sender ! Sockets.UpgradeServer(Sockets.acceptAllFunction(req), self) // upgrade the pipeline
        case Sockets.Upgraded => // do nothing
        case f @ Frame(fin, rsv, Text | Binary, maskingKey, data) => sender ! f.copy(maskingKey = None)
        case x =>
      }
    }

    implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")
    val server = system.actorOf(Props(new SocketServer))

    IO(Sockets) ! Http.Bind(
      server,
      "localhost",
      9001/*,
      settings=Some(ServerSettings(system).copy(sslEncryption = true))*/
    )

    Thread.sleep(100000000000000000L)
  }/*
  "Client" in {
    //"/runCase?case=1&agent=cow/0.6.3"
    implicit val system = ActorSystem()
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET, "/", List(
      Host("192.168.37.128", 9001),
      RawHeader("Upgrade", "websocket"),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
      RawHeader("Sec-WebSocket-Protocol", "chat"),
      RawHeader("Sec-WebSocket-Version", "13"),
      RawHeader("Origin", "lihaoyi.com")
    ))

    class SocketClient extends Actor{
      var result: Frame = null
      var count = 0
      var total = 0
      var senders = Set.empty[ActorRef]
      def receive = {
        case x: Tcp.Connected =>
          println("Client Connected")
          if (total == 0)
            sender ! Sockets.UpgradeClient(upgradeReq.copy(uri=s"/getCaseCount"), self)
          else if (count < total)
            sender ! Sockets.UpgradeClient(upgradeReq.copy(uri=s"/runCase?case=$count&agent=spraywebsockets"), self)
          else{
            sender ! Sockets.UpgradeClient(upgradeReq.copy(uri=s"/updateReports?agent=spraywebsockets"), self)
            context.stop(self)
          }
          count += 1

        case resp: HttpResponse => println("Client Response " + resp)

        case Sockets.Upgraded => println("Client Upgraded")

        case f: Frame  if f.opcode == Text || f.opcode == Binary =>
          if (total == 0)
            total = f.data.utf8String.toInt

          sender ! f.copy(maskingKey=Some(31337))
          result = f // save the result

        case c: Tcp.ConnectionClosed =>

          if (!senders.contains(sender)){
            println("Client Closed " + c + sender)
            senders = senders + sender
            IO(Sockets) ! Http.Connect("localhost", 9001)
          }

        case x =>
      }
    }

    implicit val client = system.actorOf(Props(new SocketClient))
    IO(Sockets) ! Http.Connect("localhost", 9001)

    Thread.sleep(100000000000000000L)
  }*/

}
