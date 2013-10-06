SprayWebSockets
===============

This is a implementation of a websocket server for the spray.io web toolkit. It is currently a work in progress, but it has a pretty comprehensive test suite that exercises [a whole bunch of functionality](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketServerTests.scala), as well as a simple [demo applicatoin](http://www.textboxplus.com/). The current discussion thread is over [here](https://groups.google.com/forum/?fromgroups=#!topic/spray-user/KWlUhXs7kvs).

Getting Started
---------------

The basic workflow for taking an existing `HttpServer` application and making it support websockets is:

### Substitute Sockets in place of Http

The main class of interest is [Sockets](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/Sockets.scala). Here's a [full example](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketExample.scala) of its use:

```scala
implicit val system = ActorSystem()

// Define the server that accepts http requests and upgrades them
class Server extends Actor{
  def receive = {
    case req: HttpRequest =>
      sender ! Sockets.acceptAllFunction(req)
      sender ! Sockets.Upgrade(self)

    case x: Connected =>
      sender ! Register(self)

    case f @ Frame(fin, rsv, Text, maskingKey, data) =>
      sender ! Frame(fin, rsv, Text, None, ByteString(f.stringData.toUpperCase))
  }
}

IO(Sockets) ! Http.Bind(TestActorRef(new Server), "localhost", 12345)

// A crappy ad-hoc websocket client
val client = TestActorRef(new Util.TestClientActor(ssl = false))

IO(Tcp).!(Tcp.Connect(new InetSocketAddress("localhost", 12345)))(client)

// Send the http-ish handshake
client await Tcp.Write(ByteString(
  "GET /mychat HTTP/1.1\r\n" +
  "Host: server.example.com\r\n" +
  "Upgrade: websocket\r\n" +
  "Connection: Upgrade\r\n" +
  "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"
))

// Send the websocket frame and wait for response
val res = client await Frame(
  true,
  (false, false, false),
  OpCode.Text,
  Some(12345123),
  ByteString("i am cow")
)

assert(res.data.decodeString("UTF-8") == "I AM COW") // capitalized response
```

It is essentially an extended `Http` server. In fact it should be a drop-in replacement for a HttpServer: as long as you don't use any websocket functionality (i.e. never send `Upgrade` messages) the behavior should be identical.

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
Server response:
```

This is the client half of the websocket handshake, which your `MessageHandler` will receive as a `HttpRequest`. If you want to accept it and upgrade into a websocket connection, you must reply with a `HttpResponse` which looks like

```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=
Sec-WebSocket-Protocol: chat
```

`Sockets.acceptAllFunction(req)` is a convenience function provided to handle the most common case where you just want to accept the message and upgrade the connection with the default reply.

So far all this stuff is just normal usage of the `Http` server. This logic lives in your actor's `receive` method with the rest of your http handling stuff, and you can ignore/reject the request too if you don't want to handle it.

When you're done with the handshake, you must reply with an `Sockets.Upgrade` message so the server can shift that connection into websocket-mode. The `Sockets` server will swap out the http-related pipeline with a websocket pipeline. Upgrade is defined as:

```scala
case class Upgrade(frameHandler: ActorRef,
                   autoPingInterval: Duration = Duration.Inf,
                   pingGenerator: () => Array[Byte] = () => Array(),
                   frameSizeLimit: Int = Int.MaxValue) extends Command
```

The `frameHandler` is the actor that will receive the websocket traffic. It can be the same one, as in the example above, or a different actor. The other parameters are optional and are mostly self-explanatory.

###Define a proper frameHandler 

The `frameHandler` will then be sent a `SocketServer.Connected` message. The connection is now open, and the `frameHandler` can now:

- Send `model.Frame` messages
- Receive `model.Frame` messages

to the sender of the `Connected` message. Each `Frame` is defined as:

```scala
case class Frame(FIN: Boolean = true,
                 RSV: (Boolean, Boolean, Boolean) = (false, false, false),
                 opcode: OpCode,
                 maskingKey: Option[Int] = None,
                 data: ByteString = ByteString.empty)
```

and represents a single websocket frame. Sending a `Frame` pipes it across the internet to whoever is on the other side, and any frames he pipes back will hit your `frameHandler`'s `receive` method. You've opened your first websocket connection! This is where the `Sockets` server's job ends and your application can send as many outgoing `Frame`s as it likes do whatever you want with the incoming `Frame`s.

###Close the Connection

In order to close the connection, the `frameHandler` should send a `Frame` with `opcode = OpCode.ConnectionClose` to comply with the websocket protocol, before sending a `SocketServer.Close` message to actually terminate the TCP connection. The *Frame Handler* will then receive a `SocketServer.Closed` message. If the client initiates a close (whether cleanly via a `ConnectionClose` frame, or by abruptly cutting off the TCP connection) the `frameHandler` will just receive the `SocketServer.Closed` message directly.

More Stuff
----------

All the messages that the `frameHandler` can expect to send/receive from the `Sockets` server are documented in the `Sockets` [companion object](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/Sockets.scala).

The server also can

- Automatically ping every client according to the `autoPingInterval`, using the `pingGenerator` to generate the body of each ping
- Work under SSL (all the tests are done both in the clear and under SSL)
- Handle fragmented messages (the server will buffer up a complete message before passing it to your `frameHandler`
- Cut off messages which are too big (whether single-messages or fragmented)
- Automatically respond to pings with pongs
- Match up outgoing Pings and incoming Pongs to find round trip times
- Kill the connection when there's a protocol violation according to the websocket spec (probably doesn't account for everything at the moment)


