package spray.can.server.websockets

import model._
import model.OpCode.Text
import org.scalatest.FreeSpec
import akka.actor.{Props, Actor, ActorSystem}
import concurrent.duration._
import spray.io.{SingletonHandler, IOClientConnection, IOServer}
import akka.pattern._
import concurrent.Await
import concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import java.nio.ByteBuffer
import akka.testkit.TestActorRef
import spray.io.IOBridge.Received
import java.nio.charset.CharsetEncoder
import spray.http.HttpRequest

class SocketServerTests extends FreeSpec{
  implicit val system = ActorSystem()
  implicit val timeout = Timeout(5 seconds)

  val websocketClientHandshake =
    "GET /mychat HTTP/1.1\r\n" +
    "Host: server.example.com\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"

  class AcceptActor extends Actor{
    def receive = {
      case req: HttpRequest =>
        sender ! SocketServer.acceptAllFunction(req)
        sender ! Upgrade(1)
    }
  }
  class EchoActor extends Actor{
    var count = 0
    def receive = {
      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        count = count + 1
        sender ! FrameCommand(Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count).getBytes))
    }
  }
  implicit class blockFuture[T](f: concurrent.Future[T]){
    def await[A] = Await.result(f, 10 seconds).asInstanceOf[A]
  }
  "hello world with echo server" in {
    val httpHandler = SingletonHandler(TestActorRef(new AcceptActor))
    val frameHandler = SingletonHandler(TestActorRef(new EchoActor))
    val server = TestActorRef(Props(SocketServer(httpHandler, frameHandler)))
    server.ask(IOServer.Bind("localhost", 80))

    val connection = TestActorRef(Props(new IOClientConnection{}))

    val r1 = (connection ? IOClientConnection.Connect("localhost", 80)).await[Any]
    println(r1)

    val r2 = (connection ? IOClientConnection.Send(ByteBuffer.wrap(websocketClientHandshake.getBytes))).await[Received]
    def frame = Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am cow".getBytes)
    val r3 = (connection ? IOClientConnection.Send(ByteBuffer.wrap(Frame.write(frame)))).await[Received]
    assert(Frame.read(r3.buffer).stringData === "I AM COW1")

    val r4 = (connection ? IOClientConnection.Send(ByteBuffer.wrap(Frame.write(frame)))).await[Received]
    assert(Frame.read(r4.buffer).stringData === "I AM COW2")
  }


}
