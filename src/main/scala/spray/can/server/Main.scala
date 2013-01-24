package spray.can.server

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.pattern.ask
import spray.util._
import spray.io._
import websockets.SocketServer


object Main  {
  def main(args: Array[String]){
    implicit val system = ActorSystem("echo-server")

    val server = system.actorOf(Props(new SocketServer(system.actorFor("noob"))), name = "echo-server")

    server.ask(IOServer.Bind("localhost", 80))(1 second span)
          .onSuccess { case IOServer.Bound(endpoint, _) => println("\nBound echo-server to " + endpoint) }
  }

}
