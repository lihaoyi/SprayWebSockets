package spray.can.server.websockets

import model._
import model.OpCode.Text
import org.scalatest.FreeSpec
import akka.actor.{ActorRef, Props, Actor, ActorSystem}
import concurrent.duration._
import spray.io.{SingletonHandler, IOClientConnection, IOServer}
import akka.pattern._
import concurrent.Await
import akka.util.Timeout
import java.nio.ByteBuffer
import spray.io.IOBridge.Received
import spray.http.HttpRequest

class SocketServerTests extends FreeSpec{
  implicit val system = ActorSystem()
  implicit val timeout = Timeout(5 seconds)
  implicit class blockActorRef(a: ActorRef){
    def send(b: Array[Byte]) = {
      a ! IOClientConnection.Send(ByteBuffer.wrap(b))
    }
    def await(b: Array[Byte]) = {
      Await.result(a ? IOClientConnection.Send(ByteBuffer.wrap(b)), 1 seconds).asInstanceOf[Received]
    }
  }
  val websocketClientHandshake =
    "GET /mychat HTTP/1.1\r\n" +
    "Host: server.example.com\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"

  "Echo Server Tests" - {
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
          println("LOL")
          count = count + 1
          sender ! FrameCommand(Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count).getBytes))
      }
    }
    def setupConnection(port: Int) = {
      val httpHandler = SingletonHandler(system.actorOf(Props(new AcceptActor)))
      val frameHandler = SingletonHandler(system.actorOf(Props(new EchoActor)))
      val server = system.actorOf(Props(SocketServer(httpHandler, frameHandler)))
      server.ask(IOServer.Bind("localhost", port))

      val connection = system.actorOf(Props(new IOClientConnection{}))
      Await.result(connection ? IOClientConnection.Connect("localhost", port), 10 seconds)

      connection await websocketClientHandshake.getBytes

      connection
    }

    "hello world with echo server" in {
      val connection = setupConnection(1001)

      def frame = Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am cow".getBytes)
      val r3 = connection await Frame.write(frame)
      assert(Frame.read(r3.buffer).stringData === "I AM COW1")

      val r4 = connection await Frame.write(frame)
      assert(Frame.read(r4.buffer).stringData === "I AM COW2")
    }
    "Fragmentation" in {
      val connection = setupConnection(10001)

      val result1 = {
        connection send Frame.write(Frame(false, (false, false, false), OpCode.Text, Some(12345123), "i am cow ".getBytes))
        Thread.sleep(100)
        connection send Frame.write(Frame(false, (false, false, false), OpCode.Continuation, Some(2139), "hear me moo ".getBytes))
        Thread.sleep(100)
        connection send Frame.write(Frame(false, (false, false, false), OpCode.Continuation, Some(-23), "i weigh twice as much as you ".getBytes))
        Thread.sleep(100)
        connection await Frame.write(Frame(true, (false, false, false), OpCode.Continuation, Some(-124123212), "and i look good on the barbecue ".getBytes))
      }
      assert(Frame.read(result1.buffer).stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        connection send Frame.write(Frame(false, (false, false, false), OpCode.Text, Some(12345123), "yoghurt curds cream cheese and butter ".getBytes))
        Thread.sleep(100)
        connection send Frame.write(Frame(false, (false, false, false), OpCode.Continuation, Some(2139), "comes from liquids from my udder ".getBytes))
        Thread.sleep(100)
        connection await Frame.write(Frame(true, (false, false, false), OpCode.Text, Some(-23), "i am cow, i am cow, hear me moooo ".getBytes))
      }
      assert(Frame.read(result2.buffer).stringData === "YOGHURT CURDS CREAM CHEESE AND BUTTER COMES FROM LIQUIDS FROM MY UDDER I AM COW, I AM COW, HEAR ME MOOOO 2")

    }

  }
}
