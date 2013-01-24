package spray.can.server

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.pattern.ask
import spray.util._
import spray.io._
import websockets.model.OpCode.Text
import websockets.model.{OpCode, Frame}
import websockets.SocketServer


object Main  {
  def main(args: Array[String]){
    implicit val system = ActorSystem("echo-server")

    val server = system.actorOf(Props(SocketServer(system.actorOf(Props(new EchoActor)))), name = "echo-server")

    server.ask(IOServer.Bind("localhost", 80))(1 second span)
          .onSuccess { case IOServer.Bound(endpoint, _) => println("\nBound echo-server to " + endpoint) }
  }
}
class EchoActor extends Actor{
  var count = 0
  def receive = {
    case f @ Frame(fin, rsv, Text, maskingKey, data) =>
      println("Received " + f)
      count = count + 1
      sender ! Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count).getBytes)
  }
}