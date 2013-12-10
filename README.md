SprayWebSockets
===============

This is a implementation of a websocket server and client for the spray.io web toolkit. It is currently a work in progress, but it has a pretty comprehensive test suite that exercises [a whole bunch of functionality](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketServerTests.scala). The server/client can:

- Work under SSL (all the tests are done both in the clear and under SSL)
- Handle fragmented messages (the server will buffer up a complete message before passing it to your `frameHandler`
- Cut off messages which are too big (whether single-messages or fragmented)
- Kill the connection when there's a protocol violation according to the websocket spec (probably doesn't account for everything at the moment)
- Automatically respond to pings with pongs
- Match up outgoing Pings and incoming Pongs to find round trip times
- Automatically ping every client according to the `autoPingInterval`, using the `pingGenerator` to generate the body of each ping
- Pass almost the entire [Autobahn Test Suite](http://autobahn.ws/testsuite/)

`client-report.html` and `sever-report.html` contain the Autobahn test reports for both the server and the client. The only tests currently failing are unicode strictness tests, which means the server isn't killing connections in the case of malformed unicode as strictly as it should.

SprayWebSockets isnt currently hosted on any maven repository; you can add

```scala
.dependsOn(uri("git://github.com/lihaoyi/SprayWebSockets.git"))
```

to your `Build.scala` project to make it work

Getting Started
---------------

The main class of interest is [Sockets](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/Sockets.scala). Here's a [full example](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketExample.scala) of its use:

```scala
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
```

It is essentially an extended `Http` server. In fact it should be a drop-in replacement for a HttpServer: as long as you don't use any websocket functionality (i.e. never send `UpgradeClient` or `UpgradeServer` messages) the behavior should be identical.

### Decide how you want to handle the websocket handshakes 

A websocket handshake is similar to an exchange of HttpRequest/Response, and the `Sockets` server re-uses all the existing http infrastructure to handle it. When a websocket request comes in, the listener you gave to the `Bind` command will receive a message that looks like:

```
GET /mychat HTTP/1.1
Host: server.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==
Sec-WebSocket-Protocol: chat
Sec-WebSocket-Version: 13
Origin: http://example.com
SocketServer response:
```

This is the client half of the websocket handshake, which your `MessageHandler` will receive as a `HttpRequest`. If you want to accept it and upgrade into a websocket connection, you must upgrade the connection with a `HttpResponse` which looks like

```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=
Sec-WebSocket-Protocol: chat
```

`Sockets.acceptAllFunction(req)` is a convenience function provided to handle the most common case where you just want to accept the message and upgrade the connection with a default reply.

So far all this stuff is just normal usage of the `Http` server. This logic lives in your actor's `receive` method with the rest of your http handling stuff, and you can ignore/reject the request too if you don't want to handle it.

When you're done with the handshake, you must reply with an `Sockets.UpgradeServer/UpgradeClient` message so the server can shift that connection into websocket-mode. The `Sockets` server will swap out the http-related pipeline with a websocket pipeline. Upgrade is defined as:

```scala
object UpgradeServer{
  def apply(resp: HttpResponse,
            frameHandler: ActorRef,
            frameSizeLimit: Int = Int.MaxValue)
           (implicit extraStages: ServerPipelineStage = EmptyPipelineStage) = ...
}
object UpgradeClient{
  def apply(req: HttpRequest,
            frameHandler: ActorRef,
            frameSizeLimit: Int = Int.MaxValue,
            maskGen: () => Int = () => util.Random.nextInt())
           (implicit extraStages: ClientPipelineStage = AutoPong(maskGen)) = ...
}
```

The `frameHandler` is the actor that will receive the websocket traffic. It can be the same one, as in the example above, or a different actor. `frameSizeLimit` specifies how big frames are allowed to get before being rejected. `extraStages` allows you to inject extra behavior into the websocket pipeline, the value of which will be explained later.

Note how the handshake's `req` and `resp` are part of the `Upgrade` message. This ensures that the response handling and upgrading appears to happen atomically to the outside world, avoiding race conditions.

###Define a proper frameHandler 

The `frameHandler` will then be sent a `SocketServer.Upgraded` message. The connection is now open, and the `frameHandler` can now:

- Send `model.Frame` messages
- Receive `model.Frame` messages

to the sender of the `Upgraded` message. Each `Frame` is defined as:

```scala
case class Frame(FIN: Boolean = true,
                 RSV: byte = 0,
                 opcode: OpCode,
                 maskingKey: Option[Int] = None,
                 data: ByteString = ByteString.empty)
```

and represents a single websocket frame. Sending a `Frame` pipes it across the internet to whoever is on the other side, and any frames he pipes back will hit your `frameHandler`'s `receive` method. You've opened your first websocket connection! This is where the `Sockets` server's job ends and your application can send as many outgoing `Frame`s as it likes do whatever you want with the incoming `Frame`s.

All the messages that the `frameHandler` can expect to send/receive from the `Sockets` server are documented in the `Sockets` [companion object](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/Sockets.scala).

###Close the Connection

In order to close the connection, the `frameHandler` should send a `Frame` with `opcode = OpCode.ConnectionClose` to comply with the websocket protocol, before sending a `SocketServer.Close` message to actually terminate the TCP connection. The *Frame Handler* will then receive a `SocketServer.Closed` message. If the client initiates a close (whether cleanly via a `ConnectionClose` frame, or by abruptly cutting off the TCP connection) the `frameHandler` will just receive the `SocketServer.Closed` message directly.

Additional Configuration
------------------------

The `UpgradeServer` and `UpgradeClient` messages optionally take an additional `extraStages` argument. This lets you inject any sort of behavior on top of the existing websocket parsing and consolidation phases. Two built in stages are:

- `AutoPing`: used on a server, this phase generates `Ping` frames at a regular interval, matches up the corresponding `Pong`s and sends `RoundTripTime` messages to the `frameHandler`. This stage also hides the `Ping`s from the `frameHandler`
- `AutoPong`: used on a client (by default), this automatically responds to `Ping` messages with `Pong`s so your `frameHandler` doesn't need to deal with them, and doesn't even see them

These stages can easily be added, removed or even replaced with custom stages. For example, if you want to perform extra filters or transforms on the incoming Frames, you can simply insert them as an additional stage in the websocket pipeline.

